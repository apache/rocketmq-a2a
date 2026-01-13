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
 * 基于RocketMQ实现的符合A2A协议标准的服务路由转发器
 * 主要用于解析接收到的RocketMQ消息并将请求转发到对应的处理器
 */
@Startup
@Singleton
public class RocketMQA2AServerRoutes extends A2AServerRoutes {
    private static final Logger log = LoggerFactory.getLogger(RocketMQA2AServerRoutes.class);
    //RocketMQ的接入点
    private static final String ROCKETMQ_ENDPOINT = System.getProperty("rocketMQEndpoint", "");
    //RocketMQ的命名空间
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace", "");
    //Agent智能体所绑定的RocketMQ 业务普通的TOPIC，用于接收任务请求等信息
    private static final String BIZ_TOPIC = System.getProperty("bizTopic", "");
    //用于订阅Agent智能体所绑定的业务普通的TOPIC
    private static final String BIZ_CONSUMER_GROUP = System.getProperty("bizConsumerGroup", "");
    //RocketMQ账号信息
    private static final String ACCESS_KEY = System.getProperty("rocketMQAK", "");
    //RocketMQ密码信息
    private static final String SECRET_KEY = System.getProperty("rocketMQSK", "");
    //RocketMQ服务端的轻量级LiteTopic，用于接收客户端发送到服务端点对点需求的消息
    private static final String WORK_AGENT_RESPONSE_TOPIC = System.getProperty("workAgentResponseTopic","");
    //订阅RocketMQ服务端轻量级LiteTopic的CID
    private static final String WORK_AGENT_RESPONSE_GROUP_ID = System.getProperty("workAgentResponseGroupID","");

