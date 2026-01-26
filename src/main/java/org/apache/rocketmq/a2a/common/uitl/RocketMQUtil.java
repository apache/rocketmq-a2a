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
package org.apache.rocketmq.a2a.common.uitl;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
import org.apache.rocketmq.a2a.common.model.ServerReceiptInfo;
import org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant;
import org.apache.rocketmq.a2a.common.future.A2AResponseFuture;
import org.apache.rocketmq.a2a.common.model.RocketMQRequest;
import org.apache.rocketmq.a2a.common.model.RocketMQResponse;
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
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.CANCEL_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.GET_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.SEND_MESSAGE_RESPONSE_REFERENCE;

/**
 * Central utility class for managing RocketMQ client resources (Producers, Consumers) in A2A protocol.
 *
 * <p>This class provides:
 * <ul>
 *   <li>Namespace-isolated producer/consumer pooling</li>
 *   <li>Message sending and response correlation via {@link #MESSAGE_RESPONSE_MAP}</li>
 *   <li>Streaming response handling using SSE event listeners</li>
 *   <li>Task-level server affinity with sticky routing through {@link #TASK_SERVER_RECEIPT_MAP}</li>
 * </ul>
 *
 * <p>All operations are thread-safe and designed for long-running agent processes.
 */
public class RocketMQUtil {
    private static final Logger log = LoggerFactory.getLogger(RocketMQUtil.class);
    public static final ConcurrentMap<String /* namespace */, Map<String /* WorkerAgentResponseTopic */, LitePushConsumer>> ROCKETMQ_CONSUMER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* agentTopic */, Producer>> ROCKETMQ_PRODUCER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, A2AResponseFuture>> MESSAGE_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, SSEEventListener>> MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* liteTopic */, Boolean>> LITE_TOPIC_USE_DEFAULT_RECOVER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* Key */, SSEEventListener>> RECOVER_MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* taskId */, ServerReceiptInfo /* ServerInfo */> TASK_SERVER_RECEIPT_MAP = new ConcurrentHashMap<>();

