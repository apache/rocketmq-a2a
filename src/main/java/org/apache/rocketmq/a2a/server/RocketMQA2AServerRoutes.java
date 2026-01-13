/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.a2a.server;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.rocketmq.a2a.common.RocketMQRequest;
import org.apache.rocketmq.a2a.common.RocketMQResponse;
import io.a2a.server.ServerCallContext;
import io.a2a.server.apps.quarkus.A2AServerRoutes;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.GetAuthenticatedExtendedCardRequest;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.IdJsonMappingException;
import io.a2a.spec.InternalError;
import io.a2a.spec.InvalidParamsError;
import io.a2a.spec.InvalidParamsJsonMappingException;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.JSONParseError;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCErrorResponse;
import io.a2a.spec.JSONRPCRequest;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.MethodNotFoundJsonMappingException;
import io.a2a.spec.NonStreamingJSONRPCRequest;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.StreamingJSONRPCRequest;
import io.a2a.spec.TaskResubscriptionRequest;
import io.a2a.spec.UnsupportedOperationError;
import io.a2a.transport.jsonrpc.handler.JSONRPCHandler;
import io.quarkus.runtime.Startup;
import io.quarkus.vertx.web.ReactiveRoutes.ServerSentEvent;
import io.smallrye.mutiny.Multi;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.a2a.util.Utils.OBJECT_MAPPER;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.METHOD;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.buildConsumer;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.buildConsumerForLite;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.buildMessage;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.buildProducer;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.toJsonString;

/**
 * An A2A-protocol-compliant service router implemented on top of RocketMQ,
 * primarily used to parse incoming RocketMQ messages and forward requests to the corresponding handlers
 */
@Startup
@Singleton
public class RocketMQA2AServerRoutes extends A2AServerRoutes {
    private static final Logger log = LoggerFactory.getLogger(RocketMQA2AServerRoutes.class);
    //The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
    private static final String ROCKETMQ_ENDPOINT = System.getProperty("rocketMQEndpoint", "");
    //Used for logical isolation of different business units or environments
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace", "");
    //The standard RocketMQ business topic bound to the Agent, used for receiving task requests and other information
    private static final String BIZ_TOPIC = System.getProperty("bizTopic", "");
    //The CID used to subscribe to the standard business topic bound to the Agent
    private static final String BIZ_CONSUMER_GROUP = System.getProperty("bizConsumerGroup", "");
    //RocketMQ Account Name
    private static final String ACCESS_KEY = System.getProperty("rocketMQAK", "");
    //RocketMQ Account Password
    private static final String SECRET_KEY = System.getProperty("rocketMQSK", "");
    //The LiteTopic on the server, used to receive point-to-point request messages sent from clients to the server
    private static final String WORK_AGENT_RESPONSE_TOPIC = System.getProperty("workAgentResponseTopic","");
    //The CID for subscribing to the server-side LiteTopic
    private static final String WORK_AGENT_RESPONSE_GROUP_ID = System.getProperty("workAgentResponseGroupID","");