    private static volatile Runnable streamingMultiSseSupportSubscribedRunnable;
    //任务线程池
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(6, 6, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10_0000), new CallerRunsPolicy());
    //RocketMQ Producer 用于发送响应结果
    private Producer producer;
    //RocketMQ PushConsumer 用于接收Agent请求的普通PushConsumer
    private PushConsumer pushConsumer;
    //RocketMQ LitePushConsumer 主要用于处理客户端与服务端点对点交互时接收请求时的消费者
    private LitePushConsumer litePushConsumer;
    //RocketMQ 服务端轻量级辅助LiteTopic
    private String serverLiteTopic;
    //用于发送SSE数据流的支持类
    private MultiSseSupport multiSseSupport;

    /**
     * JSON RPC请求处理器
     */
    @Inject
    JSONRPCHandler jsonRpcHandler;

    /**
     * 服务端路由转发器构造函数执行之后的初始化
     */
    @PostConstruct
    public void init() {
        try {
            //检查参数
            checkConfigParam();
            //初始化producer
            this.producer = buildProducer(ROCKETMQ_NAMESPACE, ROCKETMQ_ENDPOINT, ACCESS_KEY, SECRET_KEY);
            //初始化普通pushConsumer
            this.pushConsumer = buildConsumer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, BIZ_CONSUMER_GROUP, BIZ_TOPIC, buildMessageListener());
            //初始化SSE事件处理器类
            this.multiSseSupport = new MultiSseSupport(this.producer);
            //初始化LitePushConsumer
            this.litePushConsumer = buildConsumerForLite(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, WORK_AGENT_RESPONSE_GROUP_ID, WORK_AGENT_RESPONSE_TOPIC, buildMessageListener());
            //生产一个SessionId作为服务端的轻量级LiteTopic
            this.serverLiteTopic = UUID.randomUUID().toString();
            //服务端LitePushConsumer进行订阅这个服务端轻量级LiteTopic
            this.litePushConsumer.subscribeLite(serverLiteTopic);
            log.info("RocketMQA2AServerRoutes init success, server liteTopic: {}", serverLiteTopic);
        } catch (Exception e) {
            log.error("RocketMQA2AServerRoutes error: {}", e.getMessage());
        }
    }

    /**
     * 构建RocketMQ消息监听器
     * @return
     */
    private MessageListener buildMessageListener() {
        return messageView -> {
            CompletableFuture<Boolean> completableFuture = null;
            try {
                //从消息体中获取对应的内容
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                String messageStr = new String(result, StandardCharsets.UTF_8);
                //反序列化为RocketMQRequest对象
                RocketMQRequest request = JSON.parseObject(messageStr, RocketMQRequest.class);
                boolean streaming = false;
                //获取A2A协议相关的请求Body
                String body = request.getRequestBody();
                JSONRPCResponse<?> nonStreamingResponse = null;
                Multi<? extends JSONRPCResponse<?>> streamingResponse = null;
                JSONRPCErrorResponse error = null;
                try {
                    //从A2A协议body中反序化为JsonNode
                    JsonNode node = OBJECT_MAPPER.readTree(body);
                    //获取方法节点字段数据
                    JsonNode method = node != null ? node.get(METHOD) : null;
                    //判断这个方法类型是否为流式
                    streaming = method != null && (SendStreamingMessageRequest.METHOD.equals(method.asText()) || TaskResubscriptionRequest.METHOD.equals(method.asText()));
                    if (streaming) {
                        //处理流式请求
                        StreamingJSONRPCRequest<?> streamingJSONRPCRequest = OBJECT_MAPPER.treeToValue(node, StreamingJSONRPCRequest.class);
                        //得到流式响应结果处理器
                        streamingResponse = processStreamingRequest(streamingJSONRPCRequest, null);
                    } else {
                        //处理非流式请求
                        NonStreamingJSONRPCRequest<?> nonStreamingJSONRPCRequest = OBJECT_MAPPER.treeToValue(node, NonStreamingJSONRPCRequest.class);
                        //得到非流式响应结果
                        nonStreamingResponse = processNonStreamingRequest(nonStreamingJSONRPCRequest, null);
                    }
                } catch (JsonProcessingException e) {
                    //对Json处理异常进行处理
                    error = handleError(e);
                } catch (Throwable t) {
                    error = new JSONRPCErrorResponse(new InternalError(t.getMessage()));
                } finally {
                    //构造RocketMQ响应结果对象
                    RocketMQResponse response = null;
                    //如果有错误发生
                    if (error != null) {
                        //构造RocketMQResponse对象，并将error相关的信息进行写入
                        response = new RocketMQResponse();
                        response.setEnd(true);
                        response.setStream(false);
                        //设置服务端轻量级LiteTopic
                        response.setServerLiteTopic(serverLiteTopic);
                        response.setServerWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
                        response.setLiteTopic(request.getLiteTopic());
                        //设置错误内容
                        response.setResponseBody(JSON.toJSONString(error));
                        //设置客户端发送的MessageId用于客户端结果匹配
                        response.setMessageId(messageView.getMessageId().toString());
                    //处理流式请求
                    } else if (streaming) {
                        final Multi<? extends JSONRPCResponse<?>> finalStreamingResponse = streamingResponse;
                        log.info("RocketMQA2AServerRoutes streaming finalStreamingResponse: {}", JSON.toJSONString(finalStreamingResponse));
                        completableFuture = new CompletableFuture<>();
                        CompletableFuture<Boolean> finalCompletableFuture = completableFuture;
                        //线程池提交SSE数据发送任务，并传入CompletableFuture 用于后续接收任务完成通知，并提交对应的任务处理结果
                        this.executor.execute(() -> {
                            this.multiSseSupport.subscribeObjectRocketMQ(finalStreamingResponse.map(i -> (Object)i), null, request.getWorkAgentResponseTopic(), request.getLiteTopic(), messageView.getMessageId().toString(), finalCompletableFuture);
                        });
                    //处理非流式请求
                    } else {
                        //构造RocketMQResponse对象用于传输 非流式响应结果对象数据
                        response = new RocketMQResponse();
                        //设置响应结果
                        response.setEnd(true);
                        //设置响应类型
                        response.setStream(false);
                        //设置服务端轻量级LiteTopic
                        response.setServerLiteTopic(serverLiteTopic);
                        response.setServerWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
                        response.setLiteTopic(request.getLiteTopic());
                        response.setMessageId(messageView.getMessageId().toString());
                        //设置非流式响应结果对象数据
                        response.setResponseBody(toJsonString(nonStreamingResponse));
                    }
                    if (null != response) {
                        //通过调用Producer发送响应结果到客户端
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
                    //通过获取CompletableFuture的完成状态
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
     * 处理Json相关异常
     * @param exception json处理异常
     * @return JSONRPCErrorResponse JSON RPC 错误响应
     */
    private JSONRPCErrorResponse handleError(JsonProcessingException exception) {
        Object id = null;
        JSONRPCError jsonRpcError = null;
        //判读异常的原因是否是 Json解析异常
        if (exception.getCause() instanceof JsonParseException) {
            jsonRpcError = new JSONParseError();
        //判断异常是否为 JsonEOFException
        } else if (exception instanceof JsonEOFException) {
            jsonRpcError = new JSONParseError(exception.getMessage());
        //判断异常是否为 MethodNotFoundJsonMappingException
        } else if (exception instanceof MethodNotFoundJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new MethodNotFoundError();
        //判断异常是否为 InvalidParamsJsonMappingException
        } else if (exception instanceof InvalidParamsJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidParamsError();
        //判断异常是否为 IdJsonMappingException
        } else if (exception instanceof IdJsonMappingException err) {
            id = err.getId();
            jsonRpcError = new InvalidRequestError();
        } else {
            jsonRpcError = new InvalidRequestError();
        }
        //返回JSON RPC错误响应结果
        return new JSONRPCErrorResponse(id, jsonRpcError);
    }

    /**
     * 处理非流式请求
     * @param request 非流式JSON RPC请求
     * @param context 服务端调用上下文
     * @return
     */
    private JSONRPCResponse<?> processNonStreamingRequest(NonStreamingJSONRPCRequest<?> request,
        ServerCallContext context) {
        //判断请求是否为GetTaskRequest
        if (request instanceof GetTaskRequest req) {
            return jsonRpcHandler.onGetTask(req, context);
        //判断请求是否为CancelTaskRequest
        } else if (request instanceof CancelTaskRequest req) {
            return jsonRpcHandler.onCancelTask(req, context);
        //判断请求是否为SetTaskPushNotificationConfigRequest
        } else if (request instanceof SetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.setPushNotificationConfig(req, context);
        //判断请求是否为GetTaskPushNotificationConfigRequest
        } else if (request instanceof GetTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.getPushNotificationConfig(req, context);
        //判断请求是否为SendMessageRequest
        } else if (request instanceof SendMessageRequest req) {
            return jsonRpcHandler.onMessageSend(req, context);
        //判断请求是否为ListTaskPushNotificationConfigRequest
        } else if (request instanceof ListTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.listPushNotificationConfig(req, context);
        //判断请求是否为DeleteTaskPushNotificationConfigRequest
        } else if (request instanceof DeleteTaskPushNotificationConfigRequest req) {
            return jsonRpcHandler.deletePushNotificationConfig(req, context);
        //判断请求是否为GetAuthenticatedExtendedCardRequest
        } else if (request instanceof GetAuthenticatedExtendedCardRequest req) {
            return jsonRpcHandler.onGetAuthenticatedExtendedCardRequest(req, context);
        } else {
            //返回错误响应结果
            return generateErrorResponse(request, new UnsupportedOperationError());
        }
    }

    /**
     * 处理流式请求
     * @param request 流式请求JSONRPCRequest
     * @param context 服务端调用上下文
     * @return
     */
    private Multi<? extends JSONRPCResponse<?>> processStreamingRequest(JSONRPCRequest<?> request,
        ServerCallContext context) {
        Flow.Publisher<? extends JSONRPCResponse<?>> publisher;
        //如果请求为 发送流式消息请求
        if (request instanceof SendStreamingMessageRequest req) {
            publisher = jsonRpcHandler.onMessageSendStream(req, context);
        //如果请求为任务重新订阅请求
        } else if (request instanceof TaskResubscriptionRequest req) {
            publisher = jsonRpcHandler.onResubscribeToTask(req, context);
        //返回请求不支持的响应结果
        } else {
            return Multi.createFrom().item(generateErrorResponse(request, new UnsupportedOperationError()));
        }
        return Multi.createFrom().publisher(publisher);
    }

    /**
     * 构建错误响应结果
     * @param request JSON RPC请求
     * @param error JSON RPC Error对象
     * @return JSON RPC Response对象
     */
    private JSONRPCResponse<?> generateErrorResponse(JSONRPCRequest<?> request, JSONRPCError error) {
        return new JSONRPCErrorResponse(request.getId(), error);
    }

    static void setStreamingMultiSseSupportSubscribedRunnable(Runnable runnable) {
        streamingMultiSseSupportSubscribedRunnable = runnable;
    }

    /**
     * 处理SSE数据发送的工具类
     * 内部使用RocketMQ Producer进行流式内容的发送
     */
    private static class MultiSseSupport {
        private final Producer producer;

        private MultiSseSupport(Producer producer) {
            this.producer = producer;
        }

        /**
         * 将SSE数据写入RocketMQ
         * @param multi Multi Buffer数据
         * @param rc 路由上下文
         * @param workAgentResponseTopic 接收流式响应结果的客户端 所订阅的轻量级LiteTopic
         * @param liteTopic 接收流式响应结果的客户端 所订阅的轻量级 辅助 LiteTopic
         * @param msgId 客户端用于映射请求/响应结果的msgId
         * @param completableFuture 用于异步接收SSE数据是否完成的对象
         */
        public void writeRocketmq(Multi<Buffer> multi, RoutingContext rc, String workAgentResponseTopic,
            String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            multi.subscribe().withSubscriber(new Flow.Subscriber<Buffer>() {
                Flow.Subscription upstream;

                /**
                 * 对上游数据输出任务对象进行订阅
                 * @param subscription a new subscription
                 */
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.upstream = subscription;
                    //请求上游数据
                    this.upstream.request(1);
                    Runnable runnable = streamingMultiSseSupportSubscribedRunnable;
                    if (runnable != null) {
                        runnable.run();
                    }
                }

                /**
                 * 针对上游输出的增量数据进行处理
                 * @param item the item
                 */
                @Override
                public void onNext(Buffer item) {
                    try {
                        //针对上游输出的增量数据item 构造RocketMQResponse 对象
                        RocketMQResponse response = new RocketMQResponse(liteTopic, null, item.toString(), msgId, true, false);
                        //将构造RocketMQResponse对象构建为RocketMQ消息
                        SendReceipt send = producer.send(buildMessage(workAgentResponseTopic, liteTopic, response));
                        log.info("MultiSseSupport send response success, msgId: {}, time: {}", send.getMessageId(), System.currentTimeMillis(), JSON.toJSONString(response));
                    } catch (Exception e) {
                        log.error("MultiSseSupport send stream error, {}", e.getMessage());
                    }
                    //向上游发起请求
                    this.upstream.request(1);
                }

                /**
                 * 上游出现错误时调用
                 * @param throwable the exception
                 */
                @Override
                public void onError(Throwable throwable) {
                    rc.fail(throwable);
                    completableFuture.complete(false);
                }

                /**
                 * 上游流式输出任务完成时调用
                 */
                @Override
                public void onComplete() {
                    //构建RocketMQResponse对象 用于构建完成状态的数据对象
                    RocketMQResponse response = new RocketMQResponse(liteTopic, null, null, msgId, true, true);
                    try {
                        //RocketMQ Producer 发送对应的响应结果
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
         * 针对上游流式输出任务进行订阅，并通过RocketMQ发送对应数据
         * @param multi 上游流式数据输出对象
         * @param rc 路由上下文
         * @param workAgentResponseTopic 客户端接收响应结果的轻量级LiteTopic
         * @param liteTopic 客户端接收响应结果的轻量级辅助LiteTopic
         * @param msgId 客户端用于匹配请求和响应的结果的msgId
         * @param completableFuture 用于传输任务完成状态
         */
        public void subscribeObjectRocketMQ(Multi<Object> multi, RoutingContext rc, String workAgentResponseTopic,
            String liteTopic, String msgId, CompletableFuture<Boolean> completableFuture) {
            AtomicLong count = new AtomicLong();

            //针对Multi<Object> multi 进行处理为Multi<Buffer>
            Multi<Buffer> map = multi.map(new Function<Object, Buffer>() {
                @Override
                public Buffer apply(Object o) {
                    //如果对象为 SSE对象，则对数据进行处理，让其符合SSE数据传输规范
                    if (o instanceof ServerSentEvent) {
                        ServerSentEvent<?> ev = (ServerSentEvent<?>)o;
                        long id = ev.id() != -1 ? ev.id() : count.getAndIncrement();
                        String e = ev.event() == null ? "" : "event: " + ev.event() + "\n";
                        return Buffer.buffer(e + "data: " + toJsonString(ev.data()) + "\nid: " + id + "\n\n");
                    }
                    return Buffer.buffer("data: " + toJsonString(o) + "\nid: " + count.getAndIncrement() + "\n\n");
                }
            });
            //订阅SSE对象数据，并将数据通过RocketMQ发送
            writeRocketmq(map, rc, workAgentResponseTopic, liteTopic, msgId, completableFuture);
        }
    }

    /**
     * 检查配置参数
     */
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
