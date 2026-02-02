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
package org.apache.rocketmq.a2a.transport.impl;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import io.a2a.client.transport.spi.ClientTransport;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.rocketmq.a2a.common.model.RocketMQResourceInfo;
import org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.transport.jsonrpc.sse.SSEEventListener;
import io.a2a.client.transport.spi.interceptors.ClientCallContext;
import io.a2a.client.transport.spi.interceptors.ClientCallInterceptor;
import io.a2a.client.transport.spi.interceptors.PayloadAndHeaders;
import io.a2a.spec.A2AClientError;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.CancelTaskResponse;
import io.a2a.spec.DeleteTaskPushNotificationConfigParams;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.EventKind;
import io.a2a.spec.GetAuthenticatedExtendedCardRequest;
import io.a2a.spec.GetAuthenticatedExtendedCardResponse;
import io.a2a.spec.GetTaskPushNotificationConfigParams;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskPushNotificationConfigResponse;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.JSONRPCMessage;
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
import org.apache.rocketmq.a2a.transport.config.RocketMQTransportConfig;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.a2a.util.Assert.checkNotNullParam;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.CANCEL_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.GET_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.SEND_MESSAGE_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.LITE_TOPIC_USE_DEFAULT_RECOVER_MAP;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.MESSAGE_STREAM_RESPONSE_MAP;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.RECOVER_MESSAGE_STREAM_RESPONSE_MAP;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.checkConfigParam;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.getResult;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.getOrCreateLitePushConsumer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.getOrCreateProducer;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.parseAgentCardAddition;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.sendRocketMQRequest;
import static org.apache.rocketmq.a2a.common.uitl.RocketMQUtil.unmarshalResponse;

/**
 * A RocketMQ-based implementation of the {@link ClientTransport} interface for A2A protocol communication.
 * <p>
 * This transport enables clients to send both streaming and non-streaming JSON-RPC requests to remote agents over RocketMQ.
 */
public class RocketMQTransport implements ClientTransport {
    private static final Logger log = LoggerFactory.getLogger(RocketMQTransport.class);

    /**
     * The RocketMQ topic associated with the target agent, used as the destination for client requests.
     */
    private final String agentTopic;

    /**
     * The namespace used for logical isolation of RocketMQ resources.
     */
    private final String namespace;

    /**
     * The lightweight topic used to receive asynchronous replies.
     */
    private final String workAgentResponseTopic;

    /**
     * Typically, a liteTopic that is bound to {@link #workAgentResponseTopic}.
     * LiteTopic is a session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     */
    private String liteTopic;

    /**
     * A list of interceptors applied to outgoing requests before transmission.
     */
    private final List<ClientCallInterceptor> interceptors;

    /**
     * The agent's identity and metadata, including embedded RocketMQ resource information.
     */
    private AgentCard agentCard;

    /**
     * The HTTP URL where the agent's metadata service is exposed.
     * Used primarily for initial {@link AgentCard} resolution.
     */
    private final String agentUrl;

    /**
     * Indicates whether the default message recovery mode should be enabled for streaming responses.
     */
    private boolean useDefaultRecoverMode = false;

    /**
     * The HTTP client used for resolving the agent's {@link AgentCard} via its metadata endpoint.
     */
    private final A2AHttpClient httpClient;

    private boolean needsExtendedCard = false;

    /**
     * The RocketMQ consumer used to receive response messages on lite topics.
     */
    private final LitePushConsumer litePushConsumer;

    /**
     * The RocketMQ producer used to send request messages to the agent's topic.
     */
    private final Producer producer;

