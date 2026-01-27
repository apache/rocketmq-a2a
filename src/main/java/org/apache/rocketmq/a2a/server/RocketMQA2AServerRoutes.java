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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.apache.rocketmq.a2a.common.model.RocketMQRequest;
import org.apache.rocketmq.a2a.common.model.RocketMQResponse;
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
import org.apache.rocketmq.a2a.common.model.RocketMQResponse.Builder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.shaded.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.a2a.util.Utils.OBJECT_MAPPER;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.METHOD;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildLitePushConsumer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildMessageForResponse;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildProducer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.buildPushConsumer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.toJsonString;

/**
 * An A2A-protocol-compliant service router implemented on top of RocketMQ.
 * <p>
 * This component:
 * <ul>
 *   <li>Subscribes to standard and lite topics to receive client requests</li>
 *   <li>Parses incoming messages as JSON-RPC requests</li>
 *   <li>Routes them to appropriate handlers</li>
 *   <li>Sends responses back via RocketMQ Producer</li>
 *   <li>Supports both streaming and non-streaming RPC calls</li>
 * </ul>
 */
@Startup
@Singleton
public class RocketMQA2AServerRoutes extends A2AServerRoutes {
    private static final Logger log = LoggerFactory.getLogger(RocketMQA2AServerRoutes.class);
    /**
     * The network endpoint of the RocketMQ service.
     */
    private static final String ROCKETMQ_ENDPOINT = System.getProperty("rocketMQEndpoint", "");

    /**
     * The namespace used for logical isolation of RocketMQ resources.
     */
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace", "");

    /**
     * The standard RocketMQ business topic bound to the Agent, used for receiving task requests and other information.
     */
    private static final String BIZ_TOPIC = System.getProperty("bizTopic", "");

    /**
     * The CID used to subscribe to the standard business topic bound to the Agent.
     */
    private static final String BIZ_CONSUMER_GROUP = System.getProperty("bizConsumerGroup", "");

    /**
     * The access key for authenticating with the RocketMQ service.
     */
    private static final String ACCESS_KEY = System.getProperty("rocketMQAK", "");

    /**
     * The secret key for authenticating with the RocketMQ service.
     */
    private static final String SECRET_KEY = System.getProperty("rocketMQSK", "");

    /**
     * The lightweight topic used to receive asynchronous replies.
     */
    private static final String WORK_AGENT_RESPONSE_TOPIC = System.getProperty("workAgentResponseTopic","");