    private static volatile Runnable streamingMultiSseSupportSubscribedRunnable;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(6, 6, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10_0000), new CallerRunsPolicy());
    //The RocketMQ Producer used to send response results
    private Producer producer;
    //The standard PushConsumer used to receive request messages.
    private PushConsumer pushConsumer;
    //The consumer for receiving request messages during point-to-point interactions from clients to server
    private LitePushConsumer litePushConsumer;
    private String serverLiteTopic;
    //Used to send SSE data streams
    private MultiSseSupport multiSseSupport;

    //The JSONRPCHandler in the A2A protocol
    @Inject
    JSONRPCHandler jsonRpcHandler;

    @PostConstruct
    public void init() {
        try {
            //Check param
            checkConfigParam();
            //Init producer
            this.producer = buildProducer(ROCKETMQ_NAMESPACE, ROCKETMQ_ENDPOINT, ACCESS_KEY, SECRET_KEY);
            //Init pushConsumer
            this.pushConsumer = buildConsumer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, BIZ_CONSUMER_GROUP, BIZ_TOPIC, buildMessageListener());
            //Initialize the SSE data stream handler
            this.multiSseSupport = new MultiSseSupport(this.producer);
            //Init litePushConsumer
            this.litePushConsumer = buildConsumerForLite(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, WORK_AGENT_RESPONSE_GROUP_ID, WORK_AGENT_RESPONSE_TOPIC, buildMessageListener());
            //Init serverLiteTopic
            this.serverLiteTopic = UUID.randomUUID().toString();
            this.litePushConsumer.subscribeLite(serverLiteTopic);
            log.info("RocketMQA2AServerRoutes init success, server liteTopic: {}", serverLiteTopic);
        } catch (Exception e) {
            log.error("RocketMQA2AServerRoutes error: {}", e.getMessage());
        }
    }

    /**
     * Build the client message listener
     * @return MessageListener
     */
    private MessageListener buildMessageListener() {
        return messageView -> {
            CompletableFuture<Boolean> completableFuture = null;
            try {
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                String messageStr = new String(result, StandardCharsets.UTF_8);
                //Deserialize into RocketMQRequest
                RocketMQRequest request = JSON.parseObject(messageStr, RocketMQRequest.class);
                boolean streaming = false;
                String body = request.getRequestBody();
                JSONRPCResponse<?> nonStreamingResponse = null;
                Multi<? extends JSONRPCResponse<?>> streamingResponse = null;
                JSONRPCErrorResponse error = null;
                try {
                    //Deserialize the body data into a JsonNode
                    JsonNode node = OBJECT_MAPPER.readTree(body);
                    JsonNode method = node != null ? node.get(METHOD) : null;
                    //Determine whether the method type is streaming
                    streaming = method != null && (SendStreamingMessageRequest.METHOD.equals(method.asText()) || TaskResubscriptionRequest.METHOD.equals(method.asText()));
                    if (streaming) {
                        //Deserialize to obtain a StreamingJSONRPCRequest
                        StreamingJSONRPCRequest<?> streamingJSONRPCRequest = OBJECT_MAPPER.treeToValue(node, StreamingJSONRPCRequest.class);
                        //Process the streaming request and obtain a streaming response output object
                        streamingResponse = processStreamingRequest(streamingJSONRPCRequest, null);
                    } else {
                        //Deserialize to obtain a NonStreamingJSONRPCRequest
                        NonStreamingJSONRPCRequest<?> nonStreamingJSONRPCRequest = OBJECT_MAPPER.treeToValue(node, NonStreamingJSONRPCRequest.class);
                        //Process the non-streaming request and obtain the corresponding response
                        nonStreamingResponse = processNonStreamingRequest(nonStreamingJSONRPCRequest, null);
                    }
                } catch (JsonProcessingException e) {
                    //handle JsonProcessingException
                    error = handleError(e);
                } catch (Throwable t) {
                    error = new JSONRPCErrorResponse(new InternalError(t.getMessage()));
                } finally {
                    RocketMQResponse response = null;
                    //If an error occurs
                    if (error != null) {
                        response = new RocketMQResponse();
                        response.setEnd(true);
                        response.setStream(false);
                        response.setServerLiteTopic(serverLiteTopic);
                        response.setServerWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
                        response.setLiteTopic(request.getLiteTopic());
                        //set error info
                        response.setResponseBody(JSON.toJSONString(error));
                        //Set the MessageId sent by the client for result correlation on the client side
                        response.setMessageId(messageView.getMessageId().toString());
                    //Handle streaming requests
                    } else if (streaming) {
                        final Multi<? extends JSONRPCResponse<?>> finalStreamingResponse = streamingResponse;
                        log.info("RocketMQA2AServerRoutes streaming finalStreamingResponse: {}", JSON.toJSONString(finalStreamingResponse));
                        completableFuture = new CompletableFuture<>();
                        CompletableFuture<Boolean> finalCompletableFuture = completableFuture;
                        //Submit an SSE data-sending task to the thread pool, passing in a CompletableFuture to receive notification upon task completion
                        this.executor.execute(() -> {
                            this.multiSseSupport.subscribeObjectRocketMQ(finalStreamingResponse.map(i -> (Object)i), null, request.getWorkAgentResponseTopic(), request.getLiteTopic(), messageView.getMessageId().toString(), finalCompletableFuture);
                        });
                    //Handle non-streaming requests
                    } else {
                        response = new RocketMQResponse();
                        response.setEnd(true);
                        response.setStream(false);
                        response.setServerLiteTopic(serverLiteTopic);
                        response.setServerWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
                        response.setLiteTopic(request.getLiteTopic());
                        response.setMessageId(messageView.getMessageId().toString());
                        //Set the data in the non-streaming response object
                        response.setResponseBody(toJsonString(nonStreamingResponse));
                    }
                    if (null != response) {
                        //Send the response result by invoking the Producer
                        SendReceipt send = this.producer.send(buildMessage(request.getWorkAgentResponseTopic(), request.getLiteTopic(), response));
                        log.info("RocketMQA2AServerRoutes send nonStreamingResponse success, msgId: {}, time: {}, " + "response: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    }
                }
            } catch (Exception e) {
                log.error("RocketMQA2AServerRoutes error: {}", e.getMessage());
                return ConsumeResult.FAILURE;
            }
            if (null != completableFuture) {
                try {
                    //By obtaining the completion status of CompletableFuture.
                    if (Boolean.TRUE.equals(completableFuture.get(15, TimeUnit.MINUTES))) {
                        log.info("RocketMQA2AServerRoutes deal msg success");
                        return ConsumeResult.SUCCESS;
                    } else {
                        log.info("RocketMQA2AServerRoutes deal msg failed");
                        return ConsumeResult.FAILURE;
                    }
                } catch (Exception e) {
                    log.error("RocketMQA2AServerRoutes error: {}", e.getMessage());
                    return ConsumeResult.FAILURE;
                }
            }
            return ConsumeResult.SUCCESS;
        };
    }

    /**
     * Handle JSON-related exceptions
     * @param exception JsonProcessingException
     * @return JSONRPCErrorResponse
     */
    private JSONRPCErrorResponse handleError(JsonProcessingException exception) {
        Object id = null;
        JSONRPCError jsonRpcError = null;
        //Determine whether the cause of the exception is JsonParseException
        if (exception.getCause() instanceof JsonParseException) {
            jsonRpcError = new JSONParseError();
        //Check if the exception is a JsonEOFException
        } else if (exception instanceof JsonEOFException) {
            jsonRpcError = new JSONParseError(exception.getMessage());
        //Check if the exception is a MethodNotFoundJsonMappingException
        } else if (exception instanceof MethodNotFoundJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new MethodNotFoundError();
        //Check if the exception is an InvalidParamsJsonMappingException
        } else if (exception instanceof InvalidParamsJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidParamsError();
        //Check if the exception is an IdJsonMappingException
        } else if (exception instanceof IdJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidRequestError();
        } else {
            jsonRpcError = new InvalidRequestError();
        }
        //Return the JSON-RPC error response result.
        return new JSONRPCErrorResponse(id, jsonRpcError);
    }

    /**
     * Handle non-streaming requests
     * @param request NonStreamingJSONRPCRequest
     * @param context ServerCallContext
     * @return
     */
    private JSONRPCResponse<?> processNonStreamingRequest(NonStreamingJSONRPCRequest<?> request,
        ServerCallContext context) {
        //Check if the request is a GetTaskRequest
        if (request instanceof GetTaskRequest req) {
            return jsonRpcHandler.onGetTask(req, context);
        //Check if the request is a CancelTaskRequest
        } else if (request instanceof CancelTaskRequest req) {
            return jsonRpcHandler.onCancelTask(req, context);
        //Check if the request is a SetTaskPushNotificationConfigRequest
        } else if (request instanceof SetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.setPushNotificationConfig(req, context);
        //Check if the request is a GetTaskPushNotificationConfigRequest
        } else if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.getPushNotificationConfig(req, context);
        //Check if the request is a SendMessageRequest
        } else if (request instanceof SendMessageRequest req) {
            return jsonRpcHandler.onMessageSend(req, context);
        //Check if the request is a ListTaskPushNotificationConfigRequest
        } else if (request instanceof ListTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.listPushNotificationConfig(req, context);
        //Check if the request is a DeleteTaskPushNotificationConfigRequest
        } else if (request instanceof DeleteTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.deletePushNotificationConfig(req, context);
        //Check if the request is a GetAuthenticatedExtendedCardRequest
        } else if (request instanceof GetAuthenticatedExtendedCardRequest req) {
            return jsonRpcHandler.onGetAuthenticatedExtendedCardRequest(req, context);
        } else {
            //return ErrorResponse
            return generateErrorResponse(request, new UnsupportedOperationError());
        }
    }

    /**
     * Handle streaming requests.
     * @param request JSONRPCRequest
     * @param context ServerCallContext
     * @return Multi<? extends JSONRPCResponse<?>> Asynchronous data stream
     */
    private Multi<? extends JSONRPCResponse<?>> processStreamingRequest(JSONRPCRequest<?> request, ServerCallContext context) {
        Flow.Publisher<? extends JSONRPCResponse<?>> publisher;
        //If the request is SendStreamingMessageRequest
        if (request instanceof SendStreamingMessageRequest req) {
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
        //If the request is TaskResubscriptionRequest
        } else if (request instanceof TaskResubscriptionRequest req) {
            publisher = jsonRpcHandler.onResubscribeToTask(req, context);
        //Return a response indicating that the request is not supported.
        } else {
            return Multi.createFrom().item(generateErrorResponse(request, new UnsupportedOperationError()));
        }
        return Multi.createFrom().publisher(publisher);
    }

    /**
     * Construct an error response result
     * @param request JSONRPCRequest
     * @param error JSONRPCError
     * @return JSONRPCResponse
     */
    private JSONRPCResponse<?> generateErrorResponse(JSONRPCRequest<?> request, JSONRPCError error) {
        return new JSONRPCErrorResponse(request.getId(), error);
    }

    static void setStreamingMultiSseSupportSubscribedRunnable(Runnable runnable) {
        streamingMultiSseSupportSubscribedRunnable = runnable;
    }

    /**
     * A utility class for handling SSE data transmission, internally using a RocketMQ Producer to send streaming content
     */
    private static class MultiSseSupport {
        private final Producer producer;

        private MultiSseSupport(Producer producer) {
            this.producer = producer;
        }

        /**
         * Write the data stream to RocketMQ
         * @param multi Asynchronous data stream
         * @param rc Routing context
         * @param workAgentResponseTopic A LiteTopic subscribed by the client receiving streaming responses
         * @param liteTopic todo
         * @param msgId The msgId used by the client to map requests and response results
         * @param completableFuture Used to asynchronously receive whether the SSE data transmission is completed
         */
        public void writeRocketmq(Multi<Buffer> multi, RoutingContext rc, String workAgentResponseTopic, String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            multi.subscribe().withSubscriber(new Flow.Subscriber<Buffer>() {
                Flow.Subscription upstream;

                /**
                 * Subscribe to the upstream asynchronous data output stream object
                 * @param subscription a new subscription
                 */
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.upstream = subscription;
                    //Request upstream data
                    this.upstream.request(1);
                    Runnable runnable = streamingMultiSseSupportSubscribedRunnable;
                    if (runnable != null) {
                        runnable.run();
                    }
                }

                /**
                 * Process incremental data from the upstream output
                 * @param item the item
                 */
                @Override
                public void onNext(Buffer item) {
                    try {
                        //Construct a RocketMQResponse object for the incremental data item from the upstream output
                        RocketMQResponse response = new RocketMQResponse(liteTopic, null, item.toString(), msgId, true, false);
                        SendReceipt send = producer.send(buildMessage(workAgentResponseTopic, liteTopic, response));
                        log.info("MultiSseSupport send response success, msgId: {}, time: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    } catch (Exception e) {
                        log.error("MultiSseSupport send stream error, {}", e.getMessage());
                    }
                    //Request upstream data
                    this.upstream.request(1);
                }

                /**
                 * Called when an error occurs upstream
                 * @param throwable the exception
                 */
                @Override
                public void onError(Throwable throwable) {
                    rc.fail(throwable);
                    completableFuture.complete(false);
                }

                /**
                 * Called when the upstream streaming output task is completed
                 */
                @Override
                public void onComplete() {
                    //Construct a RocketMQResponse object to represent a completion status data object
                    RocketMQResponse response = new RocketMQResponse(liteTopic, null, null, msgId, true, true);
                    try {
                        //Send the corresponding response result via RocketMQ Producer
                        SendReceipt send = producer.send(buildMessage(workAgentResponseTopic, liteTopic, response));
                        log.info("MultiSseSupport send response success, msgId: {}, time: {}, response: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    } catch (ClientException e) {
                        log.error("MultiSseSupport error send complete, msgId: {}", e.getMessage());
                    }
                    completableFuture.complete(true);
                }
            });
        }

        /**
         * Subscribe to the upstream streaming output task and send the corresponding data via RocketMQ
         * @param multi Asynchronous data stream
         * @param rc Routing context
         * @param workAgentResponseTopic A LiteTopic subscribed by the client receiving streaming responses
         * @param liteTopic todo
         * @param msgId The msgId used by the client to map requests and response results
         * @param completableFuture Used to asynchronously receive whether the SSE data transmission is completed
         */
        public void subscribeObjectRocketMQ(Multi<Object> multi, RoutingContext rc, String workAgentResponseTopic,
            String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            AtomicLong count = new AtomicLong();

            //Transform Multi<Object> to  Multi<Buffer>
            Multi<Buffer> map = multi.map(new Function<Object, Buffer>() {
                @Override
                public Buffer apply(Object o) {
                    //If the object is an SSE object, process the data to comply with the SSE data transmission specification
                    if (o instanceof ServerSentEvent) {
                        ServerSentEvent<?> ev = (ServerSentEvent<?>)o;
                        long id = ev.id() != -1 ? ev.id() : count.getAndIncrement();
                        String e = ev.event() == null ? "" : "event: " + ev.event() + "\n";
                        return Buffer.buffer(e + "data: " + toJsonString(ev.data()) + "\nid: " + id + "\n\n");
                    }
                    return Buffer.buffer("data: " + toJsonString(o) + "\nid: " + count.getAndIncrement() + "\n\n");
                }
            });
            //Subscribe to SSE object data and send the data via RocketMQ
            writeRocketmq(map, rc, workAgentResponseTopic, liteTopic, msgId, completableFuture);
        }
    }

    private void checkConfigParam() {
        if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT) || StringUtils.isEmpty(BIZ_TOPIC) || StringUtils.isEmpty(BIZ_CONSUMER_GROUP) || StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC) || StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID)) {
            if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT)) {
                log.error("rocketMQEndpoint is empty");
            }
            if (StringUtils.isEmpty(BIZ_TOPIC)) {
                log.error("bizTopic is empty");
            }
            if (StringUtils.isEmpty(BIZ_CONSUMER_GROUP)) {
                log.error("bizConsumerGroup is empty");
            }
            if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC)) {
                log.error("workAgentResponseTopic is empty");
            }
            if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID)) {
                log.error("workAgentResponseGroupID is empty");
            }
            throw new RuntimeException("RocketMQA2AServerRoutes check init rocketmq param error, init failed!!!");
        }
    }
}