    /**
     * Constructs a new {@link RocketMQTransport} instance.
     * @param rocketMQTransportConfig Configuration class for RocketMQTransport.
     * @param agentCard the agent's identity and embedded RocketMQ resource info.
     */
    public RocketMQTransport(RocketMQTransportConfig rocketMQTransportConfig, AgentCard agentCard) {
        this.workAgentResponseTopic = rocketMQTransportConfig.getWorkAgentResponseTopic();
        this.interceptors = rocketMQTransportConfig.getInterceptors();
        this.agentUrl = rocketMQTransportConfig.getAgentUrl();
        this.httpClient = rocketMQTransportConfig.getHttpClient();
        this.liteTopic = rocketMQTransportConfig.getLiteTopic();
        this.useDefaultRecoverMode = rocketMQTransportConfig.isUseDefaultRecoverMode();
        this.agentCard = agentCard;
        // Generate a random lite topic if none is provided
        this.liteTopic = StringUtils.isEmpty(liteTopic) ? UUID.randomUUID().toString() : liteTopic;
        // Parse RocketMQ resource info from the agent card
        RocketMQResourceInfo rocketMQAgentCardInfo = parseAgentCardAddition(this.agentCard);
        if (null == rocketMQAgentCardInfo) {
            throw new IllegalArgumentException("RocketMQTransport failed to parse RocketMQResourceInfo from AgentCard");
        }
        if (null != rocketMQTransportConfig.getNamespace() && !rocketMQTransportConfig.getNamespace().equals(rocketMQAgentCardInfo.getNamespace())) {
            throw new IllegalArgumentException("RocketMQTransport namespace don't match, please check the config info");
        }
        this.agentTopic = rocketMQAgentCardInfo.getTopic();
        this.namespace = StringUtils.isEmpty(rocketMQAgentCardInfo.getNamespace()) ? "" : rocketMQAgentCardInfo.getNamespace();
        // Register recovery mode preference for this lite topic
        LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.namespace, k -> new ConcurrentHashMap<>()).put(this.liteTopic, useDefaultRecoverMode);
        String endpoint = rocketMQAgentCardInfo.getEndpoint();
        // Validate required configuration parameters
        checkConfigParam(endpoint, this.workAgentResponseTopic, rocketMQTransportConfig.getWorkAgentResponseGroupID(), this.liteTopic, this.agentTopic);
        this.litePushConsumer = getOrCreateLitePushConsumer(this.namespace, endpoint, rocketMQTransportConfig.getAccessKey(), rocketMQTransportConfig.getSecretKey(), this.workAgentResponseTopic, rocketMQTransportConfig.getWorkAgentResponseGroupID(), this.liteTopic);
        this.producer = getOrCreateProducer(this.namespace, endpoint, rocketMQTransportConfig.getAccessKey(), rocketMQTransportConfig.getSecretKey(), this.agentTopic);
    }

    /**
     * Sends a non-streaming JSON-RPC request to the remote agent and waits for the response.
     *
     * @param request the message send parameters.
     * @param context optional client call context.
     * @return the result of the operation, or {@code null} if an error occurred.
     * @throws A2AClientException if the request fails due to transport or protocol issues.
     */
    @Override
    public EventKind sendMessage(MessageSendParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        checkNotNullParam("request.message", request.message());
        SendMessageRequest sendMessageRequest = new SendMessageRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(SendMessageRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(SendMessageRequest.METHOD, sendMessageRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, resolveLiteTopic(request.message().getContextId()), this.workAgentResponseTopic, this.producer, null);
            SendMessageResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, SEND_MESSAGE_RESPONSE_REFERENCE), SEND_MESSAGE_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport sendMessage error", e);
            throw new A2AClientException("RocketMQTransport sendMessage error", e);
        }
    }

    /**
     * Sends a streaming JSON-RPC request to the remote agent and registers listeners for events and errors.
     *
     * @param request the message send parameters.
     * @param eventConsumer the consumer for incoming streaming events.
     * @param errorConsumer the consumer for error notifications.
     * @param context optional client call context.
     * @throws A2AClientException if the request fails to be sent.
     */
    @Override
    public void sendMessageStreaming(MessageSendParams request, Consumer<StreamingEventKind> eventConsumer,
        Consumer<Throwable> errorConsumer, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        checkNotNullParam("request.message", request.message());
        checkNotNullParam("eventConsumer", eventConsumer);
        try {
            SendStreamingMessageRequest sendStreamingMessageRequest = new SendStreamingMessageRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(SendStreamingMessageRequest.METHOD).params(request).build();
            PayloadAndHeaders payloadAndHeaders = applyInterceptors(SendStreamingMessageRequest.METHOD, sendStreamingMessageRequest, this.agentCard, context);
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, resolveLiteTopic(request.message().getContextId()), this.workAgentResponseTopic, this.producer, null);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport sendMessageStreaming error, responseMessageId is empty");
                return;
            }
            MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(this.namespace, k -> new ConcurrentHashMap<>()).put(responseMessageId, new SSEEventListener(eventConsumer, errorConsumer));
            log.debug("RocketMQTransport sendMessageStreaming success, responseMessageId: [{}]", responseMessageId);
        } catch (Exception e) {
            log.error("RocketMQTransport sendMessageStreaming error", e);
            throw new A2AClientException("RocketMQTransport sendMessageStreaming error", e);
        }
    }

    /**
     * Resubscribes to or unsubscribes from a streaming session based on metadata in the request.
     * <p>
     * In this method, subscription behavior is controlled by setting key-value pairs in the TaskIdParams. metadata map:
     * To resubscribe to a LiteTopic, use {@link RocketMQA2AConstant#SUB_LITE_TOPIC} as the key and the target LiteTopic as the value.
     * To unsubscribe from a LiteTopic, use {@link RocketMQA2AConstant#UNSUB_LITE_TOPIC} as the key and the target LiteTopic as the value.
     *
     * @param request the task ID parameters containing subscription metadata.
     * @param eventConsumer the consumer for streaming events.
     * @param errorConsumer the consumer for errors.
     * @param context optional client call context.
     * @throws A2AClientException if resubscribe fails.
     */
    @Override
    public void resubscribe(TaskIdParams request, Consumer<StreamingEventKind> eventConsumer, Consumer<Throwable> errorConsumer, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        checkNotNullParam("eventConsumer", eventConsumer);
        checkNotNullParam("errorConsumer", errorConsumer);
        try {
            SSEEventListener sseEventListener = new SSEEventListener(eventConsumer, errorConsumer);
            if (null != request.metadata()) {
                registerSSEListener(request, sseEventListener);
                addSubscribe(request);
                unSubscribe(request);
            }
            if (this.useDefaultRecoverMode) {
                RECOVER_MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER, sseEventListener);
            }
        } catch (Exception e) {
            log.error("RocketMQTransport resubscribe error", e);
            throw new A2AClientException("RocketMQTransport resubscribe error", e);
        }
    }

    /**
     * Registers an SSE event listener for a specific message ID to enable message routing.
     * The listener is stored under the current namespace and message ID for later retrieval.
     *
     * @param request the task request containing metadata.
     * @param sseEventListener the listener to register for real-time streaming.
     */
    private void registerSSEListener(TaskIdParams request, SSEEventListener sseEventListener) {
        String responseMsgId = (String)request.metadata().get(RocketMQA2AConstant.MESSAGE_RESPONSE_ID);
        if (!StringUtils.isEmpty(responseMsgId)) {
            MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(this.namespace, k -> new ConcurrentHashMap<>()).put(responseMsgId, sseEventListener);
        }
    }

    /**
     * Subscribes the consumer to a specified LiteTopic for receiving asynchronous responses.
     * Also marks whether this topic supports recovery mode for reconnection scenarios.
     *
     * @param request the task request containing the topic name to subscribe.
     * @throws ClientException if the subscription fails.
     */
    private void addSubscribe(TaskIdParams request) throws ClientException {
        String subLiteTopic = (String)request.metadata().get(RocketMQA2AConstant.SUB_LITE_TOPIC);
        if (null != litePushConsumer && !StringUtils.isEmpty(subLiteTopic)) {
            litePushConsumer.subscribeLite(subLiteTopic);
            log.info("RocketMQTransport.resubscribe litePushConsumer subscribeLite LiteTopic: [{}]", subLiteTopic);
            LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.namespace, k -> new ConcurrentHashMap<>()).put(subLiteTopic, this.useDefaultRecoverMode);
        }
    }
    /**
     * Unsubscribes from a specified LiteTopic to stop receiving messages.
     * Also removes the recovery mode flag for this topic.
     *
     * @param request the task request containing the topic name to unsubscribe.
     * @throws ClientException if the unsubscription fails.
     */
    private void unSubscribe(TaskIdParams request) throws ClientException {
        String unsubLiteTopic = (String)request.metadata().get(RocketMQA2AConstant.UNSUB_LITE_TOPIC);
        if (null != litePushConsumer && !StringUtils.isEmpty(unsubLiteTopic)) {
            litePushConsumer.unsubscribeLite(unsubLiteTopic);
            log.info("RocketMQTransport.resubscribe litePushConsumer unsubscribeLite LiteTopic: [{}]", unsubLiteTopic);
            LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.namespace, k -> new ConcurrentHashMap<>()).remove(unsubLiteTopic);
        }
    }

    /**
     * Queries the status of a specific task on the remote agent.
     *
     * @param request the task query parameters.
     * @param context optional client call context.
     * @return the task details, or {@code null} if an error occurred.
     * @throws A2AClientException if the request fails.
     */
    @Override
    public Task getTask(TaskQueryParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        try {
            GetTaskRequest getTaskRequest = new GetTaskRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(GetTaskRequest.METHOD).params(request).build();
            PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetTaskRequest.METHOD, getTaskRequest, this.agentCard, context);
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            GetTaskResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, GET_TASK_RESPONSE_REFERENCE), GET_TASK_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport getTask error", e);
            throw new A2AClientException("RocketMQTransport getTask error", e);
        }
    }

    /**
     * Cancels the execution of a specific task on the remote agent.
     *
     * @param request the task ID parameters.
     * @param context optional client call context.
     * @return the updated task state, or {@code null} if an error occurred.
     * @throws A2AClientException if the request fails.
     */
    @Override
    public Task cancelTask(TaskIdParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        try {
            CancelTaskRequest cancelTaskRequest = new CancelTaskRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(CancelTaskRequest.METHOD).params(request).build();
            PayloadAndHeaders payloadAndHeaders = applyInterceptors(CancelTaskRequest.METHOD, cancelTaskRequest, this.agentCard, context);
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            CancelTaskResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, CANCEL_TASK_RESPONSE_REFERENCE), CANCEL_TASK_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport cancelTask error", e);
            throw new A2AClientException("RocketMQTransport cancelTask error", e);
        }
    }

    /**
     * Sets the push notification configuration for a specific task.
     *
     * @param request the push notification configuration to apply.
     * @param context optional client call context.
     * @return the applied configuration, or {@code null} if an error occurred.
     * @throws A2AClientException if the request fails.
     */
    @Override
    public TaskPushNotificationConfig setTaskPushNotificationConfiguration(TaskPushNotificationConfig request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        try {
            SetTaskPushNotificationConfigRequest setTaskPushNotificationRequest = new SetTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(SetTaskPushNotificationConfigRequest.METHOD).params(request).build();
            PayloadAndHeaders payloadAndHeaders = applyInterceptors(SetTaskPushNotificationConfigRequest.METHOD, setTaskPushNotificationRequest, this.agentCard, context);
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.taskId());
            SetTaskPushNotificationConfigResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE), SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport setTaskPushNotificationConfiguration error", e);
            throw new A2AClientException("RocketMQTransport setTaskPushNotificationConfiguration error", e);
        }
    }

    /**
     * Retrieves the push notification configuration for a specific task.
     *
     * @param request the parameters identifying the task.
     * @param context optional client call context.
     * @return the current configuration, or {@code null} if an error occurred.
     * @throws A2AClientException if the request fails.
     */
    @Override
    public TaskPushNotificationConfig getTaskPushNotificationConfiguration(GetTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        try {
            GetTaskPushNotificationConfigRequest getTaskPushNotificationRequest = new GetTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(GetTaskPushNotificationConfigRequest.METHOD).params(request).build();
            PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetTaskPushNotificationConfigRequest.METHOD, getTaskPushNotificationRequest, this.agentCard, context);
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            GetTaskPushNotificationConfigResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE), GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport getTaskPushNotificationConfiguration error", e);
            throw new A2AClientException("RocketMQTransport getTaskPushNotificationConfiguration error", e);
        }
    }

    /**
     * Lists all push notification configurations for a given task or scope.
     *
     * @param request the listing parameters.
     * @param context optional client call context.
     * @return a list of configurations, or {@code null} if an error occurred.
     * @throws A2AClientException if the request fails.
     */
    @Override
    public List<TaskPushNotificationConfig> listTaskPushNotificationConfigurations(ListTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        try {
            ListTaskPushNotificationConfigRequest listTaskPushNotificationRequest = new ListTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(ListTaskPushNotificationConfigRequest.METHOD).params(request).build();
            PayloadAndHeaders payloadAndHeaders = applyInterceptors(ListTaskPushNotificationConfigRequest.METHOD, listTaskPushNotificationRequest, this.agentCard, context);
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            ListTaskPushNotificationConfigResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE), LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport listTaskPushNotificationConfigurations error", e);
            throw new A2AClientException("RocketMQTransport listTaskPushNotificationConfigurations error", e);
        }
    }

    /**
     * Deletes the push notification configuration for a specific task.
     *
     * @param request the deletion parameters.
     * @param context optional client call context.
     * @throws A2AClientException if the request fails.
     */
    @Override
    public void deleteTaskPushNotificationConfigurations(DeleteTaskPushNotificationConfigParams request,
        ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        try {
            DeleteTaskPushNotificationConfigRequest deleteTaskPushNotificationRequest = new DeleteTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(DeleteTaskPushNotificationConfigRequest.METHOD).params(request).build();
            PayloadAndHeaders payloadAndHeaders = applyInterceptors(DeleteTaskPushNotificationConfigRequest.METHOD, deleteTaskPushNotificationRequest, this.agentCard, context);
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            getResult(responseMessageId, this.namespace, DELETE_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
        } catch (Exception e) {
            log.error("RocketMQTransport deleteTaskPushNotificationConfigurations error", e);
            throw new A2AClientException("RocketMQTransport deleteTaskPushNotificationConfigurations error", e);
        }
    }

    /**
     * Retrieves the agent's {@link AgentCard}, potentially enriched with authenticated extended data.
     *
     * @param context optional client call context.
     * @return the resolved agent card.
     * @throws A2AClientException if card resolution or enrichment fails.
     */
    @Override
    public AgentCard getAgentCard(ClientCallContext context) throws A2AClientException {
        try {
            if (agentCard == null) {
                A2ACardResolver resolver = new A2ACardResolver(httpClient, agentUrl, null, getHttpHeaders(context));
                agentCard = resolver.getAgentCard();
                needsExtendedCard = agentCard.supportsAuthenticatedExtendedCard();
            }
            if (!needsExtendedCard) {
                return agentCard;
            }
            try {
                GetAuthenticatedExtendedCardRequest getExtendedAgentCardRequest = new GetAuthenticatedExtendedCardRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(GetAuthenticatedExtendedCardRequest.METHOD).build(); // id will be randomly generated
                PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetAuthenticatedExtendedCardRequest.METHOD, getExtendedAgentCardRequest, this.agentCard, context);
                String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, null);
                GetAuthenticatedExtendedCardResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE), GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE);
                return response.getResult();
            } catch (Exception e) {
                log.error("RocketMQTransport GetAuthenticatedExtendedCard error", e);
                throw new A2AClientException("RocketMQTransport GetAuthenticatedExtendedCard error", e);
            }
        } catch (A2AClientError e) {
            log.error("RocketMQTransport getAgentCard error", e);
            throw new A2AClientException("RocketMQTransport getAgentCard error", e);
        }
    }

    @Override
    public void close() {}

    /**
     * Resolves the appropriate lite topic for a request, potentially creating a context-specific subscription.
     *
     * @param contextId the optional context ID.
     * @return the resolved lite topic to use for this request.
     */
    private String resolveLiteTopic(String contextId) {
        if (StringUtils.isEmpty(contextId)) {
            return this.liteTopic;
        }
        try {
            litePushConsumer.subscribeLite(contextId);
            return contextId;
        } catch (ClientException e) {
            log.error("resolveLiteTopic subscribeLiteTopic error, contextId: [{}], liteTopic: [{}] ", contextId, this.liteTopic, e);
            return this.liteTopic;
        }
    }

    /**
     * Applies registered interceptors to the outgoing request payload and headers.
     *
     * @param methodName the JSON-RPC method name.
     * @param payload the original request payload.
     * @param agentCard the agent's identity.
     * @param clientCallContext the client call context.
     * @return the modified payload and headers after interception.
     */
    private PayloadAndHeaders applyInterceptors(String methodName, Object payload, AgentCard agentCard, ClientCallContext clientCallContext) {
        PayloadAndHeaders payloadAndHeaders = new PayloadAndHeaders(payload, getHttpHeaders(clientCallContext));
        if (CollectionUtils.isEmpty(interceptors)) {
            return payloadAndHeaders;
        }
        for (ClientCallInterceptor interceptor : interceptors) {
            payloadAndHeaders = interceptor.intercept(methodName, payloadAndHeaders.getPayload(), payloadAndHeaders.getHeaders(), agentCard, clientCallContext);
        }
        return payloadAndHeaders;
    }

    /**
     * Extracts HTTP headers from the client call context, or returns an empty map if none.
     *
     * @param context the client call context.
     * @return a map of HTTP headers.
     */
    private Map<String, String> getHttpHeaders(@Nullable ClientCallContext context) {
        return context != null ? context.getHeaders() : Collections.emptyMap();
    }
}