    /**
     * The consumer group ID used when subscribing to the {@link #WORK_AGENT_RESPONSE_TOPIC}.
     */
    private static final String WORK_AGENT_RESPONSE_GROUP_ID = System.getProperty("workAgentResponseGroupID","");

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        6, 6, 60L, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(100_000),
        new ThreadFactoryBuilder().setNameFormat("rocketmq-a2a-sse-pool-%d").build(),
        new CallerRunsPolicy()
    );
    /**
     * The RocketMQ Producer used to send response results.
     */
    private Producer producer;

    /**
     * The standard PushConsumer used to receive request messages.
     */
    private PushConsumer pushConsumer;

    /**
     * The consumer for receiving request messages during point-to-point interactions from clients to server.
     */
    private LitePushConsumer litePushConsumer;

    /**
     * Dynamically generated topic for this server instance to receive direct (point-to-point) requests.
     */
    private String serverLiteTopic;

    /**
     * Used to send SSE data streams.
     */
    private MultiSseSupport multiSseSupport;

    /**
     * The JSONRPCHandler in the A2A protocol.
     */
    @Inject
    JSONRPCHandler jsonRpcHandler;

    @PostConstruct
    public void init() {
        try {
            checkConfigParam();
            this.producer = buildProducer(ROCKETMQ_NAMESPACE, ROCKETMQ_ENDPOINT, ACCESS_KEY, SECRET_KEY);
            this.pushConsumer = buildPushConsumer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, BIZ_CONSUMER_GROUP, BIZ_TOPIC, buildMessageListener());
            this.multiSseSupport = new MultiSseSupport(this.producer);
            this.litePushConsumer = buildLitePushConsumer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, WORK_AGENT_RESPONSE_TOPIC, WORK_AGENT_RESPONSE_GROUP_ID, buildMessageListener());
            //Init serverLiteTopic
            this.serverLiteTopic = UUID.randomUUID().toString();
            this.litePushConsumer.subscribeLite(serverLiteTopic);
            log.info("RocketMQA2AServerRoutes init success, bizTopic: [{}], bizConsumerGroup: [{}], workAgentResponseTopic: [{}], workAgentResponseGroupID: [{}], serverLiteTopic: [{}]", BIZ_TOPIC, BIZ_CONSUMER_GROUP, WORK_AGENT_RESPONSE_GROUP_ID, WORK_AGENT_RESPONSE_TOPIC, serverLiteTopic);
        } catch (Exception e) {
            log.error("RocketMQA2AServerRoutes error", e);
        }
    }

    /**
     * Builds a message listener that processes incoming RocketMQ messages as A2A JSON-RPC requests.
     *
     * @return a configured {@link MessageListener}
     */
    private MessageListener buildMessageListener() {
        return messageView -> {
            CompletableFuture<Boolean> completableFuture = null;
            try {
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                // Deserialize into RocketMQRequest
                RocketMQRequest request = JSON.parseObject(new String(result, StandardCharsets.UTF_8), RocketMQRequest.class);
                boolean streaming = false;
                JSONRPCResponse<?> nonStreamingResponse = null;
                Multi<? extends JSONRPCResponse<?>> streamingResponse = null;
                JSONRPCErrorResponse error = null;
                try {
                    // Deserialize the body data into a JsonNode
                    JsonNode node = OBJECT_MAPPER.readTree(request.getRequestBody());
                    JsonNode method = node != null ? node.get(METHOD) : null;
                    streaming = method != null && (SendStreamingMessageRequest.METHOD.equals(method.asText()) || TaskResubscriptionRequest.METHOD.equals(method.asText()));
                    if (streaming) {
                        streamingResponse = processStreamingRequest(OBJECT_MAPPER.treeToValue(node, StreamingJSONRPCRequest.class), null);
                        completableFuture = new CompletableFuture<>();
                    } else {
                        nonStreamingResponse = processNonStreamingRequest(OBJECT_MAPPER.treeToValue(node, NonStreamingJSONRPCRequest.class), null);
                    }
                } catch (JsonProcessingException e) {
                    error = handleError(e);
                } catch (Throwable t) {
                    error = new JSONRPCErrorResponse(new InternalError(t.getMessage()));
                } finally {
                    dealResponse(request, error, messageView, streaming, nonStreamingResponse, streamingResponse, completableFuture);
                }
            } catch (Exception e) {
                log.error("RocketMQA2AServerRoutes error", e);
                return ConsumeResult.FAILURE;
            }
            return dealCompletableFuture(completableFuture);
        };
    }

    /**
     * Handles the final response for an A2A request: either an error, a non-streaming result,
     * or a streaming result. For non-streaming cases, sends a direct RocketMQ response.
     * For streaming, submits an SSE emission task to the executor.
     *
     * @param request the original request context.
     * @param error the error response if present.
     * @param messageView the received message metadata.
     * @param streaming whether this is a streaming operation.
     * @param nonStreamingResponse the sync result (if not streaming).
     * @param streamingResponse the async stream (if streaming).
     * @param completableFuture completion signal for the streaming task.
     * @throws ClientException if sending the response fails.
     */
    private void dealResponse(RocketMQRequest request, JSONRPCErrorResponse error, MessageView messageView, boolean streaming, JSONRPCResponse<?> nonStreamingResponse, Multi<? extends JSONRPCResponse<?>> streamingResponse, CompletableFuture completableFuture)
        throws ClientException {
        RocketMQResponse response = null;
        if (error != null) {
            response = buildErrorResponse(error, messageView);
        } else if (!streaming) {
            response = buildSuccessResponse(nonStreamingResponse, messageView);
        } else {
            final Multi<? extends JSONRPCResponse<?>> finalStreamingResponse = streamingResponse;
            log.info("RocketMQA2AServerRoutes streaming finalStreamingResponse: {}", JSON.toJSONString(finalStreamingResponse));
            // Submit an SSE data-sending task to the thread pool, passing in a CompletableFuture to receive notification upon task completion
            this.executor.execute(() -> {
                this.multiSseSupport.subscribeObjectRocketMQ(finalStreamingResponse.map(i -> (Object)i), null, request.getWorkAgentResponseTopic(), request.getLiteTopic(), messageView.getMessageId().toString(), completableFuture);
            });
        }
        if (null != response) {
            // Send the response result by invoking the Producer
            SendReceipt send = this.producer.send(buildMessageForResponse(request.getWorkAgentResponseTopic(), request.getLiteTopic(), response));
            log.info("RocketMQA2AServerRoutes send nonStreamingResponse success, msgId: [{}], time: [{}], response: [{}]", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
        }
    }

    /**
     * Constructs a RocketMQ response for failed JSON-RPC calls.
     *
     * @param error the JSON-RPC error details to include in the response body.
     * @param messageView the incoming message context used to extract message ID and trace context.
     * @return a fully built {@link RocketMQResponse} representing an error result.
     */
    private RocketMQResponse buildErrorResponse(JSONRPCErrorResponse error, MessageView messageView) {
        return buildBaseResponse(messageView).end(true).stream(false).responseBody(toJsonString(JSON.toJSONString(error))).build();
    }

    /**
     * Constructs a success response for non-streaming JSON-RPC operations.
     *
     * @param nonStreamingResponse the successful JSON-RPC response object to serialize.
     * @param messageView the original message context used for tracking and routing.
     * @return a fully built {@link RocketMQResponse} representing a successful result.
     */
    private RocketMQResponse buildSuccessResponse(JSONRPCResponse<?> nonStreamingResponse, MessageView messageView) {
        return buildBaseResponse(messageView).end(true).stream(false).responseBody(toJsonString(nonStreamingResponse)).build();
    }

    /**
     * Creates a pre-populated builder with common fields from the message context.
     *
     * @param messageView the received message view containing metadata from the original request
     * @return a configured {@link RocketMQResponse.Builder} ready for additional settings
     */
    private RocketMQResponse.Builder buildBaseResponse(MessageView messageView) {
        Builder builder = RocketMQResponse.builder();
        builder.messageId(messageView.getMessageId().toString())
            .serverLiteTopic(serverLiteTopic)
            .serverWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
        return builder;
    }

    /**
     * Waits for the completion of a CompletableFuture with timeout,
     * and maps the result to a {@link ConsumeResult} for message consumption acknowledgment.
     *
     * @param completableFuture the future to wait on.
     */
    private ConsumeResult dealCompletableFuture(CompletableFuture<Boolean> completableFuture) {
        if (null == completableFuture) {
            return ConsumeResult.SUCCESS;
        }
        try {
            Boolean result = completableFuture.get(15, TimeUnit.MINUTES);
            return Boolean.TRUE.equals(result) ? ConsumeResult.SUCCESS : ConsumeResult.FAILURE;
        } catch (Exception e) {
            log.error("RocketMQA2AServerRoutes dealCompletableFuture error", e);
            return ConsumeResult.FAILURE;
        }
    }

    /**
     * Converts a {@link JsonProcessingException} into a corresponding JSON-RPC error response.
     * <p>
     * This method inspects the type of the exception to determine the appropriate JSON-RPC error code.
     *
     * @param exception the JSON parsing or mapping exception thrown during request deserialization.
     * @return a {@link JSONRPCErrorResponse} representing the error in JSON-RPC format.
     */
    private JSONRPCErrorResponse handleError(JsonProcessingException exception) {
        Object id = null;
        JSONRPCError jsonRpcError = null;
        if (exception.getCause() instanceof JsonParseException) {
            jsonRpcError = new JSONParseError();
        } else if (exception instanceof JsonEOFException) {
            // Incomplete JSON input
            jsonRpcError = new JSONParseError(exception.getMessage());
        } else if (exception instanceof MethodNotFoundJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new MethodNotFoundError();
        } else if (exception instanceof InvalidParamsJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidParamsError();
        } else if (exception instanceof IdJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidRequestError();
        } else {
            jsonRpcError = new InvalidRequestError();
        }
        return new JSONRPCErrorResponse(id, jsonRpcError);
    }

    /**
     * Processes a non-streaming JSON-RPC request by dispatching it to the appropriate handler method.
     * <p>
     * Supported request types include task management, message sending, and push notification configuration.
     * If the request type is not recognized, an {@link UnsupportedOperationError} is returned.
     *
     * @param request the incoming non-streaming JSON-RPC request.
     * @param context the server call context (may be {@code null}).
     * @return the JSON-RPC response corresponding to the request.
     */
    private JSONRPCResponse<?> processNonStreamingRequest(NonStreamingJSONRPCRequest<?> request,
        ServerCallContext context) {
        // Dispatch based on concrete request type
        if (request instanceof GetTaskRequest req) {
            return jsonRpcHandler.onGetTask(req, context);
        } else if (request instanceof CancelTaskRequest req) {
            return jsonRpcHandler.onCancelTask(req, context);
        } else if (request instanceof SetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.setPushNotificationConfig(req, context);
        } else if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.getPushNotificationConfig(req, context);
        } else if (request instanceof SendMessageRequest req) {
            return jsonRpcHandler.onMessageSend(req, context);
        } else if (request instanceof ListTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.listPushNotificationConfig(req, context);
        } else if (request instanceof DeleteTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.deletePushNotificationConfig(req, context);
        } else if (request instanceof GetAuthenticatedExtendedCardRequest req) {
            return jsonRpcHandler.onGetAuthenticatedExtendedCardRequest(req, context);
        } else {
            return generateErrorResponse(request, new UnsupportedOperationError());
        }
    }

    /**
     * Processes a streaming JSON-RPC request by delegating to the appropriate handler.
     * <p>
     * Currently supports:
     * <ul>
     *   <li>{@link SendStreamingMessageRequest} – for streaming message delivery</li>
     *   <li>{@link TaskResubscriptionRequest} – for resubscribing to task updates</li>
     * </ul>
     * Unsupported request types result in an immediate error response wrapped in a {@link Multi}.
     *
     * @param request the incoming streaming JSON-RPC request.
     * @param context the server call context (may be {@code null}).
     * @return a reactive stream ({@link Multi}) of JSON-RPC responses.
     */
    private Multi<? extends JSONRPCResponse<?>> processStreamingRequest(JSONRPCRequest<?> request, ServerCallContext context) {
        Flow.Publisher<? extends JSONRPCResponse<?>> publisher;
        if (request instanceof SendStreamingMessageRequest req) {
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
        } else if (request instanceof TaskResubscriptionRequest req) {
            publisher = jsonRpcHandler.onResubscribeToTask(req, context);
        } else {
            // Return a single-item stream containing an error response
            return Multi.createFrom().item(generateErrorResponse(request, new UnsupportedOperationError()));
        }
        return Multi.createFrom().publisher(publisher);
    }

    /**
     * Creates a JSON-RPC error response based on the original request and error details.
     *
     * @param request the original JSON-RPC request (used to extract the {@code id})
     * @param error   the specific JSON-RPC error to include in the response
     * @return a {@link JSONRPCErrorResponse} instance
     */
    private JSONRPCResponse<?> generateErrorResponse(JSONRPCRequest<?> request, JSONRPCError error) {
        return new JSONRPCErrorResponse(request.getId(), error);
    }

    /**
     * Handles Server-Sent Events (SSE) streaming over RocketMQ.
     * <p>
     * Converts reactive streams into SSE-formatted messages and publishes them to RocketMQ topics
     * for client consumption.
     */
    private static class MultiSseSupport {
        private final Producer producer;

        private MultiSseSupport(Producer producer) {
            this.producer = producer;
        }

        /**
         * Publishes an SSE data stream to RocketMQ.
         *
         * @param multi                  Asynchronous data stream
         * @param rc                     Routing context
         * @param workAgentResponseTopic A LiteTopic subscribed by the client receiving streaming responses
         * @param liteTopic              todo
         * @param msgId                  The msgId used by the client to map requests and response results
         * @param completableFuture      Used to asynchronously receive whether the SSE data transmission is completed
         */
        public void writeRocketmq(Multi<Buffer> multi, RoutingContext rc, String workAgentResponseTopic, String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            multi.subscribe().withSubscriber(new Flow.Subscriber<Buffer>() {
                Flow.Subscription upstream;

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.upstream = subscription;
                    // Request first item
                    this.upstream.request(1);
                }

                @Override
                public void onNext(Buffer item) {
                    try {
                        // Construct a RocketMQResponse object for the incremental data item from the upstream output
                        RocketMQResponse response = RocketMQResponse.builder().responseBody(item.toString()).messageId(msgId).stream(true).end(false).build();
                        SendReceipt send = producer.send(buildMessageForResponse(workAgentResponseTopic, liteTopic, response));
                        log.debug("MultiSseSupport send response success, msgId: [{}], time: [{}], response: [{}]", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    } catch (Exception e) {
                        log.error("MultiSseSupport send stream error, {}", e.getMessage());
                    }
                    // Request next item
                    this.upstream.request(1);
                }

                @Override
                public void onError(Throwable throwable) {
                    rc.fail(throwable);
                    completableFuture.complete(false);
                }

                @Override
                public void onComplete() {
                    // Construct a RocketMQResponse object to represent a completion status data object
                    RocketMQResponse response = RocketMQResponse.builder().messageId(msgId).stream(true).end(true).build();
                    try {
                        // Send the corresponding response result via RocketMQ Producer
                        SendReceipt send = producer.send(buildMessageForResponse(workAgentResponseTopic, liteTopic, response));
                        log.debug("MultiSseSupport send response success, msgId: {}, time: {}, response: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    } catch (ClientException e) {
                        log.error("MultiSseSupport error send complete, msgId: {}", e.getMessage());
                    }
                    completableFuture.complete(true);
                }
            });
        }

        /**
         * Subscribes to a stream of objects, formats them as SSE messages, and sends via RocketMQ.
         *
         * @param multi the stream of response objects.
         * @param rc routing context.
         * @param workAgentResponseTopic the topic where the client listens for responses.
         * @param liteTopic the client's session-specific lite topic (used for correlation).
         * @param msgId the original message ID for request-response correlation.
         * @param completableFuture completes with true on success, false on error.
         */
        public void subscribeObjectRocketMQ(Multi<Object> multi, RoutingContext rc, String workAgentResponseTopic,
            String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            AtomicLong count = new AtomicLong();

            // Transform Multi<Object> to  Multi<Buffer>
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
            // Subscribe to SSE object data and send the data via RocketMQ
            writeRocketmq(map, rc, workAgentResponseTopic, liteTopic, msgId, completableFuture);
        }
    }

    /**
     * Validates required configuration parameters for initializing RocketMQA2AServerRoutes.
     * <p>All parameters are mandatory. If any is {@code null} or empty, an {@link IllegalArgumentException}
     * is thrown with detailed information about which field(s) failed validation.
     */
    private void checkConfigParam() {
        Map<String, String> configParams = new LinkedHashMap<>();
        configParams.put("rocketMQEndpoint", ROCKETMQ_ENDPOINT);
        configParams.put("bizTopic", BIZ_TOPIC);
        configParams.put("bizConsumerGroup", BIZ_CONSUMER_GROUP);
        configParams.put("workAgentResponseTopic", WORK_AGENT_RESPONSE_TOPIC);
        configParams.put("workAgentResponseGroupID", WORK_AGENT_RESPONSE_GROUP_ID);
        List<String> missingParams = new ArrayList<>();
        for (Map.Entry<String, String> entry : configParams.entrySet()) {
            if (StringUtils.isEmpty(entry.getValue())) {
                String paramName = entry.getKey();
                log.error("RocketMQA2AServerRoutes checkConfigParam [{}] is empty", paramName);
                missingParams.add(paramName);
            }
        }
        if (!missingParams.isEmpty()) {
            throw new IllegalArgumentException(
                "RocketMQA2AServerRoutes init failed, missing required params: " + missingParams
            );
        }
    }
}
