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
package org.apache.rocketmq.a2a.transport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import io.a2a.client.transport.spi.ClientTransport;
import org.apache.rocketmq.a2a.common.RocketMQRequest;
import org.apache.rocketmq.a2a.common.RocketMQResponse;
import org.apache.rocketmq.a2a.common.RocketMQA2AConstant;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.transport.jsonrpc.sse.SSEEventListener;
import io.a2a.client.transport.spi.interceptors.ClientCallContext;
import io.a2a.client.transport.spi.interceptors.ClientCallInterceptor;
import io.a2a.client.transport.spi.interceptors.PayloadAndHeaders;
import io.a2a.spec.A2AClientError;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.CancelTaskResponse;
import io.a2a.spec.DeleteTaskPushNotificationConfigParams;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigResponse;
import io.a2a.spec.EventKind;
import io.a2a.spec.GetAuthenticatedExtendedCardRequest;
import io.a2a.spec.GetAuthenticatedExtendedCardResponse;
import io.a2a.spec.GetTaskPushNotificationConfigParams;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskPushNotificationConfigResponse;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.JSONRPCMessage;
import io.a2a.spec.JSONRPCResponse;
import io.a2a.spec.ListTaskPushNotificationConfigParams;
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.ListTaskPushNotificationConfigResponse;
import io.a2a.spec.MessageSendParams;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.SendStreamingMessageRequest;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.SetTaskPushNotificationConfigResponse;
import io.a2a.spec.StreamingEventKind;
import io.a2a.spec.Task;
import io.a2a.spec.TaskIdParams;
import io.a2a.spec.TaskPushNotificationConfig;
import io.a2a.spec.TaskQueryParams;
import io.a2a.util.Utils;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.DATA_PREFIX;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.HTTPS_URL_PREFIX;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.HTTP_URL_PREFIX;
import static io.a2a.util.Assert.checkNotNullParam;

public class RocketMQTransport implements ClientTransport {
    private static final Logger log = LoggerFactory.getLogger(RocketMQTransport.class);
    private static final TypeReference<SendMessageResponse> SEND_MESSAGE_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final TypeReference<GetTaskResponse> GET_TASK_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final TypeReference<CancelTaskResponse> CANCEL_TASK_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final TypeReference<GetTaskPushNotificationConfigResponse> GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final TypeReference<SetTaskPushNotificationConfigResponse> SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final TypeReference<ListTaskPushNotificationConfigResponse> LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final TypeReference<DeleteTaskPushNotificationConfigResponse> DELETE_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final TypeReference<GetAuthenticatedExtendedCardResponse> GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE = new TypeReference<>() { };
    private static final ConcurrentMap<String /* namespace */, Map<String /* WorkerAgentResponseTopic */, LitePushConsumer>> ROCKETMQ_CONSUMER_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String /* namespace */, Map<String /* agentTopic */, Producer>> ROCKETMQ_PRODUCER_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, CompletableFuture<String>>> MESSAGE_RESPONSE_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String /* namespace */, Map<String /* msgId */, SSEEventListener>> MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String /* namespace */, Map<String /* liteTopic */, Boolean>> LITE_TOPIC_USE_DEFAULT_RECOVER_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String /* namespace */, Map<String /* Key */, SSEEventListener>> RECOVER_MESSAGE_STREAM_RESPONSE_MAP = new ConcurrentHashMap<>();

    private final String agentTopic;
    private final String accessKey;
    private final String secretKey;
    private final String rocketMQEndpoint;
    private final String rocketNamespace;
    private final String workAgentResponseTopic;
    private final String workAgentResponseGroupID;
    private final List<ClientCallInterceptor> interceptors;
    private AgentCard agentCard;
    private final String agentUrl;
    private boolean useDefaultRecoverMode = false;
    private String liteTopic;
    private final A2AHttpClient httpClient;
    private boolean needsExtendedCard = false;
    private LitePushConsumer litePushConsumer;
    private Producer producer;

