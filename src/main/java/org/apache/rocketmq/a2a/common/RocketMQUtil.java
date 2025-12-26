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
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCResponse;
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
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.DATA_PREFIX;

public class RocketMQUtil {
    private static final Logger log = LoggerFactory.getLogger(RocketMQUtil.class);
    public static final ConcurrentMap<String /* namespace */, Map<String /* WorkerAgentResponseTopic */, LitePushConsumer>> ROCKETMQ_CONSUMER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* agentTopic */, Producer>> ROCKETMQ_PRODUCER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, CompletableFuture<String>>> MESSAGE_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, SSEEventListener>> MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* liteTopic */, Boolean>> LITE_TOPIC_USE_DEFAULT_RECOVER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* Key */, SSEEventListener>> RECOVER_MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();

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

    //todo
    public static LitePushConsumer buildConsumer(String endpoint, String namespace, String accessKey, String secretKey, String workAgentResponseGroupID, String workAgentResponseTopic) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(workAgentResponseTopic)) {
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
            .setMessageListener(messageView -> {
                try {
                    Optional<String> liteTopicOpt = messageView.getLiteTopic();
                    String liteTopic = liteTopicOpt.get();
                    if (StringUtils.isEmpty(liteTopic)) {
                        log.error("RocketMQTransport buildConsumer liteTopic is empty");
                        return ConsumeResult.SUCCESS;
                    }
                    byte[] result = new byte[messageView.getBody().remaining()];
                    messageView.getBody().get(result);
                    String resultStr = new String(result, StandardCharsets.UTF_8);
                    RocketMQResponse response = JSON.parseObject(resultStr, RocketMQResponse.class);
                    if (null == response || StringUtils.isEmpty(response.getMessageId())) {
                        log.error("RocketMQTransport litePushConsumer consumer error, response is null or messageId is empty");
                        return ConsumeResult.SUCCESS;
                    }
                    if (!response.isStream()) {
                        return dealNonStreamResult(response, namespace);
                    }
                    return dealStreamResult(response, namespace, liteTopic);
                } catch (Exception e) {
                    log.error("RocketMQTransport litePushConsumer consumer error, msgId: {}, error: {}", messageView.getMessageId(), e.getMessage());
                    return ConsumeResult.SUCCESS;
                }
            }).build();
    }

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

    public static String sendRocketMQRequest(PayloadAndHeaders payloadAndHeaders, String agentTopic, String liteTopic, String workAgentResponseTopic, Producer producer) throws JsonProcessingException {
        if (null == payloadAndHeaders || StringUtils.isEmpty(agentTopic) || StringUtils.isEmpty(liteTopic) || StringUtils.isEmpty(workAgentResponseTopic) || null == producer) {
            log.error("RocketMQTransport sendRocketMQRequest param error, payloadAndHeaders: {}, agentTopic: {}, workAgentResponseTopic: {}, liteTopic: {}, producer: {}", payloadAndHeaders, agentTopic, workAgentResponseTopic, liteTopic, producer);
            return null;
        }
        RocketMQRequest request = new RocketMQRequest();
        request.setRequestBody(Utils.OBJECT_MAPPER.writeValueAsString(payloadAndHeaders.getPayload()));
        request.setAgentTopic(agentTopic);
        request.setWorkAgentResponseTopic(workAgentResponseTopic);
        request.setLiteTopic(liteTopic);
        if (payloadAndHeaders.getHeaders() != null) {
            for (Map.Entry<String, String> entry : payloadAndHeaders.getHeaders().entrySet()) {
                request.addHeader(entry.getKey(), entry.getValue());
            }
        }
        String messageBodyStr = serialText(request);
        if (StringUtils.isEmpty(messageBodyStr)) {
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        byte[] body = messageBodyStr.getBytes(StandardCharsets.UTF_8);
        final Message message = provider.newMessageBuilder().setTopic(agentTopic).setBody(body).build();
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

    private static ConsumeResult dealStreamResult(RocketMQResponse response, String namespace, String liteTopic) {
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(liteTopic) || !response.isEnd() && StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealStreamResult param is error, response: {}, liteTopic: {}", JSON.toJSONString(response), liteTopic);
            return ConsumeResult.SUCCESS;
        }
        Map<String, SSEEventListener> sseEventListenerMap = MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
        if (null == sseEventListenerMap) {
            return ConsumeResult.SUCCESS;
        }
        SSEEventListener sseEventListener = sseEventListenerMap.get(response.getMessageId());
        if (null == sseEventListener) {
            Map<String, Boolean> booleanMap = LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.get(namespace);
            if (null == booleanMap || !Boolean.TRUE.equals(booleanMap.get(liteTopic))) {
                return ConsumeResult.SUCCESS;
            }
            if (!RECOVER_MESSAGE_STREAM_RESPONSE_MAP.isEmpty() && RECOVER_MESSAGE_STREAM_RESPONSE_MAP.containsKey(namespace)) {
                Map<String, SSEEventListener> sseEventListenerMapRecover = RECOVER_MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
                if (null == sseEventListenerMapRecover) {
                    return ConsumeResult.SUCCESS;
                }
                sseEventListener = sseEventListenerMapRecover.get(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER);
                if (null == sseEventListener) {
                    return ConsumeResult.SUCCESS;
                }
            }
            if (null == sseEventListener) {
                return ConsumeResult.SUCCESS;
            }
        }
        String item = response.getResponseBody();
        if (!StringUtils.isEmpty(item) && item.startsWith(DATA_PREFIX)) {
            item = item.substring(5).trim();
            if (!item.isEmpty()) {
                try {
                    sseEventListener.onMessage(item, new CompletableFuture<>());
                } catch (Throwable e) {
                    log.error("RocketMQTransport dealStreamResult error: {}", e.getMessage());
                    return ConsumeResult.FAILURE;
                }
            }
            if (response.isEnd() && !StringUtils.isEmpty(response.getMessageId())) {
                sseEventListenerMap.remove(response.getMessageId());
            }
        }
        return ConsumeResult.SUCCESS;
    }

    private static ConsumeResult dealNonStreamResult(RocketMQResponse response, String namespace) {
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealNonStreamResult param is error, response: {}", JSON.toJSONString(response));
            return ConsumeResult.SUCCESS;
        }
        Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.get(namespace);
        if (null != completableFutureMap && completableFutureMap.containsKey(response.getMessageId())) {
            CompletableFuture<String> completableFuture = completableFutureMap.get(response.getMessageId());
            completableFuture.complete(response.getResponseBody());
        }
        return ConsumeResult.SUCCESS;
    }

    public static String getResult(String responseMessageId, String namespace) throws ExecutionException, InterruptedException, TimeoutException {
        if (StringUtils.isEmpty(responseMessageId)) {
            throw new RuntimeException("responseMessageId is null");
        }
        Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
        completableFutureMap.put(responseMessageId, objectCompletableFuture);
        String result = objectCompletableFuture.get(120, TimeUnit.SECONDS);
        completableFutureMap.remove(responseMessageId);
        return result;
    }

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
            log.error("toJsonString param is null");
            return null;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static String serialText(RocketMQRequest rocketMQRequest) {
        if (null == rocketMQRequest || StringUtils.isEmpty(rocketMQRequest.getRequestBody()) || StringUtils.isEmpty(rocketMQRequest.getWorkAgentResponseTopic()) || StringUtils.isEmpty(rocketMQRequest.getLiteTopic()) || StringUtils.isEmpty(rocketMQRequest.getAgentTopic())) {
            log.error("serialText param error rocketMQRequest: {}", JSON.toJSONString(rocketMQRequest));
            return null;
        }
        return JSON.toJSONString(rocketMQRequest);
    }
}
