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
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, A2AResponseFuture>> MESSAGE_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, SSEEventListener>> MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* liteTopic */, Boolean>> LITE_TOPIC_USE_DEFAULT_RECOVER_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* namespace */, Map<String /* Key */, SSEEventListener>> RECOVER_MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    public static final ConcurrentMap<String /* taskId */, ServerReceiptInfo /* ServerInfo */> TASK_SERVER_RECEIPT_MAP = new ConcurrentHashMap<>();

    /**
     * Used to validate the parameters for initializing RocketMQTransport
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param workAgentResponseTopic A lightweight LiteTopic for clients to receive response results
     * @param workAgentResponseGroupID The CID used by the client to subscribe to the lightweight LiteTopic for response results
     * @param liteTopic todo
     * @param agentTopic The Normal Topic bound to the target Agent
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
     * Initialize and obtain the Producer under the corresponding namespace
     * @param namespace Used for logical isolation of different business units or environments
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param agentTopic The Normal Topic bound to the target Agent
     * @return Producer An initialized Producer instance
     * @throws ClientException RocketMQ ClientException
     */
    public static Producer initAndGetProducer(String namespace, String endpoint, String accessKey, String secretKey, String agentTopic) throws ClientException {
        if (null == namespace || StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(agentTopic)) {
            log.error("initAndGetProducer param error, namespace: {}, endpoint: {}, agentTopic: {}", namespace, endpoint, agentTopic);
        }
        Map<String, Producer> producerMap = ROCKETMQ_PRODUCER_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        return producerMap.computeIfAbsent(agentTopic, k -> {
            try {
                //create new producer
                return buildProducer(namespace, endpoint, accessKey, secretKey, k);
            } catch (ClientException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Init Producer
     * @param namespace Used for logical isolation of different business units or environments
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param topics The destination Topics to which the producer sends messages
     * @return producer
     * @throws ClientException RocketMQ ClientException
     */
    public static Producer buildProducer(String namespace, String endpoint, String accessKey, String secretKey, String... topics) throws ClientException {
        if (null == namespace || StringUtils.isEmpty(endpoint)) {
            log.error("buildProducer param error, endpoint: {}", endpoint);
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        //Configure authentication credentials
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        //Configure client parameters
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
     * Initialize and obtain the Consumer under the corresponding namespace
     * @param namespace Used for logical isolation of different business units or environments
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param workAgentResponseTopic A LiteTopic for clients to receive response results
     * @param workAgentResponseGroupID The CID used by the client to subscribe to the LiteTopic for response results
     * @param liteTopic todo
     * @return LitePushConsumer
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
                //create new consumer
                return buildConsumer(endpoint, namespace, accessKey, secretKey, workAgentResponseGroupID, workAgentResponseTopic);
            } catch (ClientException e) {
                log.error("RocketMQTransport initRocketMQProducerAndConsumer buildConsumer error: {}", e.getMessage());
                throw new RuntimeException(e);
            }
        });
        if (null != litePushConsumer) {
            //consumer sub the liteTopic
            litePushConsumer.subscribeLite(liteTopic);
        }
        return litePushConsumer;
    }

    /**
     * todo
     * Build Consumer
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param namespace Used for logical isolation of different business units or environments
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param workAgentResponseGroupID The CID used by the client to subscribe to the lightweight LiteTopic for response results
     * @param workAgentResponseTopic A lightweight LiteTopic for clients to receive response results
     * @return LitePushConsumer
     * @throws ClientException RocketMQ Client Exception
     */
    public static LitePushConsumer buildConsumer(String endpoint, String namespace, String accessKey, String secretKey, String workAgentResponseGroupID, String workAgentResponseTopic) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(workAgentResponseTopic)) {
            log.error("RocketMQTransport buildConsumer check param error");
            return null;
        }
        return buildConsumerForLite(endpoint, namespace, accessKey, secretKey, workAgentResponseGroupID, workAgentResponseTopic, buildClientMessageListener(namespace));
    }

    /**
     * todo
     * Build LitePushConsumer
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param namespace Used for logical isolation of different business units or environments
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param workAgentResponseGroupID The CID used by the client to subscribe to the lightweight LiteTopic for response results
     * @param workAgentResponseTopic A lightweight LiteTopic for clients to receive response results
     * @param messageListener Perform business logic processing on messages pulled by the consumer
     * @return LitePushConsumer
     * @throws ClientException RocketMQ Client Exception
     */
    public static LitePushConsumer buildConsumerForLite(String endpoint, String namespace, String accessKey, String secretKey, String workAgentResponseGroupID, String workAgentResponseTopic, MessageListener messageListener) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(workAgentResponseTopic) || null == messageListener) {
            log.error("RocketMQTransport buildConsumer check param error");
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        //Configure authentication credentials
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        //Configure client parameters
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
     * Build the client message listener
     * @param namespace Used for logical isolation of different business units or environments
     * @return MessageListener
     */
    private static MessageListener buildClientMessageListener(String namespace) {
        return messageView -> {
            try {
                //parse and obtain the liteTopic
                Optional<String> liteTopicOpt = messageView.getLiteTopic();
                String liteTopic = liteTopicOpt.get();
                if (StringUtils.isEmpty(liteTopic)) {
                    log.error("RocketMQTransport buildConsumer liteTopic is empty");
                    return ConsumeResult.SUCCESS;
                }
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                String resultStr = new String(result, StandardCharsets.UTF_8);
                //Deserialize the retrieved result into a RocketMQResponse
                RocketMQResponse response = JSON.parseObject(resultStr, RocketMQResponse.class);
                if (null == response || StringUtils.isEmpty(response.getMessageId())) {
                    log.error("RocketMQTransport litePushConsumer consumer error, response is null or messageId is empty");
                    return ConsumeResult.SUCCESS;
                }
                //Process non-streaming results
                if (!response.isStream()) {
                    return dealNonStreamResult(response, namespace);
                }
                //Process streaming results
                return dealStreamResult(response, namespace, liteTopic);
            } catch (Exception e) {
                log.error("RocketMQTransport litePushConsumer consumer error, msgId: {}, error: {}", messageView.getMessageId(), e.getMessage());
                return ConsumeResult.SUCCESS;
            }
        };
    }

    /**
     * Build PushConsumer
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param namespace Used for logical isolation of different business units or environments
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param bizGroup The CID of the consumer subscribed to the Standard bizTopic
     * @param bizTopic Standard bizTopic
     * @param messageListener Perform business logic processing on messages pulled by the consumer
     * @return PushConsumer
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
     * Build Message
     * @param topic 客户端轻量级LiteTopic
     * @param liteTopic todo
     * @param response todo
     * @return Message
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
     * Send a request that complies with the A2A protocol
     * @param payloadAndHeaders An encapsulated object containing the request payload and headers, typically used to transparently pass context or authentication information
     * @param agentTopic The Normal Topic bound to the target Agent
     * @param liteTopic todo
     * @param workAgentResponseTopic A lightweight LiteTopic for clients to receive response results
     * @param producer Normal RocketMQ Producer
     * @param taskId The id of the task
     * @return RocketMQ message id
     * @throws JsonProcessingException JsonProcess Exception
     */
    public static String sendRocketMQRequest(PayloadAndHeaders payloadAndHeaders, String agentTopic, String liteTopic, String workAgentResponseTopic, Producer producer, String taskId) throws JsonProcessingException {
        if (null == payloadAndHeaders || StringUtils.isEmpty(agentTopic) || StringUtils.isEmpty(liteTopic) || StringUtils.isEmpty(workAgentResponseTopic) || null == producer) {
            log.error("RocketMQTransport sendRocketMQRequest param error, payloadAndHeaders: {}, agentTopic: {}, workAgentResponseTopic: {}, liteTopic: {}, producer: {}", payloadAndHeaders, agentTopic, workAgentResponseTopic, liteTopic, producer);
            return null;
        }
        //build RocketMQRequest
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
        //Serialize the request
        String messageBodyStr = serialText(request);
        if (StringUtils.isEmpty(messageBodyStr)) {
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        byte[] body = messageBodyStr.getBytes(StandardCharsets.UTF_8);
        Message message = null;
        //Check whether the TaskId is null or empty, and whether the corresponding server receipt information is cached in TASK_SERVER_RECEIPT_MAP
        if (!StringUtils.isEmpty(taskId) && TASK_SERVER_RECEIPT_MAP.containsKey(taskId)) {
            ServerReceiptInfo serverReceiptInfo = TASK_SERVER_RECEIPT_MAP.get(taskId);
            //build message
            message = provider.newMessageBuilder().setTopic(serverReceiptInfo.getServerWorkAgentResponseTopic()).setLiteTopic(serverReceiptInfo.getServerLiteTopic()).setBody(body).build();
            log.info("send message to server liteTopic taskId: {}, serverReceiptInfo: {}", taskId, JSON.toJSONString(serverReceiptInfo));
        } else {
            //build message
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
     * Process streaming results
     * @param response The remote Agent returns an A2A-compliant response
     * @param namespace Used for logical isolation of different business units or environments
     * @param liteTopic todo
     * @return ConsumeResult
     */
    private static ConsumeResult dealStreamResult(RocketMQResponse response, String namespace, String liteTopic) {
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(liteTopic) || !response.isEnd() && StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealStreamResult param is error, response: {}, liteTopic: {}", JSON.toJSONString(response), liteTopic);
            return ConsumeResult.SUCCESS;
        }
        //Retrieve the default SSE listener map by namespace
        Map<String, SSEEventListener> sseEventListenerMap = MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
        if (null == sseEventListenerMap) {
            return ConsumeResult.SUCCESS;
        }
        //Retrieve the sseEventListener corresponding to the messageId from this sseEventListenerMap
        SSEEventListener sseEventListener = sseEventListenerMap.get(response.getMessageId());
        if (null == sseEventListener) {
            Map<String, Boolean> booleanMap = LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.get(namespace);
            if (null == booleanMap || !Boolean.TRUE.equals(booleanMap.get(liteTopic))) {
                return ConsumeResult.SUCCESS;
            }
            //"If the RECOVER_MESSAGE_STREAM_RESPONSE_MAP is not empty and contains this namespace
            if (!RECOVER_MESSAGE_STREAM_RESPONSE_MAP.isEmpty() && RECOVER_MESSAGE_STREAM_RESPONSE_MAP.containsKey(namespace)) {
                //Retrieve the corresponding sseEventListenerMap
                Map<String, SSEEventListener> sseEventListenerMapRecover = RECOVER_MESSAGE_STREAM_RESPONSE_MAP.get(namespace);
                if (null == sseEventListenerMapRecover) {
                    return ConsumeResult.SUCCESS;
                }
                //Retrieve the corresponding sseEventListener
                sseEventListener = sseEventListenerMapRecover.get(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER);
                if (null == sseEventListener) {
                    return ConsumeResult.SUCCESS;
                }
            }
            if (null == sseEventListener) {
                return ConsumeResult.SUCCESS;
            }
        }
        //Extract the corresponding ResponseBody from the response result
        String item = response.getResponseBody();
        if (!StringUtils.isEmpty(item) && item.startsWith(DATA_PREFIX)) {
            //data:
            item = item.substring(5).trim();
            if (!item.isEmpty()) {
                try {
                    //Pass the processed data to the sseEventListener for handling
                    sseEventListener.onMessage(item, new CompletableFuture<>());
                } catch (Throwable e) {
                    log.error("RocketMQTransport dealStreamResult error: {}", e.getMessage());
                    return ConsumeResult.FAILURE;
                }
            }
            //If the tag in the response indicates that this message is the last one for the task,
            //remove the SSE listener handler associated with the messageId from the sseEventListenerMap
            if (response.isEnd() && !StringUtils.isEmpty(response.getMessageId())) {
                sseEventListenerMap.remove(response.getMessageId());
            }
        }
        return ConsumeResult.SUCCESS;
    }

    /**
     * Process non-streaming results
     * @param response The remote Agent returns an A2A-compliant response
     * @param namespace Used for logical isolation of different business units or environments
     * @return ConsumeResult
     */
    private static ConsumeResult dealNonStreamResult(RocketMQResponse response, String namespace) {
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealNonStreamResult param is error, response: {}", JSON.toJSONString(response));
            return ConsumeResult.SUCCESS;
        }
        Map<String, A2AResponseFuture> msgIdAndAsyncTypedMap = MESSAGE_RESPONSE_MAP.get(namespace);
        if (null != msgIdAndAsyncTypedMap && msgIdAndAsyncTypedMap.containsKey(response.getMessageId())) {
            //get a2AResponseFuture from msgIdAndAsyncTypedMap by messageId
            A2AResponseFuture a2AResponseFuture = msgIdAndAsyncTypedMap.get(response.getMessageId());
            //"Call complete() on the CompletableFuture in a2AResponseFuture to synchronize the response result data
            a2AResponseFuture.getCompletableFuture().complete(response.getResponseBody());
            //When the type obtained from a2AResponseFuture.getTypeReference() is SEND_MESSAGE_RESPONSE_REFERENCE
            if (SEND_MESSAGE_RESPONSE_REFERENCE == a2AResponseFuture.getTypeReference()) {
                try {
                    //Deserialize the response result into a SendMessageResponse
                    SendMessageResponse sendMessageResponse = unmarshalResponse(response.getResponseBody(), SEND_MESSAGE_RESPONSE_REFERENCE);
                    Task result = (Task)sendMessageResponse.getResult();
                    //Use the taskId as the key and store the server's lightweight response topic along with the LiteTopic as the value in TASK_SERVER_RECEIPT_MAP
                    TASK_SERVER_RECEIPT_MAP.putIfAbsent(result.getId(), new ServerReceiptInfo(response.getServerWorkAgentResponseTopic(), response.getServerLiteTopic()));
                    log.info("dealNonStreamResult put task info when send message, taskId: {}, serverInfo: {}", result.getId(), JSON.toJSONString(TASK_SERVER_RECEIPT_MAP.get(result.getId())));
                } catch (JsonProcessingException e) {
                   log.error("dealNonStreamResult unmarshalResponse error: {}", e.getMessage());
                }
            //When the type obtained from a2AResponseFuture.getTypeReference() is CANCEL_TASK_RESPONSE_REFERENCE
            } else if (CANCEL_TASK_RESPONSE_REFERENCE == a2AResponseFuture.getTypeReference()) {
                try {
                    //Deserialize the response result into a CancelTaskResponse
                    CancelTaskResponse cancelTaskResponse = unmarshalResponse(response.getResponseBody(), CANCEL_TASK_RESPONSE_REFERENCE);
                    Task result = cancelTaskResponse.getResult();
                    //Remove the server-side data associated with the taskId key from TASK_SERVER_RECEIPT_MAP
                    ServerReceiptInfo remove = TASK_SERVER_RECEIPT_MAP.remove(result.getId());
                    log.info("dealNonStreamResult cancel task, taskId: {}, remove: {}", result.getId(), JSON.toJSONString(remove));
                } catch (JsonProcessingException e) {
                    log.error("dealNonStreamResult unmarshalResponse error: {}", e.getMessage());
                }
                //When the type obtained from a2AResponseFuture.getTypeReference() is SEND_MESSAGE_RESPONSE_REFERENCE
            } else if (GET_TASK_RESPONSE_REFERENCE == a2AResponseFuture.getTypeReference()) {
                try {
                    //Deserialize the response result into a GetTaskResponse
                    GetTaskResponse getTaskResponse = unmarshalResponse(response.getResponseBody(), GET_TASK_RESPONSE_REFERENCE);
                    TaskStatus status = getTaskResponse.getResult().getStatus();
                    //Remove the server-side data associated with the taskId key from TASK_SERVER_RECEIPT_MAP
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
     * Retrieve the corresponding result
     * @param responseMessageId The MessageId of the request being sent
     * @param namespace Used for logical isolation of different business units or environments
     * @param typeReference Generic type reference used for deserializing A2A protocol response results
     * @return String result
     * @throws ExecutionException ExecutionException
     * @throws InterruptedException InterruptedException
     * @throws TimeoutException TimeoutException
     */
    public static String getResult(String responseMessageId, String namespace, TypeReference typeReference) throws ExecutionException, InterruptedException, TimeoutException {
        if (StringUtils.isEmpty(responseMessageId)) {
            throw new RuntimeException("responseMessageId is null");
        }
        Map<String, A2AResponseFuture> msgIdAndAsyncTypedMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(namespace, k -> new HashMap<>());
        CompletableFuture<String> completableFuture = new CompletableFuture<>();
        msgIdAndAsyncTypedMap.put(responseMessageId, new A2AResponseFuture(completableFuture, typeReference));
        String result = completableFuture.get(120, TimeUnit.SECONDS);
        msgIdAndAsyncTypedMap.remove(responseMessageId);
        return result;
    }

    /**
     * Deserialize the response result
     * @param response response result String
     * @param typeReference Generic type reference used for deserializing A2A protocol response results
     * @return JSONRPCResponse
     * @throws A2AClientException A2AClientException
     * @throws JsonProcessingException JSONProcessingException
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
