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
package org.apache.rocketmq.a2a.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.a2a.client.transport.jsonrpc.sse.SSEEventListener;
import io.a2a.client.transport.spi.interceptors.PayloadAndHeaders;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.CancelTaskResponse;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.util.Utils;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.a2a.util.Utils.OBJECT_MAPPER;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.CANCEL_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.DATA_PREFIX;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.GET_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.SEND_MESSAGE_RESPONSE_REFERENCE;

/**
 * RocketMQ utility class for logical grouping, used to centrally manage RocketMQ client resources (such as Producers and Consumers).
 * All resources are isolated by namespace, supporting multi-tenancy or multi-environment deployments.
 */
public class RocketMQUtil {
    private static final Logger log = LoggerFactory.getLogger(RocketMQUtil.class);
    public static final ConcurrentMap<String /* namespace */, Map<String /* WorkerAgentResponseTopic */, LitePushConsumer>> ROCKETMQ_CONSUMER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* agentTopic */, Producer>> ROCKETMQ_PRODUCER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, AsyncTypedResult>> MESSAGE_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, SSEEventListener>> MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* liteTopic */, Boolean>> LITE_TOPIC_USE_DEFAULT_RECOVER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* Key */, SSEEventListener>> RECOVER_MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* taskId */, ServerReceiptInfo /* ServerInfo */> TASK_SERVER_RECEIPT_MAP = new ConcurrentHashMap<>();

