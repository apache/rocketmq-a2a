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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import io.a2a.client.transport.spi.ClientTransport;
import org.apache.rocketmq.a2a.common.RocketMQResourceInfo;
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
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static io.a2a.util.Assert.checkNotNullParam;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.CANCEL_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.DELETE_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.GET_TASK_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.SEND_MESSAGE_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE;
import static org.apache.rocketmq.a2a.common.RocketMQResourceInfo.parseAgentCardAddition;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.LITE_TOPIC_USE_DEFAULT_RECOVER_MAP;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.MESSAGE_STREAM_RESPONSE_MAP;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.RECOVER_MESSAGE_STREAM_RESPONSE_MAP;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.checkConfigParam;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.getResult;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.initAndGetLitePushConsumer;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.initAndGetProducer;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.sendRocketMQRequest;
import static org.apache.rocketmq.a2a.common.RocketMQUtil.unmarshalResponse;

/**
 * A ClientTransport implementation based on RocketMQ as the communication component
 */
public class RocketMQTransport implements ClientTransport {
    private static final Logger log = LoggerFactory.getLogger(RocketMQTransport.class);
    /**
     * The Topic bound to the target agent Agent
     */
    private final String agentTopic;
    /**
     * RocketMQ Account Name
     */
    private final String accessKey;
    /**
     * RocketMQ Account Password
     */
    private final String secretKey;
    /**
     * The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     */
    private final String endpoint;
    /**
     * Used for logical isolation of different business units or environments
     */
    private final String namespace;
    /**
     * A LiteTopic for clients to receive response results
     */
    private final String workAgentResponseTopic;
    /**
     * The CID used by the client to subscribe to the lightweight LiteTopic for response results
     */
    private final String workAgentResponseGroupID;
    /**
     * Client call interceptor
     */
    private final List<ClientCallInterceptor> interceptors;
    /**
     * AgentCard information in A2A
     */
    private AgentCard agentCard;
    /**
     * The URL where the Agent provides services
     */
    private final String agentUrl;
    /**
     * Whether to use the default recovery mode
     */
    private boolean useDefaultRecoverMode = false;
    /**
     * todo
     */
    private String liteTopic;

    private final A2AHttpClient httpClient;

    private boolean needsExtendedCard = false;
    /**
     * RocketMQ LitePushConsumer
     */
    private LitePushConsumer litePushConsumer;
    /**
     * RocketMQ Producer
     */
    private Producer producer;