    public RocketMQTransport(String rocketNamespace, String accessKey, String secretKey, String workAgentResponseTopic, String workAgentResponseGroupID,
        List<ClientCallInterceptor> interceptors, String agentUrl, A2AHttpClient httpClient, String liteTopic, boolean useDefaultRecoverMode, AgentCard agentCard) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.workAgentResponseGroupID = workAgentResponseGroupID;
        this.interceptors = interceptors;
        this.agentUrl = agentUrl;
        this.httpClient = httpClient;
        this.liteTopic = liteTopic;
        if (StringUtils.isEmpty(this.liteTopic)) {
            this.liteTopic = UUID.randomUUID().toString();
        }
        this.useDefaultRecoverMode = useDefaultRecoverMode;
        this.agentCard = agentCard;
        RocketMQResourceInfo rocketAgentCardInfo = parseAgentCardAddition(this.agentCard);
        if (null == rocketAgentCardInfo) {
            throw new RuntimeException("RocketMQTransport rocketAgentCardInfo pare error");
        }
        if (null != rocketNamespace && !rocketNamespace.equals(rocketAgentCardInfo.getNamespace())) {
            throw new RuntimeException("RocketMQTransport rocketAgentCardInfo namespace do not match, please check the config info");
        }
        this.rocketMQEndpoint = rocketAgentCardInfo.getEndpoint();
        this.agentTopic = rocketAgentCardInfo.getTopic();
        this.rocketNamespace = StringUtils.isEmpty(rocketAgentCardInfo.getNamespace()) ? "" : rocketAgentCardInfo.getNamespace();
        LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>()).put(this.liteTopic, useDefaultRecoverMode);
        checkConfigParam();
        initRocketMQProducerAndConsumer();
    }

    private void initRocketMQProducerAndConsumer() {
        if (StringUtils.isEmpty(this.rocketMQEndpoint) || StringUtils.isEmpty(this.workAgentResponseTopic) || StringUtils.isEmpty(this.liteTopic)) {
            throw new A2AClientException("RocketMQTransport initRocketMQProducerAndConsumer param error");
        }
        try {
            Map<String, LitePushConsumer> consumerMap = ROCKETMQ_CONSUMER_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            if (consumerMap.containsKey(this.workAgentResponseTopic)) {
                this.litePushConsumer = consumerMap.get(this.workAgentResponseTopic);
                this.litePushConsumer.subscribeLite(this.liteTopic);
            } else {
                LitePushConsumer litePushConsumer = consumerMap.computeIfAbsent(this.workAgentResponseTopic, k -> {
                    try {
                        return buildConsumer();
                    } catch (ClientException e) {
                        log.error("RocketMQTransport initRocketMQProducerAndConsumer buildConsumer error: {}", e.getMessage());
                        throw new RuntimeException(e);
                    }
                });
                if (null != litePushConsumer) {
                    litePushConsumer.subscribeLite(this.liteTopic);
                    this.litePushConsumer = litePushConsumer;
                }
            }
            Map<String, Producer> producerMap = ROCKETMQ_PRODUCER_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            if (!producerMap.containsKey(this.agentTopic)) {
                this.producer = buildProducer(this.agentTopic);
                producerMap.put(this.agentTopic, this.producer);
            }
            log.info("RocketMQTransport initRocketMQProducerAndConsumer success");
        } catch (Exception e) {
            log.info("RocketMQTransport initRocketMQProducerAndConsumer error: {}", e.getMessage());
        }
    }

    @Override
    public EventKind sendMessage(MessageSendParams request, ClientCallContext context) throws A2AClientException {
        SendMessageRequest sendMessageRequest = new SendMessageRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(SendMessageRequest.METHOD)
            .params(request)
            .build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(SendMessageRequest.METHOD, sendMessageRequest, this.agentCard, context);
        try {
            String liteTopic = dealLiteTopic(request.message().getContextId());
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport sendMessage error, responseMessageId is null");
                return null;
            }
            Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
            completableFutureMap.put(responseMessageId, objectCompletableFuture);
            String result = objectCompletableFuture.get(120, TimeUnit.SECONDS);
            completableFutureMap.remove(responseMessageId);
            SendMessageResponse response = unmarshalResponse(String.valueOf(result), SEND_MESSAGE_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport sendMessage error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void sendMessageStreaming(MessageSendParams request, Consumer<StreamingEventKind> eventConsumer, Consumer<Throwable> errorConsumer, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        checkNotNullParam("eventConsumer", eventConsumer);
        SendStreamingMessageRequest sendStreamingMessageRequest = new SendStreamingMessageRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(SendStreamingMessageRequest.METHOD)
            .params(request)
            .build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(SendStreamingMessageRequest.METHOD, sendStreamingMessageRequest, this.agentCard, context);
        SSEEventListener sseEventListener = new SSEEventListener(eventConsumer, errorConsumer);
        try {
            String liteTopic = dealLiteTopic(request.message().getContextId());
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport sendMessageStreaming error, responseMessageId is null");
                return;
            }
            MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>()).put(responseMessageId, sseEventListener);
            log.info("RocketMQTransport sendMessageStreaming success, responseMessageId: {}", responseMessageId);
        } catch (Exception e) {
            throw new A2AClientException("RocketMQTransport Failed to send streaming message request: " + e, e);
        }
    }

    @Override
    public void resubscribe(TaskIdParams request, Consumer<StreamingEventKind> eventConsumer, Consumer<Throwable> errorConsumer, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        checkNotNullParam("eventConsumer", eventConsumer);
        checkNotNullParam("errorConsumer", errorConsumer);
        SSEEventListener sseEventListener = new SSEEventListener(eventConsumer, errorConsumer);
        try {
            if (null != request.metadata()) {
                String responseMessageId = (String)request.metadata().get(RocketMQA2AConstant.MESSAGE_RESPONSE_ID);
                if (!StringUtils.isEmpty(responseMessageId)) {
                    MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>()).put(responseMessageId, sseEventListener);
                }
                String liteTopic = (String)request.metadata().get(RocketMQA2AConstant.LITE_TOPIC);
                if (null != litePushConsumer && !StringUtils.isEmpty(liteTopic)) {
                    litePushConsumer.subscribeLite(liteTopic);
                    log.info("litePushConsumer subscribeLite liteTopic: {}", liteTopic);
                    LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>()).put(liteTopic, this.useDefaultRecoverMode);
                }

                String closeLiteTopic = (String)request.metadata().get(RocketMQA2AConstant.CLOSE_LITE_TOPIC);
                if (null != litePushConsumer && !StringUtils.isEmpty(closeLiteTopic)) {
                    litePushConsumer.unsubscribeLite(closeLiteTopic);
                    log.info("litePushConsumer unsubscribeLite liteTopic: {}", closeLiteTopic);
                    LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>()).remove(closeLiteTopic);
                }
            }
            if (this.useDefaultRecoverMode) {
                RECOVER_MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(rocketNamespace, k -> new HashMap<>()).put(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER, sseEventListener);
            }
        } catch (Exception e) {
            throw new A2AClientException("RocketMQTransport failed to resubscribe streaming message request: " + e, e);
        }
    }

    @Override
    public Task getTask(TaskQueryParams request, ClientCallContext context) throws A2AClientException {
        GetTaskRequest getTaskRequest = new GetTaskRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(GetTaskRequest.METHOD)
            .params(request)
            .build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetTaskRequest.METHOD, getTaskRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport getTask error, responseMessageId is null");
                return null;
            }
            Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
            completableFutureMap.put(responseMessageId, objectCompletableFuture);
            String result = objectCompletableFuture.get(120, TimeUnit.SECONDS);
            completableFutureMap.remove(responseMessageId);
            GetTaskResponse response = unmarshalResponse(result, GET_TASK_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport getTask error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Task cancelTask(TaskIdParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        CancelTaskRequest cancelTaskRequest = new CancelTaskRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(CancelTaskRequest.METHOD)
            .params(request)
            .build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(CancelTaskRequest.METHOD, cancelTaskRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport cancelTask error, responseMessageId is null");
                return null;
            }
            Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
            completableFutureMap.put(responseMessageId, objectCompletableFuture);
            String result = objectCompletableFuture.get(120, TimeUnit.SECONDS);
            completableFutureMap.remove(responseMessageId);
            CancelTaskResponse response = unmarshalResponse(result, CANCEL_TASK_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport cancelTask error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public TaskPushNotificationConfig setTaskPushNotificationConfiguration(TaskPushNotificationConfig request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        SetTaskPushNotificationConfigRequest setTaskPushNotificationRequest = new SetTaskPushNotificationConfigRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(SetTaskPushNotificationConfigRequest.METHOD)
            .params(request)
            .build();

        PayloadAndHeaders payloadAndHeaders = applyInterceptors(SetTaskPushNotificationConfigRequest.METHOD, setTaskPushNotificationRequest, agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport setTaskPushNotificationConfiguration error, responseMessageId is null");
                return null;
            }
            Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
            completableFutureMap.put(responseMessageId, objectCompletableFuture);
            String result = objectCompletableFuture.get(120, TimeUnit.SECONDS);
            completableFutureMap.remove(responseMessageId);
            SetTaskPushNotificationConfigResponse response = unmarshalResponse(result, SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport setTaskPushNotificationConfiguration error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public TaskPushNotificationConfig getTaskPushNotificationConfiguration(GetTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        GetTaskPushNotificationConfigRequest getTaskPushNotificationRequest
            = new GetTaskPushNotificationConfigRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(GetTaskPushNotificationConfigRequest.METHOD)
            .params(request)
            .build();

        PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetTaskPushNotificationConfigRequest.METHOD, getTaskPushNotificationRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport getTaskPushNotificationConfiguration error, responseMessageId is null");
                return null;
            }
            Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            CompletableFuture<String> completableFuture = new CompletableFuture<>();
            completableFutureMap.put(responseMessageId, completableFuture);
            String result = completableFuture.get(120, TimeUnit.SECONDS);
            completableFutureMap.remove(responseMessageId);
            GetTaskPushNotificationConfigResponse response = unmarshalResponse(result, GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport getTaskPushNotificationConfiguration error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public List<TaskPushNotificationConfig> listTaskPushNotificationConfigurations(ListTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        ListTaskPushNotificationConfigRequest listTaskPushNotificationRequest = new ListTaskPushNotificationConfigRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(ListTaskPushNotificationConfigRequest.METHOD)
            .params(request)
            .build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(ListTaskPushNotificationConfigRequest.METHOD, listTaskPushNotificationRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport listTaskPushNotificationConfigurations error, responseMessageId is null");
                return null;
            }
            Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
            completableFutureMap.put(responseMessageId, objectCompletableFuture);
            String result = objectCompletableFuture.get(120, TimeUnit.SECONDS);
            completableFutureMap.remove(responseMessageId);
            ListTaskPushNotificationConfigResponse response = unmarshalResponse(result, LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport listTaskPushNotificationConfigurations error: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public void deleteTaskPushNotificationConfigurations(DeleteTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        DeleteTaskPushNotificationConfigRequest deleteTaskPushNotificationRequest = new DeleteTaskPushNotificationConfigRequest.Builder()
            .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
            .method(DeleteTaskPushNotificationConfigRequest.METHOD)
            .params(request)
            .build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(DeleteTaskPushNotificationConfigRequest.METHOD, deleteTaskPushNotificationRequest, agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.liteTopic);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport deleteTaskPushNotificationConfigurations error, responseMessageId is null");
                return;
            }
            Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
            CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
            completableFutureMap.put(responseMessageId, objectCompletableFuture);
            objectCompletableFuture.get(120, TimeUnit.SECONDS);
            completableFutureMap.remove(responseMessageId);
        } catch (Exception e) {
            log.error("RocketMQTransport deleteTaskPushNotificationConfigurations error: {}", e.getMessage());
        }
    }

    @Override
    public AgentCard getAgentCard(ClientCallContext context) throws A2AClientException {
        A2ACardResolver resolver;
        try {
            if (agentCard == null) {
                resolver = new A2ACardResolver(httpClient, agentUrl, null, getHttpHeaders(context));
                agentCard = resolver.getAgentCard();
                needsExtendedCard = agentCard.supportsAuthenticatedExtendedCard();
            }
            if (!needsExtendedCard) {
                return agentCard;
            }
            try {
                GetAuthenticatedExtendedCardRequest getExtendedAgentCardRequest = new GetAuthenticatedExtendedCardRequest.Builder()
                    .jsonrpc(JSONRPCMessage.JSONRPC_VERSION)
                    .method(GetAuthenticatedExtendedCardRequest.METHOD)
                    .build(); // id will be randomly generated
                PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetAuthenticatedExtendedCardRequest.METHOD, getExtendedAgentCardRequest, this.agentCard, context);
                String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.liteTopic);
                if (StringUtils.isEmpty(responseMessageId)) {
                    log.error("RocketMQTransport getAgentCard responseMessageId is null");
                    return null;
                }
                Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.computeIfAbsent(this.rocketNamespace, k -> new HashMap<>());
                CompletableFuture<String> objectCompletableFuture = new CompletableFuture<>();
                completableFutureMap.put(responseMessageId, objectCompletableFuture);
                String result = objectCompletableFuture.get(120, TimeUnit.SECONDS);
                completableFutureMap.remove(responseMessageId);
                GetAuthenticatedExtendedCardResponse response = unmarshalResponse(result, GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE);
                return response.getResult();
            } catch (Exception e) {
                throw new A2AClientException("RocketMQTransport getAgentCard error: " + e, e);
            }
        } catch (A2AClientError e) {
            throw new A2AClientException("RocketMQTransport getAgentCard error: " + e, e);
        }
    }

    @Override
    public void close() {
        try {
            if (null != this.producer) {
                this.producer.close();
            }
            if (null != this.litePushConsumer) {
                this.litePushConsumer.close();
            }
            log.info("RocketMQTransport close success");
        } catch (Exception e) {
            log.error("RocketMQTransport close error: {}", e.getMessage());
        }
    }

    private void checkConfigParam() {
        if (StringUtils.isEmpty(this.rocketMQEndpoint) || StringUtils.isEmpty(this.workAgentResponseTopic) ||
            StringUtils.isEmpty(this.workAgentResponseGroupID) || StringUtils.isEmpty(this.liteTopic) || StringUtils.isEmpty(agentTopic)) {

            if (StringUtils.isEmpty(this.rocketMQEndpoint)) {
                log.info("RocketMQTransport checkConfigParam rocketMQEndpoint is empty");
            }
            if (StringUtils.isEmpty(this.workAgentResponseTopic)) {
                log.info("RocketMQTransport checkConfigParam workAgentResponseTopic is empty");
            }
            if (StringUtils.isEmpty(this.workAgentResponseGroupID)) {
                log.info("RocketMQTransport checkConfigParam workAgentResponseGroupID is empty");
            }
            if (StringUtils.isEmpty(this.liteTopic)) {
                log.info("RocketMQTransport checkConfigParam liteTopic is empty");
            }
            if (StringUtils.isEmpty(this.agentTopic)) {
                log.info("RocketMQTransport checkConfigParam agentTopic is empty");
            }
            throw new RuntimeException("RocketMQTransport checkConfigParam error, init failed !!!");
        }
    }

    private String dealLiteTopic(String contextId) {
        String liteTopic = this.liteTopic;
        if (!StringUtils.isEmpty(contextId)) {
            try {
                litePushConsumer.subscribeLite(contextId);
                liteTopic = contextId;
            } catch (ClientException e) {

            }
        }
        return liteTopic;
    }

    private LitePushConsumer buildConsumer() throws ClientException {
        if (StringUtils.isEmpty(this.rocketMQEndpoint) || StringUtils.isEmpty(this.workAgentResponseGroupID) || StringUtils.isEmpty(this.workAgentResponseTopic)) {
            log.error("RocketMQTransport buildConsumer check param error");
            return null;
        }
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(this.rocketMQEndpoint)
            .setNamespace(this.rocketNamespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .build();
        LitePushConsumer litePushConsumer = provider.newLitePushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(this.workAgentResponseGroupID)
            .bindTopic(this.workAgentResponseTopic)
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
                        return dealNonStreamResult(response, this.rocketNamespace);
                    }
                    return dealStreamResult(response, this.rocketNamespace, liteTopic);
                } catch (Exception e) {
                    log.error("RocketMQTransport litePushConsumer consumer error, msgId: {}, error: {}", messageView.getMessageId(), e.getMessage());
                    return ConsumeResult.SUCCESS;
                }
            }).build();
        return litePushConsumer;
    }

    private ConsumeResult dealStreamResult(RocketMQResponse response, String rocketMQNamespace, String liteTopic) {
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(liteTopic) || !response.isEnd() && StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealStreamResult param is error, response: {}, liteTopic: {}", JSON.toJSONString(response), liteTopic);
            return ConsumeResult.SUCCESS;
        }

        Map<String, SSEEventListener> sseEventListenerMap = MESSAGE_STREAM_RESPONSE_MAP.get(rocketMQNamespace);
        if (null == sseEventListenerMap) {
            log.error("RocketMQTransport dealStreamResult sseEventListenerMap is null");
            return ConsumeResult.SUCCESS;
        }
        SSEEventListener sseEventListener = sseEventListenerMap.get(response.getMessageId());
        if (null == sseEventListener) {
            Map<String, Boolean> booleanMap = LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.get(rocketMQNamespace);
            if (null == booleanMap) {
                log.error("RocketMQTransport dealStreamResult booleanMap is null");
                return ConsumeResult.SUCCESS;
            }
            Boolean useDefaultRecoverModeConsumer = booleanMap.get(liteTopic);
            if (null == useDefaultRecoverModeConsumer || !useDefaultRecoverModeConsumer) {
                return ConsumeResult.SUCCESS;
            }
            if (!RECOVER_MESSAGE_STREAM_RESPONSE_MAP.isEmpty() && RECOVER_MESSAGE_STREAM_RESPONSE_MAP.containsKey(rocketMQNamespace)) {
                Map<String, SSEEventListener> sseEventListenerMapRecover = RECOVER_MESSAGE_STREAM_RESPONSE_MAP.get(rocketMQNamespace);
                if (null == sseEventListenerMapRecover) {
                    log.error("RocketMQTransport dealStreamResult sseEventListenerMapRecover is null");
                    return ConsumeResult.SUCCESS;
                }
                sseEventListener = sseEventListenerMapRecover.get(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER);
                if (null == sseEventListener) {
                    log.error("RocketMQTransport dealStreamResult sseEventListenerMapRecover get sseEventListener is null");
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
                MESSAGE_STREAM_RESPONSE_MAP.remove(response.getMessageId());
            }
        }
        return ConsumeResult.SUCCESS;
    }

    private ConsumeResult dealNonStreamResult(RocketMQResponse response, String rocketMQNamespace) {
        if (null == response || StringUtils.isEmpty(response.getMessageId()) || StringUtils.isEmpty(response.getResponseBody())) {
            log.error("RocketMQTransport dealNonStreamResult param is error, response: {}", JSON.toJSONString(response));
            return ConsumeResult.SUCCESS;
        }
        Map<String, CompletableFuture<String>> completableFutureMap = MESSAGE_RESPONSE_MAP.get(rocketMQNamespace);
        if (null != completableFutureMap && completableFutureMap.containsKey(response.getMessageId())) {
            CompletableFuture<String> completableFuture = completableFutureMap.get(response.getMessageId());
            completableFuture.complete(response.getResponseBody());
        }
        return ConsumeResult.SUCCESS;
    }

    private String sendRocketMQRequest(PayloadAndHeaders payloadAndHeaders, String liteTopic) throws JsonProcessingException {
        if (null == payloadAndHeaders || StringUtils.isEmpty(this.agentTopic) || StringUtils.isEmpty(liteTopic) || StringUtils.isEmpty(this.workAgentResponseTopic)) {
            log.error("RocketMQTransport sendRocketMQRequest error, payloadAndHeaders: {}, agentTopic: {}, workAgentResponseTopic: {}, liteTopic: {}", payloadAndHeaders, this.agentTopic, this.workAgentResponseTopic, this.liteTopic);
            return null;
        }
        RocketMQRequest request = new RocketMQRequest();
        request.setRequestBody(Utils.OBJECT_MAPPER.writeValueAsString(payloadAndHeaders.getPayload()));
        request.setAgentTopic(this.agentTopic);
        request.setWorkAgentResponseTopic(this.workAgentResponseTopic);
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
        if (null == producer) {
            log.error("RocketMQTransport sendRocketMQRequest producer is null, agentTopic: {}", this.agentTopic);
            return null;
        }
        byte[] body = messageBodyStr.getBytes(StandardCharsets.UTF_8);
        final Message message = provider.newMessageBuilder()
            .setTopic(this.agentTopic)
            .setBody(body)
            .build();
        try {
            final SendReceipt sendReceipt = producer.send(message);
            if (!StringUtils.isEmpty(sendReceipt.getMessageId().toString())) {
                return sendReceipt.getMessageId().toString();
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }
    private static void printPrompt(String role) {
        System.out.print("\n\u001B[36m" + role + " > \u001B[0m");
    }

    private <T extends JSONRPCResponse<?>> T unmarshalResponse(String response, TypeReference<T> typeReference)
        throws A2AClientException, JsonProcessingException {
        T value = Utils.unmarshalFrom(response, typeReference);
        JSONRPCError error = value.getError();
        if (error != null) {
            throw new A2AClientException(error.getMessage() + (error.getData() != null ? ": " + error.getData() : ""), error);
        }
        return value;
    }

    private static String serialText(RocketMQRequest rocketMQRequest) {
        if (null == rocketMQRequest || StringUtils.isEmpty(rocketMQRequest.getRequestBody()) || StringUtils.isEmpty(rocketMQRequest.getWorkAgentResponseTopic()) || StringUtils.isEmpty(rocketMQRequest.getLiteTopic()) || StringUtils.isEmpty(rocketMQRequest.getAgentTopic())) {
            return null;
        }
        return JSON.toJSONString(rocketMQRequest);
    }

    private Producer buildProducer(String... topics) throws ClientException {
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(this.rocketMQEndpoint)
            .setNamespace(this.rocketNamespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .setRequestTimeout(Duration.ofSeconds(15))
            .build();
        final ProducerBuilder builder = provider.newProducerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setTopics(topics);
        return builder.build();
    }

    private PayloadAndHeaders applyInterceptors(String methodName, Object payload, AgentCard agentCard, ClientCallContext clientCallContext) {
        PayloadAndHeaders payloadAndHeaders = new PayloadAndHeaders(payload, getHttpHeaders(clientCallContext));
        if (interceptors != null && !interceptors.isEmpty()) {
            for (ClientCallInterceptor interceptor : interceptors) {
                payloadAndHeaders = interceptor.intercept(methodName, payloadAndHeaders.getPayload(), payloadAndHeaders.getHeaders(), agentCard, clientCallContext);
            }
        }
        return payloadAndHeaders;
    }

    private Map<String, String> getHttpHeaders(@Nullable ClientCallContext context) {
        return context != null ? context.getHeaders() : Collections.emptyMap();
    }

    private RocketMQResourceInfo parseAgentCardAddition(AgentCard agentCard) {
        if (null == agentCard || StringUtils.isEmpty(agentCard.preferredTransport()) || StringUtils.isEmpty(agentCard.url()) || null == agentCard.additionalInterfaces() || agentCard.additionalInterfaces().isEmpty()) {
            log.info("parseAgentCardAddition param error, agentCard: {}", JSON.toJSONString(agentCard));
            return null;
        }
        RocketMQResourceInfo rocketMQResourceInfo = null;
        String preferredTransport = agentCard.preferredTransport();
        if (RocketMQA2AConstant.ROCKETMQ_PROTOCOL.equals(preferredTransport)) {
            String url = agentCard.url();
            rocketMQResourceInfo = pareAgentCardUrl(url);
            if (null != rocketMQResourceInfo && !StringUtils.isEmpty(rocketMQResourceInfo.getEndpoint()) && !StringUtils.isEmpty(
                rocketMQResourceInfo.getTopic())) {
                log.info("RocketMQTransport get rocketMQResourceInfo from preferredTransport");
                return rocketMQResourceInfo;
            }
        }
        List<AgentInterface> agentInterfaces = agentCard.additionalInterfaces();
        for (AgentInterface agentInterface : agentInterfaces) {
            String transport = agentInterface.transport();
            if (!StringUtils.isEmpty(transport) && RocketMQA2AConstant.ROCKETMQ_PROTOCOL.equals(transport)) {
                String url = agentInterface.url();
                rocketMQResourceInfo = pareAgentCardUrl(url);
                if (null != rocketMQResourceInfo && !StringUtils.isEmpty(rocketMQResourceInfo.getEndpoint()) && !StringUtils.isEmpty(
                    rocketMQResourceInfo.getTopic())) {
                    log.info("RocketMQTransport get rocketMQResourceInfo from additionalInterfaces");
                    return rocketMQResourceInfo;
                }
            }
        }
        return null;
    }

    private static RocketMQResourceInfo pareAgentCardUrl(String agentCardUrl) {
        if (StringUtils.isEmpty(agentCardUrl)) {
            return null;
        }
        String agentUrl = agentCardUrl.replace(HTTP_URL_PREFIX, "");
        String replaceFinal = agentUrl.replace(HTTPS_URL_PREFIX, "");
        String[] split = replaceFinal.split("/");
        if (split.length != 3) {
            return null;
        }
        RocketMQResourceInfo rocketMQResourceInfo = new RocketMQResourceInfo();
        rocketMQResourceInfo.setEndpoint(split[0].trim());
        rocketMQResourceInfo.setNamespace(split[1].trim());
        rocketMQResourceInfo.setTopic(split[2].trim());
        return rocketMQResourceInfo;
    }

    private static class RocketMQResourceInfo {
        private String endpoint;
        private String topic;
        private String namespace;

        public RocketMQResourceInfo(String endpoint, String topic) {
            this.endpoint = endpoint;
            this.topic = topic;
        }

        public RocketMQResourceInfo() {}

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }

}