    /**
     * 用于检查RocketMQTransport的初始化参数
     * @param endpoint rocketmq endpoint
     * @param workAgentResponseTopic 客户端接收响应结果的liteTopic
     * @param workAgentResponseGroupID 客户端订阅结果响应Topic的CID
     * @param liteTopic 临时的轻量级Topic(类似于SessionId)
     * @param agentTopic 对应Agent的Topic
     */
    public static void checkConfigParam(String endpoint, String workAgentResponseTopic, String workAgentResponseGroupID, String liteTopic, String agentTopic) {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseTopic) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(liteTopic) || StringUtils.isEmpty(agentTopic)) {
            if (StringUtils.isEmpty(endpoint)) {
                log.error("checkRocketMQConfigParam endpoint is empty");
            }
            if (StringUtils.isEmpty(workAgentResponseTopic)) {
                log.error("checkRocketMQConfigParam workAgentResponseTopic is empty");
            }
            if (StringUtils.isEmpty(workAgentResponseGroupID)) {
                log.error("checkRocketMQConfigParam workAgentResponseGroupID is empty");
            }
            if (StringUtils.isEmpty(liteTopic)) {
                log.error("checkRocketMQConfigParam liteTopic is empty");
            }
            if (StringUtils.isEmpty(agentTopic)) {
                log.error("checkRocketMQConfigParam agentTopic is empty");
            }
            throw new RuntimeException("checkRocketMQConfigParam error, init failed !!!");
        }
    }

    /**
     * 初始化并获取对应的Producer
     * @param namespace RocketMQ命名空间
     * @param endpoint RocketMQ接入点
     * @param accessKey RocketMQ账户名称
     * @param secretKey RocketMQ账户密码
     * @param agentTopic 对应智能体应用的Topic
     * @return Producer 已初始化并缓存的 Producer 实例
     * @throws ClientException rocketmq 客户端异常
     */
    public static Producer initAndGetProducer(String namespace, String endpoint, String accessKey, String secretKey, String agentTopic) throws ClientException {
        if (null == namespace || StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(agentTopic)) {
            log.error("initAndGetProducer param error, namespace: {}, endpoint: {}, agentTopic: {}", namespace, endpoint, agentTopic);
        }
        Map<String, Producer> producerMap = ROCKETMQ_PRODUCER_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        return producerMap.computeIfAbsent(agentTopic, k -> {
            try {
                return buildProducer(namespace, endpoint, accessKey, secretKey, k);
            } catch (ClientException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * 构建Producer
     * @param namespace RocketMQ命名空间
     * @param endpoint RocketMQ接入点
     * @param accessKey RocketMQ账户名称
     * @param secretKey RocketMQ账户密码
     * @param topics 生产者发送到的目的端Topic
     * @return 消息生产者
     * @throws ClientException rocketmq 客户端异常
     */
    public static Producer buildProducer(String namespace, String endpoint, String accessKey, String secretKey, String... topics) throws ClientException {
        if (null == namespace || StringUtils.isEmpty(endpoint)) {
            log.error("buildProducer param error, endpoint: {}", endpoint);
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(endpoint)
            .setNamespace(namespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .setRequestTimeout(Duration.ofSeconds(15))
            .build();
        final ProducerBuilder builder = provider.newProducerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setTopics(topics);
        return builder.build();
    }

    /**
     * 初始化以及获取Consumer对象
     * @param namespace RocketMQ命名空间
     * @param endpoint RocketMQ接入点
     * @param accessKey RocketMQ账户名称
     * @param secretKey RocketMQ账户密码
     * @param workAgentResponseTopic 客户端接收响应的轻量Topic
     * @param workAgentResponseGroupID 客户端订阅响应的轻量Topic的消费者
     * @param liteTopic 轻量级Topic
     * @return LitePushConsumer 用于拉取响应Topic对应的消息
     * @throws ClientException
     */
    public static LitePushConsumer initAndGetConsumer(String namespace, String endpoint, String accessKey, String secretKey, String workAgentResponseTopic, String workAgentResponseGroupID, String liteTopic) throws ClientException {
        if (null == namespace || StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseTopic) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(liteTopic)) {
            log.error("initAndGetConsumer param error, namespace: {}, endpoint: {}, workAgentResponseTopic: {}, " + "workAgentResponseGroupID: {}, liteTopic: {}", namespace, endpoint, workAgentResponseTopic, workAgentResponseGroupID, liteTopic);
            return null;
        }
        Map<String, LitePushConsumer> consumerMap = ROCKETMQ_CONSUMER_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        LitePushConsumer litePushConsumer = consumerMap.computeIfAbsent(workAgentResponseTopic, k -> {
            try {
                return buildConsumer(endpoint, namespace, accessKey, secretKey, workAgentResponseGroupID, workAgentResponseTopic);
            } catch (ClientException e) {
                log.error("RocketMQTransport initRocketMQProducerAndConsumer buildConsumer error: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
        if (null != litePushConsumer) {
            litePushConsumer.subscribeLite(liteTopic);
        }
        return litePushConsumer;
    }

    /**
     * 构建消费者
     * @param endpoint
     * @param namespace
     * @param accessKey
     * @param secretKey
     * @param workAgentResponseGroupID
     * @param workAgentResponseTopic
     * @return
     * @throws ClientException
     */
    public static LitePushConsumer buildConsumer(String endpoint, String namespace, String accessKey, String secretKey, String workAgentResponseGroupID, String workAgentResponseTopic) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(workAgentResponseTopic)) {
            log.error("RocketMQTransport buildConsumer check param error");
            return null;
        }
        return buildConsumerForLite(endpoint, namespace, accessKey, secretKey, workAgentResponseGroupID, workAgentResponseTopic, buildClientMessageListener(namespace));
    }

    /**
     * 构建LiteConsumer
     * @param endpoint
     * @param namespace
     * @param accessKey
     * @param secretKey
     * @param workAgentResponseGroupID
     * @param workAgentResponseTopic
     * @param messageListener
     * @return
     * @throws ClientException
     */
    public static LitePushConsumer buildConsumerForLite(String endpoint, String namespace, String accessKey, String secretKey, String workAgentResponseGroupID, String workAgentResponseTopic, MessageListener messageListener) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(workAgentResponseTopic) || null == messageListener) {
            log.error("RocketMQTransport buildConsumer check param error");
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(endpoint)
            .setNamespace(namespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .build();
        return provider.newLitePushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(workAgentResponseGroupID)
            .bindTopic(workAgentResponseTopic)
            .setMessageListener(messageListener).build();
    }

    /**
     * 构建客户端消息监听器
     * @param namespace rocketmq 命名空间
     * @return 消息监听器
     */
    private static MessageListener buildClientMessageListener(String namespace) {
        return messageView -> {
            try {
                //解析得到LiteTopic
                Optional<String> liteTopicOpt = messageView.getLiteTopic();
                String liteTopic = liteTopicOpt.get();
                //判断LiteTopic 是否为空
                if (StringUtils.isEmpty(liteTopic)) {
                    log.error("RocketMQTransport buildConsumer liteTopic is empty");
                    return ConsumeResult.SUCCESS;
                }
                //获取消息中body对应的内容
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                String resultStr = new String(result, StandardCharsets.UTF_8);
                //对获取的结果进行反序列化为RocketMQResponse
                RocketMQResponse response = JSON.parseObject(resultStr, RocketMQResponse.class);
                //反序列化是否成功
                if (null == response || StringUtils.isEmpty(response.getMessageId())) {
                    log.error("RocketMQTransport litePushConsumer consumer error, response is null or messageId is empty");
                    return ConsumeResult.SUCCESS;
                }
                //处理非流式结果
                if (!response.isStream()) {
                    return dealNonStreamResult(response, namespace);
                }
                //处理流式结果
                return dealStreamResult(response, namespace, liteTopic);
            } catch (Exception e) {
                log.error("RocketMQTransport litePushConsumer consumer error, msgId: {}, error: {}", messageView.getMessageId(), e.getMessage());
                return ConsumeResult.SUCCESS;
            }
        };
    }

    /**
     * 构建消费者
     * @param endpoint
     * @param namespace
     * @param accessKey
     * @param secretKey
     * @param bizGroup
     * @param bizTopic
     * @param messageListener
     * @return
     * @throws ClientException
     */
    public static PushConsumer buildConsumer(String endpoint, String namespace, String accessKey, String secretKey, String bizGroup, String bizTopic, MessageListener messageListener) throws ClientException {
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(endpoint)
            .setNamespace(namespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .build();
        String tag = "*";
        return provider.newPushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(bizGroup)
            .setSubscriptionExpressions(Collections.singletonMap(bizTopic, new FilterExpression(tag, FilterExpressionType.TAG)))
            .setMessageListener(messageListener).build();
    }

    /**
     * 构建消息体
     * @param topic
     * @param liteTopic
     * @param response
     * @return
     */
    public static Message buildMessage(String topic, String liteTopic, RocketMQResponse response) {
        if (StringUtils.isEmpty(topic) || StringUtils.isEmpty(liteTopic)) {
            log.error("RocketMQA2AServerRoutes buildMessage param error, topic: {}, liteTopic: {}, response: {}", topic, liteTopic, JSON.toJSONString(response));
            return null;
        }
        String missionJsonStr = JSON.toJSONString(response);
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        return provider.newMessageBuilder()
            .setTopic(topic)
            .setBody(missionJsonStr.getBytes(StandardCharsets.UTF_8))
            .setLiteTopic(liteTopic)
            .build();
    }

    /**
     * 发送 A2A 协议请求消息
     * @param payloadAndHeaders 包含请求体（payload）和 HTTP 头信息（headers）的封装对象，通常用于透传上下文或认证信息
     * @param agentTopic 目标 Agent 的请求接收 Topic，即消息实际发送到的 RocketMQ Topic
     * @param liteTopic 轻量级响应 Topic
     * @param workAgentResponseTopic 轻量级响应主Topic
     * @param producer 生产者
     * @param taskId 任务ID
     * @return rocketmq 消息ID
     * @throws JsonProcessingException json处理异常
     */
    public static String sendRocketMQRequest(PayloadAndHeaders payloadAndHeaders, String agentTopic, String liteTopic, String workAgentResponseTopic, Producer producer, String taskId) throws JsonProcessingException {
        if (null == payloadAndHeaders || StringUtils.isEmpty(agentTopic) || StringUtils.isEmpty(liteTopic) || StringUtils.isEmpty(workAgentResponseTopic) || null == producer) {
            log.error("RocketMQTransport sendRocketMQRequest param error, payloadAndHeaders: {}, agentTopic: {}, workAgentResponseTopic: {}, liteTopic: {}, producer: {}", payloadAndHeaders, agentTopic, workAgentResponseTopic, liteTopic, producer);
            return null;
        }
        //构建RocketMQRequest
        RocketMQRequest request = new RocketMQRequest();
        //将负载中相关数据进行序列化
        request.setRequestBody(Utils.OBJECT_MAPPER.writeValueAsString(payloadAndHeaders.getPayload()));
        //设置AgentTopic
        request.setAgentTopic(agentTopic);
        //设置客户端接收响应的轻量级主topic
        request.setWorkAgentResponseTopic(workAgentResponseTopic);
        //设置客户端接收响应的轻量级Topic
        request.setLiteTopic(liteTopic);
        //遍历请求头中的数据，并将KV数据写入请求
        if (payloadAndHeaders.getHeaders() != null) {
            for (Map.Entry<String, String> entry : payloadAndHeaders.getHeaders().entrySet()) {
                request.addHeader(entry.getKey(), entry.getValue());
            }
        }
        //对请求进行序列化
        String messageBodyStr = serialText(request);
        if (StringUtils.isEmpty(messageBodyStr)) {
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        byte[] body = messageBodyStr.getBytes(StandardCharsets.UTF_8);
        //如果有taskId 进行特殊处理
        Message message = null;
        //判断TaskId 是否为空 以及 TASK_SERVER_RECEIPT_MAP 中是否缓存了对应的服务端回执信息
        if (!StringUtils.isEmpty(taskId) && TASK_SERVER_RECEIPT_MAP.containsKey(taskId)) {
            //通过任务Id 获取 服务端回执信息
            ServerReceiptInfo serverReceiptInfo = TASK_SERVER_RECEIPT_MAP.get(taskId);
            //构建Message
            message = provider.newMessageBuilder().setTopic(serverReceiptInfo.getServerWorkAgentResponseTopic()).setLiteTopic(serverReceiptInfo.getServerLiteTopic()).setBody(body).build();
            log.info("send message to server liteTopic taskId: {}, serverReceiptInfo: {}", taskId, JSON.toJSONString(serverReceiptInfo));
        } else {
            //构建Message
            message = provider.newMessageBuilder().setTopic(agentTopic).setBody(body).build();
            log.info("send message to server use normal topic");
        }
        try {
            final SendReceipt sendReceipt = producer.send(message);
            if (!StringUtils.isEmpty(sendReceipt.getMessageId().toString())) {
                return sendReceipt.getMessageId().toString();
            }
        } catch (Throwable t) {
            log.error("sendRocketMQRequest send message failed, error: {}", t.getMessage());
        }
        return null;
    }

    /**
     * 处理流式结果
     * @param response a2a中 rocketmq响应结果
     * @param namespace rocketmq 命名空间
     * @param liteTopic 轻量级Topic
     * @return 消费结果
     */
    private static ConsumeResult dealStreamResult(RocketMQResponse response, String namespace, String liteTopic) {
        //对参数进行检查
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(liteTopic) || !response.isEnd() && StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealStreamResult param is error, response: {}, liteTopic: {}", JSON.toJSONString(response), liteTopic);
            return ConsumeResult.SUCCESS;
        }
        //通过命名空间获取 默认的 SSE监听器Map
        Map<String, SSEEventListener> sseEventListenerMap = MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
        //如果这个SSE监听器Map 为null，则直接返回消费成功
        if (null == sseEventListenerMap) {
            return ConsumeResult.SUCCESS;
        }
        //从这个SSE监听器Map中获取 messageId 对应的 SSE监听器
        SSEEventListener sseEventListener = sseEventListenerMap.get(response.getMessageId());
        //如果SSE监听处理器为null
        if (null == sseEventListener) {
            //判断是否有默认可以恢复的Map可以使用
            Map<String, Boolean> booleanMap = LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.get(namespace);
            //如果没有默认可以使用SSE监听处理器，则直接返回消费成功
            if (null == booleanMap || !Boolean.TRUE.equals(booleanMap.get(liteTopic))) {
                return ConsumeResult.SUCCESS;
            }
            //如果默认SSE监听处理器Map不为空 并且其中包含这个命名空间
            if (!RECOVER_MESSAGE_STREAM_RESPONSE_MAP.isEmpty() && RECOVER_MESSAGE_STREAM_RESPONSE_MAP.containsKey(namespace)) {
                //获取对应的SSE监听器Map
                Map<String, SSEEventListener> sseEventListenerMapRecover = RECOVER_MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
                if (null == sseEventListenerMapRecover) {
                    return ConsumeResult.SUCCESS;
                }
                //获取对应的SSE监听器
                sseEventListener = sseEventListenerMapRecover.get(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER);
                //如果SSE监听器为null，则直接返回消费成功
                if (null == sseEventListener) {
                    return ConsumeResult.SUCCESS;
                }
            }
            //如果SSE监听器为null，则直接返回消费成功
            if (null == sseEventListener) {
                return ConsumeResult.SUCCESS;
            }
        }
        //从响应中获取对应的响应体数据
        String item = response.getResponseBody();
        if (!StringUtils.isEmpty(item) && item.startsWith(DATA_PREFIX)) {
            //对data: 部分进行截取后字符串数据进行处理
            item = item.substring(5).trim();
            if (!item.isEmpty()) {
                try {
                    //将处理后的数据交给sseEventListener 处理
                    sseEventListener.onMessage(item, new CompletableFuture<>());
                } catch (Throwable e) {
                    log.error("RocketMQTransport dealStreamResult error: {}", e.getMessage());
                    return ConsumeResult.FAILURE;
                }
            }
            //如果响应结果中的标签为 标识本消息为本任务的最后一条消息，则对sseEventListenerMap中 messageId对应的SSE监听处理器进行移除
            if (response.isEnd() && !StringUtils.isEmpty(response.getMessageId())) {
                sseEventListenerMap.remove(response.getMessageId());
            }
        }
        return ConsumeResult.SUCCESS;
    }

    /**
     * 处理非流式结果
     * @param response a2a协议中获取得到的响应结果
     * @param namespace 命名空间
     * @return 消费结果
     */
    private static ConsumeResult dealNonStreamResult(RocketMQResponse response, String namespace) {
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealNonStreamResult param is error, response: {}", JSON.toJSONString(response));
            return ConsumeResult.SUCCESS;
        }
        //从MESSAGE_RESPONSE_MAP中获取 completableFutureMap
        Map<String, AsyncTypedResult> completableFutureMap = MESSAGE_RESPONSE_MAP.get(namespace);
        //如果completableFutureMap 不为空，并且包含这个MessageId
        if (null != completableFutureMap && completableFutureMap.containsKey(response.getMessageId())) {
            //获取得到 异步结果处理器
            AsyncTypedResult asyncTypedResult = completableFutureMap.get(response.getMessageId());
            //对异步结果处理中的CompleteFuture进行调用complete进行异步结果同步
            asyncTypedResult.getCompletableFuture().complete(response.getResponseBody());
            //当这个结果类型为SendMessage时
            if (SEND_MESSAGE_RESPONSE_REFERENCE == asyncTypedResult.getTypeReference()) {
                try {
                    //反序列化为SendMessageResponse
                    SendMessageResponse sendMessageResponse = unmarshalResponse(response.getResponseBody(), SEND_MESSAGE_RESPONSE_REFERENCE);
                    //获取Task对象
                    Task result = (Task)sendMessageResponse.getResult();
                    //以taskId为key，服务端的轻量级响应Topic与LiteTopic作为Value写入TASK_SERVER_RECEIPT_MAP
                    TASK_SERVER_RECEIPT_MAP.putIfAbsent(result.getId(), new ServerReceiptInfo(response.getServerWorkAgentResponseTopic(), response.getServerLiteTopic()));
                    log.info("dealNonStreamResult put task info when send message, taskId: {}, serverInfo: {}", result.getId(), JSON.toJSONString(TASK_SERVER_RECEIPT_MAP.get(result.getId())));
                } catch (JsonProcessingException e) {
                   log.error("dealNonStreamResult unmarshalResponse error: {}", e.getMessage());
                }
            //当这个结果类型时CancelTask时
            } else if (CANCEL_TASK_RESPONSE_REFERENCE == asyncTypedResult.getTypeReference()) {
                try {
                    //将响应结果反序列化为CancelTaskResponse
                    CancelTaskResponse cancelTaskResponse = unmarshalResponse(response.getResponseBody(), CANCEL_TASK_RESPONSE_REFERENCE);
                    //从响应结果中获取Task
                    Task result = cancelTaskResponse.getResult();
                    //对TASK_SERVER_RECEIPT_MAP中taskId对应的服务端数据进行移除
                    ServerReceiptInfo remove = TASK_SERVER_RECEIPT_MAP.remove(result.getId());
                    log.info("dealNonStreamResult cancel task, taskId: {}, remove: {}", result.getId(), JSON.toJSONString(remove));
                } catch (JsonProcessingException e) {
                    log.error("dealNonStreamResult unmarshalResponse error: {}", e.getMessage());
                }
            //当这个结果类型是GetTask的时
            } else if (GET_TASK_RESPONSE_REFERENCE == asyncTypedResult.getTypeReference()) {
                try {
                    //对响应结果反序列化为GetTaskResponse
                    GetTaskResponse getTaskResponse = unmarshalResponse(response.getResponseBody(), GET_TASK_RESPONSE_REFERENCE);
                    //获取任务状态
                    TaskStatus status = getTaskResponse.getResult().getStatus();
                    //如果任务状态为Complete，对TASK_SERVER_RECEIPT_MAP中服务端回执数据进行移除
                    if (null != status && status.state() == TaskState.COMPLETED) {
                        ServerReceiptInfo remove = TASK_SERVER_RECEIPT_MAP.remove(getTaskResponse.getResult().getId());
                        log.info("dealNonStreamResult get task complete, taskId: {}, remove: {}", getTaskResponse.getResult().getId(), JSON.toJSONString(remove));
                    }
                } catch (JsonProcessingException e) {
                    log.error("dealNonStreamResult unmarshalResponse error: {}", e.getMessage());
                }

            }
        }
        return ConsumeResult.SUCCESS;
    }

    /**
     * 获取对应的结果
     * @param responseMessageId 发送请求消息的消息Id
     * @param namespace rocketmq 命名空间
     * @param typeReference 类型引用
     * @return 结果数据
     * @throws ExecutionException 执行异常
     * @throws InterruptedException 中断异常
     * @throws TimeoutException 超时异常
     */
    public static String getResult(String responseMessageId, String namespace, TypeReference typeReference) throws ExecutionException, InterruptedException, TimeoutException {
        if (StringUtils.isEmpty(responseMessageId)) {
            throw new RuntimeException("responseMessageId is null");
        }
        //获取对应的CompleteFutureMap
        Map<String, AsyncTypedResult> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        //将MessageId为Key 异步类型结果对象为value 写入map
        completableFutureMap.put(responseMessageId, new AsyncTypedResult(completableFuture, typeReference));
        String result = completableFuture.get(120, TimeUnit.SECONDS);
        completableFutureMap.remove(responseMessageId);
        return result;
    }

    /**
     * 对响应结果进行反序列化
     * @param response 响应结果字符串
     * @param typeReference 类型引用
     * @return 响应结果
     * @param <T>
     * @throws A2AClientException a2a客户端异常
     * @throws JsonProcessingException JSON处理异常
     */
    public static <T extends JSONRPCResponse<?>> T unmarshalResponse(String response, TypeReference<T> typeReference)
        throws A2AClientException, JsonProcessingException {
        T value = Utils.unmarshalFrom(response, typeReference);
        JSONRPCError error = value.getError();
        if (error != null) {
            throw new A2AClientException(error.getMessage() + (error.getData() != null ? ": " + error.getData() : ""), error);
        }
        return value;
    }

    /**
     * 将对象进行序列化
     * @param o 等待序列化的对象
     * @return 序列化结果
     */
    public static String toJsonString(Object o) {
        if (null == o) {
            log.error("toJsonString param is null");
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 将对象进行JSON序列化
     * @param rocketMQRequest rocketmq请求
     * @return JSON序列化后字符串
     */
    public static String serialText(RocketMQRequest rocketMQRequest) {
        if (null == rocketMQRequest || StringUtils.isEmpty(rocketMQRequest.getRequestBody()) || StringUtils.isEmpty(rocketMQRequest.getWorkAgentResponseTopic()) || StringUtils.isEmpty(rocketMQRequest.getLiteTopic()) || StringUtils.isEmpty(rocketMQRequest.getAgentTopic())) {
            log.error("serialText param error rocketMQRequest: {}", JSON.toJSONString(rocketMQRequest));
            return null;
        }
        return JSON.toJSONString(rocketMQRequest);
    }
}
