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
import java.time.Duration;
import java.util.Collections;
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
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.a2a.util.Utils.OBJECT_MAPPER;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.METHOD;

@Startup
@Singleton
public class RocketMQA2AServerRoutes extends A2AServerRoutes {
    private static final Logger log = LoggerFactory.getLogger(RocketMQA2AServerRoutes.class);
    private static final String ROCKETMQ_ENDPOINT = System.getProperty("rocketMQEndpoint", "");
    private static final String ROCKETMQ_INSTANCE_ID = System.getProperty("rocketMQInstanceID", "");
    private static final String BIZ_TOPIC = System.getProperty("bizTopic", "");
    private static final String BIZ_CONSUMER_GROUP = System.getProperty("bizConsumerGroup", "");
    private static final String ACCESS_KEY = System.getProperty("rocketMQAk", "");
    private static final String SECRET_KEY = System.getProperty("rocketMQSk", "");

    @Inject
    JSONRPCHandler jsonRpcHandler;

    private static volatile Runnable streamingMultiSseSupportSubscribedRunnable;

    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
        6,
        6,
        60, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(10_0000),
        new CallerRunsPolicy()
    );

    private Producer producer;
    private PushConsumer pushConsumer;
    private MultiSseSupport multiSseSupport;

    @PostConstruct
    public void init() {
        try {
            checkConfigParam();
            this.producer = buildProducer();
            this.pushConsumer = buildConsumer();
            this.multiSseSupport = new MultiSseSupport(this.producer);
            log.info("RocketMQA2AServerRoutes init success");
        } catch (Exception e) {
            log.error("RocketMQA2AServerRoutes error: {}", e.getMessage());
        }
    }
    private Producer buildProducer() throws ClientException {
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(ACCESS_KEY, SECRET_KEY);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(ROCKETMQ_ENDPOINT)
            .setNamespace(ROCKETMQ_INSTANCE_ID)
            .setCredentialProvider(sessionCredentialsProvider)
            .setRequestTimeout(Duration.ofSeconds(15))
            .build();
        final ProducerBuilder builder = provider.newProducerBuilder().setClientConfiguration(clientConfiguration);
        return builder.build();
    }

    private PushConsumer buildConsumer() throws ClientException {
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(ACCESS_KEY, SECRET_KEY);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(ROCKETMQ_ENDPOINT)
            .setNamespace(ROCKETMQ_INSTANCE_ID)
            .setCredentialProvider(sessionCredentialsProvider)
            .build();
        String tag = "*";
        FilterExpression filterExpression = new FilterExpression(tag, FilterExpressionType.TAG);
        PushConsumer consumer = provider.newPushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(BIZ_CONSUMER_GROUP)
            .setSubscriptionExpressions(Collections.singletonMap(BIZ_TOPIC, filterExpression))
            .setMessageListener(messageView -> {
                CompletableFuture<Boolean> completableFuture = null;
                try {
                    byte[] result = new byte[messageView.getBody().remaining()];
                    messageView.getBody().get(result);
                    String messageStr = new String(result, StandardCharsets.UTF_8);
                    RocketMQRequest request = JSON.parseObject(messageStr, RocketMQRequest.class);
                    boolean streaming = false;
                    String body = request.getRequestBody();
                    JSONRPCResponse<?> nonStreamingResponse = null;
                    Multi<? extends JSONRPCResponse<?>> streamingResponse = null;
                    JSONRPCErrorResponse error = null;
                    try {
                        JsonNode node = OBJECT_MAPPER.readTree(body);
                        JsonNode method = node != null ? node.get(METHOD) : null;
                        streaming = method != null && (SendStreamingMessageRequest.METHOD.equals(method.asText()) || TaskResubscriptionRequest.METHOD.equals(method.asText()));
                        if (streaming) {
                            StreamingJSONRPCRequest<?> streamingJSONRPCRequest = OBJECT_MAPPER.treeToValue(node, StreamingJSONRPCRequest.class);
                            streamingResponse = processStreamingRequest(streamingJSONRPCRequest, null);
                        } else {
                            NonStreamingJSONRPCRequest<?> nonStreamingJSONRPCRequest = OBJECT_MAPPER.treeToValue(node, NonStreamingJSONRPCRequest.class);
                            nonStreamingResponse = processNonStreamingRequest(nonStreamingJSONRPCRequest, null);
                        }
                    } catch (JsonProcessingException e) {
                        error = handleError(e);
                    } catch (Throwable t) {
                        error = new JSONRPCErrorResponse(new InternalError(t.getMessage()));
                    } finally {
                        RocketMQResponse response = null;
                        if (error != null) {
                            response = new RocketMQResponse();
                            response.setEnd(true);
                            response.setStream(false);
                            response.setLiteTopic(request.getLiteTopic());
                            response.setContextId(response.getContextId());
                            response.setResponseBody(JSON.toJSONString(error));
                            response.setMessageId(messageView.getMessageId().toString());
                        } else if (streaming) {
                            final Multi<? extends JSONRPCResponse<?>> finalStreamingResponse = streamingResponse;
                            log.info("RocketMQA2AServerRoutes streaming finalStreamingResponse: {}", JSON.toJSONString(finalStreamingResponse));
                            completableFuture = new CompletableFuture<>();
                            CompletableFuture<Boolean> finalCompletableFuture = completableFuture;
                            this.executor.execute(() -> {
                                this.multiSseSupport.subscribeObjectRocketmq(finalStreamingResponse.map(i -> (Object)i), null, request.getWorkAgentResponseTopic(), request.getLiteTopic(), messageView.getMessageId().toString(), finalCompletableFuture);
                            });
                        } else {
                            response = new RocketMQResponse();
                            response.setEnd(true);
                            response.setStream(false);
                            response.setLiteTopic(request.getLiteTopic());
                            response.setContextId(response.getContextId());
                            response.setMessageId(messageView.getMessageId().toString());
                            response.setResponseBody(toJsonString(nonStreamingResponse));
                        }
                        if (null != response) {
                            SendReceipt send = this.producer.send(buildMessage(request.getWorkAgentResponseTopic(), request.getLiteTopic(), response));
                            log.info("RocketMQA2AServerRoutes send nonStreamingResponse success, msgId: {}, time: {}, response: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                        }
                    }
                } catch (Exception e) {
                    log.error("RocketMQA2AServerRoutes error: {}", e.getMessage());
                    return ConsumeResult.FAILURE;
                }
                if (null != completableFuture) {
                    try {
                        Boolean streamResult = completableFuture.get(15, TimeUnit.MINUTES);
                        if (null != streamResult && streamResult) {
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
            }).build();
        return consumer;
    }

    private static Message buildMessage(String topic, String liteTopic, RocketMQResponse response) {
        if (StringUtils.isEmpty(topic) || StringUtils.isEmpty(liteTopic)) {
            log.info("RocketMQA2AServerRoutes buildMessage param error, topic: {}, liteTopic: {}, response: {}", topic, liteTopic, JSON.toJSONString(response));
            return null;
        }
        String missionJsonStr = JSON.toJSONString(response);
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        final Message message = provider.newMessageBuilder()
            .setTopic(topic)
            .setBody(missionJsonStr.getBytes(StandardCharsets.UTF_8))
            .setLiteTopic(liteTopic)
            .build();
        return message;
    }

    private JSONRPCErrorResponse handleError(JsonProcessingException exception) {
        Object id = null;
        JSONRPCError jsonRpcError = null;
        if (exception.getCause() instanceof JsonParseException) {
            jsonRpcError = new JSONParseError();
        } else if (exception instanceof JsonEOFException) {
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

    private JSONRPCResponse<?> processNonStreamingRequest(
        NonStreamingJSONRPCRequest<?> request, ServerCallContext context) {
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

    private Multi<? extends JSONRPCResponse<?>> processStreamingRequest(
        JSONRPCRequest<?> request, ServerCallContext context) {
        Flow.Publisher<? extends JSONRPCResponse<?>> publisher;
        if (request instanceof SendStreamingMessageRequest req) {
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
        } else if (request instanceof TaskResubscriptionRequest req) {
            publisher = jsonRpcHandler.onResubscribeToTask(req, context);
        } else {
            return Multi.createFrom().item(generateErrorResponse(request, new UnsupportedOperationError()));
        }
        return Multi.createFrom().publisher(publisher);
    }

    private JSONRPCResponse<?> generateErrorResponse(JSONRPCRequest<?> request, JSONRPCError error) {
        return new JSONRPCErrorResponse(request.getId(), error);
    }

    static void setStreamingMultiSseSupportSubscribedRunnable(Runnable runnable) {
        streamingMultiSseSupportSubscribedRunnable = runnable;
    }

    private static class MultiSseSupport {
        private final Producer producer;

        private MultiSseSupport(Producer producer) {
            this.producer = producer;
        }
        public void writeRocketmq(Multi<Buffer> multi, RoutingContext rc, String workAgentResponseTopic, String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            multi.subscribe().withSubscriber(new Flow.Subscriber<Buffer>() {
                Flow.Subscription upstream;
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.upstream = subscription;
                    this.upstream.request(1);
                    Runnable runnable = streamingMultiSseSupportSubscribedRunnable;
                    if (runnable != null) {
                        runnable.run();
                    }
                }

                @Override
                public void onNext(Buffer item) {
                    try {
                        RocketMQResponse response = new RocketMQResponse();
                        response.setEnd(false);
                        response.setStream(true);
                        response.setLiteTopic(liteTopic);
                        response.setContextId(response.getContextId());
                        response.setMessageId(msgId);
                        response.setResponseBody(item.toString());
                        SendReceipt send = producer.send(buildMessage(workAgentResponseTopic, liteTopic, response));
                        log.info("MultiSseSupport send response success, msgId: {}, time: {}, response: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    } catch (Exception e) {
                        log.error("MultiSseSupport send stream error, {}", e.getMessage());
                    }
                    this.upstream.request(1);
                }

                @Override
                public void onError(Throwable throwable) {
                    rc.fail(throwable);
                    completableFuture.complete(false);
                }

                @Override
                public void onComplete() {
                    try {
                        RocketMQResponse response = new RocketMQResponse();
                        response.setEnd(true);
                        response.setStream(true);
                        response.setLiteTopic(liteTopic);
                        response.setContextId(response.getContextId());
                        response.setMessageId(msgId);
                        SendReceipt send = producer.send(buildMessage(workAgentResponseTopic, liteTopic, response));
                        log.info("MultiSseSupport send response success, msgId: {}, time: {}, response: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    } catch (ClientException e) {
                        log.info("MultiSseSupport error send complete, msgId: {}", e.getMessage());
                    }
                    completableFuture.complete(true);
                }
            });
        }

        public void subscribeObjectRocketmq(Multi<Object> multi, RoutingContext rc, String workAgentResponseTopic, String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            AtomicLong count = new AtomicLong();
            Multi<Buffer> map = multi.map(new Function<Object, Buffer>() {
                @Override
                public Buffer apply(Object o) {
                    if (o instanceof ServerSentEvent) {
                        ServerSentEvent<?> ev = (ServerSentEvent<?>)o;
                        long id = ev.id() != -1 ? ev.id() : count.getAndIncrement();
                        String e = ev.event() == null ? "" : "event: " + ev.event() + "\n";
                        return Buffer.buffer(e + "data: " + toJsonString(ev.data()) + "\nid: " + id + "\n\n");
                    }
                    return Buffer.buffer("data: " + toJsonString(o) + "\nid: " + count.getAndIncrement() + "\n\n");
                }
            });
            writeRocketmq(map, rc, workAgentResponseTopic, liteTopic, msgId, completableFuture);
        }
    }

    private static String toJsonString(Object o) {
        try {
            return OBJECT_MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }

    private void checkConfigParam() {
        if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT) || StringUtils.isEmpty(ROCKETMQ_INSTANCE_ID) || StringUtils.isEmpty(BIZ_TOPIC) ||
            StringUtils.isEmpty(BIZ_CONSUMER_GROUP) || StringUtils.isEmpty(ACCESS_KEY) || StringUtils.isEmpty(SECRET_KEY)) {
            if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT)) {
                log.info("rocketMQEndpoint is empty");
            }
            if (StringUtils.isEmpty(ROCKETMQ_INSTANCE_ID)) {
                log.info("rocketMQInstanceID is empty");
            }
            if (StringUtils.isEmpty(BIZ_TOPIC)) {
                log.info("bizTopic is empty");
            }
            if (StringUtils.isEmpty(BIZ_CONSUMER_GROUP)) {
                log.info("bizConsumerGroup is empty");
            }
            if (StringUtils.isEmpty(ACCESS_KEY)) {
                log.info("rocketMQAK is empty");
            }
            if (StringUtils.isEmpty(SECRET_KEY)) {
                log.info("rocketMQSK is empty");
            }
            throw new RuntimeException("RocketMQA2AServerRoutes check init rocketmq param error, init failed!!!");
        }
    }
}