    /**
     * Create New RocketMQTransport
     * @param namespace Used for logical isolation of different business units or environments
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param workAgentResponseTopic A LiteTopic for clients to receive response results
     * @param workAgentResponseGroupID The CID used by the client to subscribe to the LiteTopic for response results
     * @param interceptors Client call interceptor
     * @param agentUrl The URL where the Agent provides services
     * @param httpClient httpClient
     * @param liteTopic todo
     * @param useDefaultRecoverMode Whether to use the default recovery mode
     * @param agentCard AgentCard information in A2A
     */
    public RocketMQTransport(String namespace, String accessKey, String secretKey, String workAgentResponseTopic, String workAgentResponseGroupID,
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
        //Retrieve RocketMQ resource-related information from the AgentCard.
        RocketMQResourceInfo rocketAgentCardInfo = parseAgentCardAddition(this.agentCard);
        if (null == rocketAgentCardInfo) {
            throw new RuntimeException("RocketMQTransport rocketAgentCardInfo pare error");
        }
        if (null != namespace && !namespace.equals(rocketAgentCardInfo.getNamespace())) {
            throw new RuntimeException("RocketMQTransport rocketAgentCardInfo namespace do not match, please check the config info");
        }
        this.endpoint = rocketAgentCardInfo.getEndpoint();
        this.agentTopic = rocketAgentCardInfo.getTopic();
        this.namespace = StringUtils.isEmpty(rocketAgentCardInfo.getNamespace()) ? "" : rocketAgentCardInfo.getNamespace();
        LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.namespace, k -> new HashMap<>()).put(this.liteTopic, useDefaultRecoverMode);
        checkConfigParam(this.endpoint, this.workAgentResponseTopic, this.workAgentResponseGroupID, this.liteTopic, this.agentTopic);
        //Initialize the RocketMQ LitePushConsumer and Producer
        try {
            this.litePushConsumer = initAndGetLitePushConsumer(this.namespace, this.endpoint, this.accessKey, this.secretKey, this.workAgentResponseTopic, this.workAgentResponseGroupID, this.liteTopic);
            this.producer = initAndGetProducer(this.namespace, this.endpoint, this.accessKey, this.secretKey, this.agentTopic);
        } catch (ClientException e) {
            log.error("RocketMQTransport init rocketmq client error, e: {}", e.getMessage());
            throw new RuntimeException("RocketMQTransport init rocketmq client error");
        }
    }

    /**
     * Send a non-streaming request to the remote Agent service
     * @param request the message send parameters
     * @param context optional client call context for the request (may be {@code null})
     * @return EventKind
     * @throws A2AClientException A2AClientException
     */
    @Override
    public EventKind sendMessage(MessageSendParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        SendMessageRequest sendMessageRequest = new SendMessageRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(SendMessageRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(SendMessageRequest.METHOD, sendMessageRequest, this.agentCard, context);
        try {
            String liteTopic = dealLiteTopic(request.message().getContextId());
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, null);
            SendMessageResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, SEND_MESSAGE_RESPONSE_REFERENCE), SEND_MESSAGE_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport sendMessage error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Send a streaming request to the remote Agent service
     * @param request       the message send parameters
     * @param eventConsumer consumer that will receive streaming events as they arrive
     * @param errorConsumer consumer that will be called if an error occurs during streaming
     * @param context       optional client call context for the request (may be {@code null})
     * @throws A2AClientException
     */
    @Override
    public void sendMessageStreaming(MessageSendParams request, Consumer<StreamingEventKind> eventConsumer, Consumer<Throwable> errorConsumer, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        checkNotNullParam("eventConsumer", eventConsumer);
        SendStreamingMessageRequest sendStreamingMessageRequest = new SendStreamingMessageRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(SendStreamingMessageRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(SendStreamingMessageRequest.METHOD, sendStreamingMessageRequest, this.agentCard, context);
        SSEEventListener sseEventListener = new SSEEventListener(eventConsumer, errorConsumer);
        try {
            String liteTopic = dealLiteTopic(request.message().getContextId());
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, null);
            if (StringUtils.isEmpty(responseMessageId)) {
                log.error("RocketMQTransport sendMessageStreaming error, responseMessageId is null");
                return;
            }
            MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(this.namespace, k -> new HashMap<>()).put(responseMessageId, sseEventListener);
            log.info("RocketMQTransport sendMessageStreaming success, responseMessageId: {}", responseMessageId);
        } catch (Exception e) {
            throw new A2AClientException("RocketMQTransport Failed to send streaming message request: " + e, e);
        }
    }

    /**
     * Resubscribe or unsubscribe for a specific session ID
     * @param request       the task ID parameters specifying which task to resubscribe to
     * @param eventConsumer consumer that will receive streaming events as they arrive
     * @param errorConsumer consumer that will be called if an error occurs during streaming
     * @param context       optional client call context for the request (may be {@code null})
     * @throws A2AClientException
     */
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
                    MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(this.namespace, k -> new HashMap<>()).put(responseMessageId, sseEventListener);
                }
                String liteTopic = (String)request.metadata().get(RocketMQA2AConstant.LITE_TOPIC);
                if (null != litePushConsumer && !StringUtils.isEmpty(liteTopic)) {
                    litePushConsumer.subscribeLite(liteTopic);
                    log.info("litePushConsumer subscribeLite liteTopic: {}", liteTopic);
                    LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.namespace, k -> new HashMap<>()).put(liteTopic, this.useDefaultRecoverMode);
                }
                String closeLiteTopic = (String)request.metadata().get(RocketMQA2AConstant.CLOSE_LITE_TOPIC);
                if (null != litePushConsumer && !StringUtils.isEmpty(closeLiteTopic)) {
                    litePushConsumer.unsubscribeLite(closeLiteTopic);
                    log.info("litePushConsumer unsubscribeLite liteTopic: {}", closeLiteTopic);
                    LITE_TOPIC_USE_DEFAULT_RECOVER_MAP.computeIfAbsent(this.namespace, k -> new HashMap<>()).remove(closeLiteTopic);
                }
            }
            if (this.useDefaultRecoverMode) {
                RECOVER_MESSAGE_STREAM_RESPONSE_MAP.computeIfAbsent(namespace, k -> new HashMap<>()).put(RocketMQA2AConstant.DEFAULT_STREAM_RECOVER, sseEventListener);
            }
        } catch (Exception e) {
            throw new A2AClientException("RocketMQTransport failed to resubscribe streaming message request: " + e, e);
        }
    }

    /**
     * Query the completion status of tasks on the remote Agent
     * @param request the task query parameters specifying which task to retrieve
     * @param context optional client call context for the request (may be {@code null})
     * @return Task
     * @throws A2AClientException
     */
    @Override
    public Task getTask(TaskQueryParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        GetTaskRequest getTaskRequest = new GetTaskRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(GetTaskRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetTaskRequest.METHOD, getTaskRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            GetTaskResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, GET_TASK_RESPONSE_REFERENCE), GET_TASK_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport getTask error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 取消在远端Agent中任务的执行
     * @param request the task ID parameters specifying which task to cancel
     * @param context optional client call context for the request (may be {@code null})
     * @return Task
     * @throws A2AClientException A2AClientException
     */
    @Override
    public Task cancelTask(TaskIdParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        CancelTaskRequest cancelTaskRequest = new CancelTaskRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(CancelTaskRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(CancelTaskRequest.METHOD, cancelTaskRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            CancelTaskResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, CANCEL_TASK_RESPONSE_REFERENCE), CANCEL_TASK_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport cancelTask error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Configure push notification settings for the task
     * @param request the push notification configuration to set for the task
     * @param context optional client call context for the request (may be {@code null})
     * @return TaskPushNotificationConfig
     * @throws A2AClientException A2AClientException
     */
    @Override
    public TaskPushNotificationConfig setTaskPushNotificationConfiguration(TaskPushNotificationConfig request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        SetTaskPushNotificationConfigRequest setTaskPushNotificationRequest = new SetTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(SetTaskPushNotificationConfigRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(SetTaskPushNotificationConfigRequest.METHOD, setTaskPushNotificationRequest, agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.taskId());
            SetTaskPushNotificationConfigResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE), SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport setTaskPushNotificationConfiguration error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Retrieve the push notification configuration information for the task
     * @param request the parameters specifying which task's notification config to retrieve
     * @param context optional client call context for the request (may be {@code null})
     * @return TaskPushNotificationConfig
     * @throws A2AClientException A2AClientException
     */
    @Override
    public TaskPushNotificationConfig getTaskPushNotificationConfiguration(GetTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        GetTaskPushNotificationConfigRequest getTaskPushNotificationRequest = new GetTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(GetTaskPushNotificationConfigRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetTaskPushNotificationConfigRequest.METHOD, getTaskPushNotificationRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            GetTaskPushNotificationConfigResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE), GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport getTaskPushNotificationConfiguration error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Query the push notification configuration information for the task
     * @param request the parameters specifying which task's notification configs to retrieve
     * @param context optional client call context for the request (may be {@code null})
     * @return List<TaskPushNotificationConfig>
     * @throws A2AClientException A2AClientException
     */
    @Override
    public List<TaskPushNotificationConfig> listTaskPushNotificationConfigurations(ListTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        ListTaskPushNotificationConfigRequest listTaskPushNotificationRequest = new ListTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(ListTaskPushNotificationConfigRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(ListTaskPushNotificationConfigRequest.METHOD, listTaskPushNotificationRequest, this.agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            ListTaskPushNotificationConfigResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE), LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
            return response.getResult();
        } catch (Exception e) {
            log.error("RocketMQTransport listTaskPushNotificationConfigurations error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Delete task push notification configuration
     * @param request the parameters specifying which task's notification configs to delete
     * @param context optional client call context for the request (may be {@code null})
     * @throws A2AClientException
     */
    @Override
    public void deleteTaskPushNotificationConfigurations(DeleteTaskPushNotificationConfigParams request, ClientCallContext context) throws A2AClientException {
        checkNotNullParam("request", request);
        DeleteTaskPushNotificationConfigRequest deleteTaskPushNotificationRequest = new DeleteTaskPushNotificationConfigRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(DeleteTaskPushNotificationConfigRequest.METHOD).params(request).build();
        PayloadAndHeaders payloadAndHeaders = applyInterceptors(DeleteTaskPushNotificationConfigRequest.METHOD, deleteTaskPushNotificationRequest, agentCard, context);
        try {
            String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, request.id());
            getResult(responseMessageId, this.namespace, DELETE_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE);
        } catch (Exception e) {
            log.error("RocketMQTransport deleteTaskPushNotificationConfigurations error: {}", e.getMessage());
        }
    }

    /**
     * Retrieve AgentCard information
     * @param context optional client call context for the request (may be {@code null})
     * @return AgentCard
     * @throws A2AClientException
     */
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
                GetAuthenticatedExtendedCardRequest getExtendedAgentCardRequest = new GetAuthenticatedExtendedCardRequest.Builder().jsonrpc(JSONRPCMessage.JSONRPC_VERSION).method(GetAuthenticatedExtendedCardRequest.METHOD).build(); // id will be randomly generated
                PayloadAndHeaders payloadAndHeaders = applyInterceptors(GetAuthenticatedExtendedCardRequest.METHOD, getExtendedAgentCardRequest, this.agentCard, context);
                String responseMessageId = sendRocketMQRequest(payloadAndHeaders, this.agentTopic, liteTopic, this.workAgentResponseTopic, this.producer, null);
                GetAuthenticatedExtendedCardResponse response = unmarshalResponse(getResult(responseMessageId, this.namespace, GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE), GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE);
                return response.getResult();
            } catch (Exception e) {
                throw new A2AClientException("RocketMQTransport getAgentCard error: " + e, e);
            }
        } catch (A2AClientError e) {
            throw new A2AClientException("RocketMQTransport getAgentCard error: " + e, e);
        }
    }

    @Override
    public void close() {}

    /**
     * Handle logic related to LiteTopic
     * @param contextId
     * @return
     */
    private String dealLiteTopic(String contextId) {
        String liteTopic = this.liteTopic;
        if (!StringUtils.isEmpty(contextId)) {
            try {
                //LitePushConsumer sub liteTopic
                litePushConsumer.subscribeLite(contextId);
                liteTopic = contextId;
            } catch (ClientException e) {
                log.error("dealLiteTopic error: {}", e.getMessage());
            }
        }
        return liteTopic;
    }

    /**
     * Apply interceptor
     * @param methodName methodName
     * @param payload payload
     * @param agentCard agentCard Info
     * @param clientCallContext clientCallContext
     * @return PayloadAndHeaders
     */
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
}