    /**
     * Validates required configuration parameters for initializing RocketMQTransport.
     *
     * <p>All parameters are mandatory. If any is {@code null} or empty, an {@link IllegalArgumentException}
     * is thrown with detailed information about which field(s) failed validation.
     *
     * @param endpoint the network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param workAgentResponseTopic the lightweight topic used to receive asynchronous replies todo
     * @param workAgentResponseGroupID the consumer group ID (CID) for subscribing to the response topic {@code workAgentResponseTopic}
     * @param liteTopic the lite topic for streaming or fast-path responses todo
     * @param agentTopic the normal business topic bound to the target agent
     */
    public static void checkConfigParam(String endpoint, String workAgentResponseTopic, String workAgentResponseGroupID, String liteTopic, String agentTopic) {
        Map<String, String> params = new HashMap<>();
        params.put("endpoint", endpoint);
        params.put("workAgentResponseTopic", workAgentResponseTopic);
        params.put("workAgentResponseGroupID", workAgentResponseGroupID);
        params.put("liteTopic", liteTopic);
        params.put("agentTopic", agentTopic);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (StringUtils.isEmpty(entry.getValue())) {
                String key = entry.getKey();
                log.error("RocketMQUtil checkConfigParam: [{}] is empty", key);
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                "Missing mandatory RocketMQ config parameters: " + missing
            );
        }
    }

    /**
     * Gets or initializes a shared Producer instance for the given namespace and agent topic.
     *
     * <p>If a Producer already exists for the specified (namespace, agentTopic), it is reused.
     * Otherwise, a new Producer is created using the provided credentials and endpoint.
     *
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param agentTopic the target topic to which messages will be sent.
     * @return a Producer instance.
     */
    public static Producer getOrCreateProducer(String namespace, String endpoint, String accessKey, String secretKey, String agentTopic) {
        if (null == namespace || StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(agentTopic)) {
            log.error("RocketMQUtil getOrCreateProducer param error, namespace: [{}], endpoint: [{}], agentTopic: [{}]", namespace, endpoint, agentTopic);
            throw new IllegalArgumentException("initAndGetProducer param error");
        }
        Map<String, Producer> producerMap = ROCKETMQ_PRODUCER_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        //Get or create producer for this topic
        return producerMap.computeIfAbsent(agentTopic, k -> {
            try {
                return buildProducer(namespace, endpoint, accessKey, secretKey, k);
            } catch (ClientException e) {
                log.error("RocketMQUtil getOrCreateProducer failed to create Producer for topic [{}] in namespace [{}]", agentTopic, namespace, e);
                throw new IllegalStateException("getOrCreateProducer failed to initialize RocketMQ Producer", e);
            }
        });
    }

    /**
     * Creates a new {@link Producer} without caching.
     *
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param endpoint  the network endpoint of the RocketMQ service.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param topics the destination topics this producer is allowed to send to.
     * @return a Producer instance.
     * @throws ClientException if client initialization fails
     */
    public static Producer buildProducer(String namespace, String endpoint, String accessKey, String secretKey, String... topics) throws ClientException {
        if (null == namespace || StringUtils.isEmpty(endpoint)) {
            log.error("RocketMQUtil buildProducer param error, namespace: [{}], endpoint: [{}]", namespace, endpoint);
            throw new IllegalArgumentException("buildProducer param error");
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
     * Gets or initializes a shared {@link LitePushConsumer} for receiving A2A response messages.
     *
     * <p>The consumer is cached per (namespace, workAgentResponseTopic). If already exists, it is reused.
     * After initialization, it subscribes to the specified {@code liteTopic}.</p>
     *
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param endpoint  the network endpoint of the RocketMQ service.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param workAgentResponseTopic the lightweight topic used to receive asynchronous replies.
     * @param workAgentResponseGroupID the consumer group ID (CID) for subscribing to the response topic {@code workAgentResponseTopic}.
     * @param liteTopic Typically, a liteTopic that is bound to {@code #workAgentResponseTopic}.
     *                  LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     * @return a LitePushConsumer instance.
     */
    public static LitePushConsumer getOrCreateLitePushConsumer(String namespace, String endpoint, String accessKey, String secretKey, String workAgentResponseTopic, String workAgentResponseGroupID, String liteTopic) {
        if (null == namespace || StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseTopic) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(liteTopic)) {
            log.error("RocketMQUtil getOrCreateLitePushConsumer param error, namespace: [{}], endpoint: [{}], workAgentResponseTopic: [{}], workAgentResponseGroupID: [{}], liteTopic: [{}]", namespace, endpoint, workAgentResponseTopic, workAgentResponseGroupID, liteTopic);
            throw new IllegalArgumentException("getOrCreateLitePushConsumer param error");
        }
        // Get or create consumer map for this namespace
        Map<String, LitePushConsumer> consumerMap = ROCKETMQ_CONSUMER_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        LitePushConsumer litePushConsumer = consumerMap.computeIfAbsent(workAgentResponseTopic, k -> {
            try {
                return buildLitePushConsumer(endpoint, namespace, accessKey, secretKey, workAgentResponseTopic, workAgentResponseGroupID, buildA2AClientMessageListener(namespace));
            } catch (ClientException e) {
                log.error("RocketMQUtil getOrCreateLitePushConsumer buildConsumer error", e);
                throw new IllegalStateException(e);
            }
        });
        //litePushConsumer sub the liteTopic
        if (null != litePushConsumer) {
            try {
                litePushConsumer.subscribeLite(liteTopic);
            } catch (ClientException e) {
                log.error("RocketMQUtil getOrCreateLitePushConsumer subscribeLite error, liteTopic: [{}]", liteTopic, e);
                throw new RuntimeException(e);
            }
        }
        return litePushConsumer;
    }


    /**
     * Creates a new {@link LitePushConsumer} without caching.
     *
     * @param endpoint  the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param workAgentResponseTopic the lightweight topic used to receive asynchronous replies.
     * @param workAgentResponseGroupID the consumer group ID (CID) for subscribing to the response topic {@code workAgentResponseTopic}.
     * @param messageListener the listener for processing incoming messages.
     * @return a LitePushConsumer instance.
     * @throws ClientException if creation fails
     */
    public static LitePushConsumer buildLitePushConsumer(String endpoint, String namespace, String accessKey, String secretKey, String workAgentResponseTopic, String workAgentResponseGroupID, MessageListener messageListener) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(workAgentResponseTopic) || null == messageListener) {
            log.error("RocketMQUtil buildLitePushConsumer check param error, endpoint: [{}], workAgentResponseGroupID: [{}], workAgentResponseTopic: [{}], messageListener: [{}]", endpoint, workAgentResponseGroupID, workAgentResponseTopic, messageListener);
            throw new IllegalArgumentException("buildLitePushConsumer param error");
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
     * Creates a new {@link PushConsumer} for subscribing to a business topic with tag filtering.
     *
     * <p>This consumer uses a wildcard tag expression ({@code *}) to receive all messages from the specified topic.</p>
     *
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param bizGroup the consumer group ID (CID).
     * @param bizTopic the target business topic to subscribe.
     * @param messageListener the listener that processes incoming messages.
     * @return a PushConsumer instance.
     * @throws ClientException if failed to create the consumer.
     */
    public static PushConsumer buildPushConsumer(String endpoint, String namespace, String accessKey, String secretKey, String bizGroup, String bizTopic, MessageListener messageListener) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(bizGroup) || StringUtils.isEmpty(bizTopic) || null == messageListener) {
            log.error("RocketMQUtil buildPushConsumer check param error, endpoint: [{}], bizGroup: [{}], bizTopic: [{}], messageListener: [{}]", endpoint, bizGroup, bizTopic, messageListener);
            throw new IllegalArgumentException("buildPushConsumer param error");
        }
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
     * Builds a {@link MessageListener} for processing A2A protocol responses received via RocketMQ.
     *
     * <p>The listener:
     * <ul>
     *   <li>Extracts the payload and deserializes it into a {@link RocketMQResponse}</li>
     *   <li>Routes non-streaming results to {@link #dealNonStreamResult}</li>
     *   <li>Handles streaming chunks via {@link #dealStreamResult}</li>
     * </ul>
     *
     * <p>If the message cannot be parsed or has no messageId, it is skipped safely.</p>
     *
     * @param namespace the namespace used to isolate response maps and listeners.
     * @return a message listener.
     */
    private static MessageListener buildA2AClientMessageListener(String namespace) {
        return messageView -> {
            try {
                //parse and obtain the liteTopic
                Optional<String> liteTopicOpt = messageView.getLiteTopic();
                String liteTopic = liteTopicOpt.get();
                if (StringUtils.isEmpty(liteTopic)) {
                    log.error("RocketMQUtil A2AClientMessageListener receive the liteTopic of the message is empty, so skip it");
                    return ConsumeResult.SUCCESS;
                }
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                //Deserialize the retrieved result into a RocketMQResponse
                RocketMQResponse response = JSON.parseObject(new String(result, StandardCharsets.UTF_8), RocketMQResponse.class);
                if (null == response || StringUtils.isEmpty(response.getMessageId())) {
                    log.error("RocketMQUtil A2AClientMessageListener consumer error, response is null or messageId is empty, so skip it");
                    return ConsumeResult.SUCCESS;
                }
                //Process non-streaming results
                if (!response.isStream()) {
                    return dealNonStreamResult(response, namespace);
                }
                //Process streaming results
                return dealStreamResult(response, namespace, liteTopic);
            } catch (Exception e) {
                log.error("RocketMQUtil A2AClientMessageListener consumer error, msgId: [{}]", messageView.getMessageId(), e);
                return ConsumeResult.SUCCESS;
            }
        };
    }

    /**
     * Constructs a RocketMQ {@link Message} for sending an A2A protocol response.
     *
     * <p>The message is sent to the specified {@code topic}, carries serialized {@link RocketMQResponse},
     * and includes a {@code liteTopic} for routing fast-path or streaming replies.</p> //todo
     *
     * @param topic the destination topic where the message will be sent.
     * @param liteTopic the lite topic used by the client for receiving responses
     * @param response the response data to serialize and send
     * @return a built Message instance, or {@code null} if validation fails
     */
    public static Message buildMessageForResponse(String topic, String liteTopic, RocketMQResponse response) {
        if (StringUtils.isEmpty(topic) || StringUtils.isEmpty(liteTopic)) {
            log.error("RocketMQUtil buildMessageForResponse param error, topic: [{}], liteTopic: [{}], response: [{}]", topic, liteTopic, JSON.toJSONString(response));
            throw new IllegalArgumentException("buildMessageForResponse param error");
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
     * Sends an A2A-compliant request message via RocketMQ.
     *
     * <p>If a {@code taskId} is provided and associated server routing information exists in {@link #TASK_SERVER_RECEIPT_MAP},
     * the message will be sent directly to the target server's response topics for sticky session support.
     * Otherwise, it is sent to the default {@code agentTopic}.</p>
     *
     * @param payloadAndHeaders the request payload and metadata (e.g., auth headers)
     * @param agentTopic the default destination topic bound to the target agent.
     * @param liteTopic Typically, a liteTopic that is bound to {@code #workAgentResponseTopic}.
     *                  LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     * @param workAgentResponseTopic the lightweight topic used to receive asynchronous replies.
     * @param producer the RocketMQ producer used to send the message
     * @param taskId optional task ID for enabling server affinity (sticky routing)
     * @return the assigned RocketMQ message ID if sent successfully; {@code null} otherwise
     * @throws JsonProcessingException if the payload cannot be serialized into JSON
     */
    public static String sendRocketMQRequest(PayloadAndHeaders payloadAndHeaders, String agentTopic, String liteTopic, String workAgentResponseTopic, Producer producer, String taskId)
        throws JsonProcessingException, ClientException {
        if (null == payloadAndHeaders || StringUtils.isEmpty(agentTopic) || StringUtils.isEmpty(liteTopic) || StringUtils.isEmpty(workAgentResponseTopic) || null == producer) {
            log.error("RocketMQUtil sendRocketMQRequest param error, payloadAndHeaders: [{}], agentTopic: [{}], workAgentResponseTopic: [{}], liteTopic: [{}], producer: [{}]", payloadAndHeaders, agentTopic, workAgentResponseTopic, liteTopic, producer);
            throw new IllegalArgumentException("sendRocketMQRequest param error");
        }
        //build RocketMQRequest
        RocketMQRequest request = new RocketMQRequest();
        request.setRequestBody(Utils.OBJECT_MAPPER.writeValueAsString(payloadAndHeaders.getPayload()));
        request.setDestAgentTopic(agentTopic);
        request.setWorkAgentResponseTopic(workAgentResponseTopic);
        request.setLiteTopic(liteTopic);
        if (payloadAndHeaders.getHeaders() != null) {
            for (Map.Entry<String, String> entry : payloadAndHeaders.getHeaders().entrySet()) {
                request.addHeader(entry.getKey(), entry.getValue());
            }
        }
        //Serialize the request
        String messageBodyStr = serialText(request);
        if (StringUtils.isEmpty(messageBodyStr)) {
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        byte[] body = messageBodyStr.getBytes(StandardCharsets.UTF_8);
        Message message = null;
        // Check if sticky routing is enabled via TASK_SERVER_RECEIPT_MAP
        if (!StringUtils.isEmpty(taskId) && TASK_SERVER_RECEIPT_MAP.containsKey(taskId)) {
            ServerReceiptInfo serverReceiptInfo = TASK_SERVER_RECEIPT_MAP.get(taskId);
            message = provider.newMessageBuilder().setTopic(serverReceiptInfo.getServerWorkAgentResponseTopic()).setLiteTopic(serverReceiptInfo.getServerLiteTopic()).setBody(body).build();
            log.debug("RocketMQUtil sendRocketMQRequest send message to serverLiteTopic taskId: [{}], serverReceiptInfo: [{}]", taskId, JSON.toJSONString(serverReceiptInfo));
        } else {
            message = provider.newMessageBuilder().setTopic(agentTopic).setBody(body).build();
            log.debug("RocketMQUtil sendRocketMQRequest send message to serverNormalTopic agentTopic: [{}]", agentTopic);
        }
        try {
            SendReceipt sendReceipt = producer.send(message);
            return sendReceipt.getMessageId().toString();
        } catch (ClientException e) {
            log.error("RocketMQUtil sendRocketMQRequest send message failed", e);
            throw e;
        }
    }

    /**
     * Processes a streaming response chunk received via RocketMQ.
     *
     * <p>This method:
     * <ul>
     *   <li>Finds the associated {@link SSEEventListener} using the {@code messageId}</li>
     *   <li>Forwards the payload (after stripping {@code data:}) to the listener</li>
     *   <li>If no listener is found but recovery mode is enabled, uses a fallback "default" listener</li>
     *   <li>Removes the listener when {@code isEnd = true}</li>
     * </ul>
     **
     * @param response the incoming A2A streaming response.
     * @param namespace logical isolation unit.
     * @param liteTopic Typically, LiteTopic is a lightweight session identifier, similar to a SessionId,
     *                  dynamically created at runtime for data storage and isolation.
     * @return {@link ConsumeResult#SUCCESS} if processed (even if skipped), {@link ConsumeResult#FAILURE} on error.
     */

    private static ConsumeResult dealStreamResult(RocketMQResponse response, String namespace, String liteTopic) {
        if (StringUtils.isEmpty(liteTopic) || null == response || StringUtils.isEmpty(response.getMessageId()) || !response.isEnd() && StringUtils.isEmpty(response.getResponseBody())) {
            log.warn("RocketMQUtil dealStreamResult param is error, response: [{}], liteTopic: [{}]", JSON.toJSONString(response), liteTopic);
            return ConsumeResult.SUCCESS;
        }
        // Get the SSE event listener map for this namespace
        Map<String, SSEEventListener> sseEventListenerMap = MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
        if (null == sseEventListenerMap) {
            log.debug("RocketMQUtil No SSE listener map found for namespace: [{}]", namespace);
            return ConsumeResult.SUCCESS;
        }
        // Try to get the specific listener by messageId
        SSEEventListener sseEventListener = sseEventListenerMap.get(response.getMessageId());
        // If not found, check if we can use the default recovery listener
        if (null == sseEventListener) {
            Map<String, Boolean> recoverFlagMap = LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.get(namespace);
            if (null == recoverFlagMap || !Boolean.TRUE.equals(recoverFlagMap.get(liteTopic))) {
                log.debug("RocketMQUtil No SSE listener for msgId: [{}], and recovery is not enabled for liteTopic: [{}]", response.getMessageId(), liteTopic);
                return ConsumeResult.SUCCESS;
            }
            Map<String, SSEEventListener> recoverListenerMap = RECOVER_MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
            if (recoverListenerMap == null || null == recoverListenerMap.get(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER)) {
                log.debug("RocketMQUtil recoverListenerMap is null or default SSE Listener is null, namespace: [{}]", namespace);
                return ConsumeResult.SUCCESS;
            }
            sseEventListener = recoverListenerMap.get(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER);
        }
        // Extract and process payload
        String item = response.getResponseBody();
        if (!StringUtils.isEmpty(item)) {
            String prefix = RocketMQA2AConstant.DATA_PREFIX;
            if (item.startsWith(prefix)) {
                item = item.substring(prefix.length()).trim();
            }
            if (!item.isEmpty()) {
                try {
                    sseEventListener.onMessage(item, new CompletableFuture<>());
                } catch (Exception e) {
                    log.error("RocketMQUtil failed to deliver stream chunk to SSE listener for msgId: [{}]", response.getMessageId(), e);
                }
            }
        }
        // Clean up listener if this is the final message
        if (response.isEnd()) {
            sseEventListenerMap.remove(response.getMessageId());
            log.debug("RocketMQUtil remove SSE event listener for completed stream, msgId: [{}]", response.getMessageId());
        }
        return ConsumeResult.SUCCESS;
    }

    /**
     * Processes a non-streaming (one-time) A2A response and completes the corresponding async future.
     *
     * <p>Additionally, based on the request type:
     * <ul>
     *   <li>{@link SendMessageResponse}: caches server-side topics in {@link #TASK_SERVER_RECEIPT_MAP} for sticky routing</li>
     *   <li>{@link CancelTaskResponse} or completed {@link GetTaskResponse}: removes cached server info</li>
     * </ul>
     *
     * <p>This enables features like:
     * <ul>
     *   <li>Sticky session: follow-up requests routed to the same agent instance</li>
     *   <li>Cleanup after cancellation or completion</li>
     * </ul>
     *
     * @param response the received response message.
     * @param namespace logical isolation unit (e.g., tenant/environment)
     * @return {@link ConsumeResult#SUCCESS} if handled or skipped safely.
     */
    private static ConsumeResult dealNonStreamResult(RocketMQResponse response, String namespace) {
        if (response == null || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(response.getResponseBody())) {
            log.warn("RocketMQUtil Invalid non-streaming response: missing messageId or responseBody, response: [{}]", JSON.toJSONString(response));
            return ConsumeResult.SUCCESS;
        }
        Map<String, A2AResponseFuture> responseMap = MESSAGE_RESPONSE_MAP.get(namespace);
        if (responseMap == null) {
            log.debug("RocketMQUtil No pending responses found for namespace: [{}]", namespace);
            return ConsumeResult.SUCCESS;
        }
        // Find the corresponding async future by messageId
        A2AResponseFuture future = responseMap.get(response.getMessageId());
        if (future == null) {
            log.debug("RocketMQUtil No pending future found for messageId: [{}]", response.getMessageId());
            return ConsumeResult.SUCCESS;
        }
        // Complete the CompletableFuture with raw response body
        future.getCompletableFuture().complete(response.getResponseBody());
        TypeReference<?> expectedType = future.getTypeReference();
        try {
            // Case 1: Response from SendMessageRequest -> cache server receipt
            if (expectedType == RocketMQA2AConstant.SEND_MESSAGE_RESPONSE_REFERENCE) {
                handleSendMessageResponse(response);
                // Case 2: Response from CancelTaskRequest -> remove cached server receipt
            } else if (expectedType == RocketMQA2AConstant.CANCEL_TASK_RESPONSE_REFERENCE) {
                handleCancelTaskResponse(response);
                // Case 3: Response from GetTaskRequest -> remove if status is completed
            } else if (expectedType == RocketMQA2AConstant.GET_TASK_RESPONSE_REFERENCE) {
                handleGetTaskResponse(response);
            }
        } catch (JsonProcessingException e) {
            log.warn("RocketMQUtil failed to deserialize response for messageId [{}]. Ignoring post-processing.", response.getMessageId(), e);
        }
        return ConsumeResult.SUCCESS;
    }

    /**
     * Handles the response of a SendMessageRequest: caches server-side topics for sticky routing.
     */
    private static void handleSendMessageResponse(RocketMQResponse response) throws JsonProcessingException {
        SendMessageResponse sendResp = unmarshalResponse(response.getResponseBody(), SEND_MESSAGE_RESPONSE_REFERENCE);
        Task task = (Task) sendResp.getResult();
        if (task == null || StringUtils.isEmpty(task.getId())) {
            return;
        }
        ServerReceiptInfo info = new ServerReceiptInfo(response.getServerWorkAgentResponseTopic(), response.getServerLiteTopic());
        TASK_SERVER_RECEIPT_MAP.putIfAbsent(task.getId(), info);
        log.debug("Cached server receipt for new task, taskId: [{}], workTopic=[{}], liteTopic=[{}]", task.getId(), info.getServerWorkAgentResponseTopic(), info.getServerLiteTopic());
    }

    /**
     * Handles the response of a CancelTaskRequest: removes any cached server receipt.
     */
    private static void handleCancelTaskResponse(RocketMQResponse response) throws JsonProcessingException {
        CancelTaskResponse cancelResp = unmarshalResponse(response.getResponseBody(), CANCEL_TASK_RESPONSE_REFERENCE);
        Task task = cancelResp.getResult();
        if (task == null || StringUtils.isEmpty(task.getId())) {
            return;
        }
        ServerReceiptInfo removed = TASK_SERVER_RECEIPT_MAP.remove(task.getId());
        if (removed != null) {
            log.debug("Removed server receipt after task cancellation, taskId: [{}]", task.getId());
        }
    }
    /**
     * Handles the response of a GetTaskRequest: removes cached receipt only if status is completed.
     */
    private static void handleGetTaskResponse(RocketMQResponse response) throws JsonProcessingException {
        GetTaskResponse getResp = unmarshalResponse(response.getResponseBody(), GET_TASK_RESPONSE_REFERENCE);
        Task task = getResp.getResult();
        if (task == null || StringUtils.isEmpty(task.getId())) {
            return;
        }
        TaskStatus status = task.getStatus();
        if (status != null && status.state() == TaskState.COMPLETED) {
            ServerReceiptInfo removed = TASK_SERVER_RECEIPT_MAP.remove(task.getId());
            if (removed != null) {
                log.debug("Removed server receipt after task completion: taskId={}", task.getId());
            }
        }
    }

    /**
     * Waits for the A2A protocol response associated with the given message ID.
     *
     * <p>This method:
     * <ul>
     *   <li>Registers a {@link CompletableFuture} in the namespace-isolated map</li>
     *   <li>Blocks until the response arrives or times out (120 seconds)</li>
     *   <li>Removes the entry upon completion or timeout to prevent memory leaks</li>
     * </ul>
     *
     * @param responseMessageId the unique message ID of the sent request.
     * @param namespace namespace used for logical isolation of RocketMQ resources.
     * @param typeReference the expected response type for later deserialization.
     * @return the raw JSON response string.
     * @throws ExecutionException if the future completed exceptionally.
     * @throws InterruptedException if the current thread was interrupted while waiting.
     * @throws TimeoutException if no response received within 120 seconds.
     */
    public static String getResult(String responseMessageId, String namespace, TypeReference typeReference) throws ExecutionException, InterruptedException, TimeoutException {
        if (StringUtils.isEmpty(responseMessageId)) {
            throw new IllegalArgumentException("getResult responseMessageId is null");
        }
        Map<String, A2AResponseFuture> msgIdAndAsyncTypedMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        msgIdAndAsyncTypedMap.put(responseMessageId, new A2AResponseFuture(completableFuture, typeReference));
        String result = completableFuture.get(120, TimeUnit.SECONDS);
        msgIdAndAsyncTypedMap.remove(responseMessageId);
        return result;
    }

    /**
     * Deserializes a JSON string into the specified generic response type and checks for RPC errors.
     *
     * <p>If the parsed response contains an {@link JSONRPCError}, throws an {@link A2AClientException}.
     * Otherwise returns the result.</p>
     *
     * @param response the JSON string to deserialize.
     * @param typeReference the target type (e.g., {@code new TypeReference<SendMessageResponse>() {}}).
     * @param <T> the expected response type extending {@link JSONRPCResponse}.
     * @return the deserialized response object.
     * @throws A2AClientException if the response contains an error field.
     * @throws JsonProcessingException if parsing fails.
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

    public static String toJsonString(Object o) {
        if (null == o) {
            log.debug("toJsonString: input object is null, returning null");
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize object to JSON: {}", o.getClass().getSimpleName(), ex);
            return null;
        }
    }

    public static String serialText(RocketMQRequest rocketMQRequest) {
        if (null == rocketMQRequest || StringUtils.isEmpty(rocketMQRequest.getRequestBody()) || StringUtils.isEmpty(rocketMQRequest.getWorkAgentResponseTopic()) || StringUtils.isEmpty(rocketMQRequest.getLiteTopic()) || StringUtils.isEmpty(rocketMQRequest.getDestAgentTopic())) {
            log.error("serialText param error rocketMQRequest: {}", JSON.toJSONString(rocketMQRequest));
            return null;
        }
        return JSON.toJSONString(rocketMQRequest);
    }
}
