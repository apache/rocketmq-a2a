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
package org.apache.rocketmq.a2a.transport.config;

import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.transport.spi.ClientTransportConfig;
import org.apache.rocketmq.a2a.transport.impl.RocketMQTransport;

/**
 * Configuration class for RocketMQTransport.
 * <p>
 * Encapsulates all necessary parameters to establish RocketMQ communication between clients and agents,
 * including authentication, topic routing, and session management.
 */
public class RocketMQTransportConfig extends ClientTransportConfig<RocketMQTransport> {

    /**
     * The access key for authenticating with the RocketMQ service.
     */
    private String accessKey;

    /**
     * The secret key for authenticating with the RocketMQ service.
     */
    private String secretKey;

    /**
     * The network endpoint of the RocketMQ service.
     */
    private String endpoint;

    /**
     * The namespace used for logical isolation of RocketMQ resources.
     */
    private String namespace;

    /**
     * The lightweight topic used to receive asynchronous replies.
     */
    private String workAgentResponseTopic;

    /**
     * The consumer group ID used when subscribing to the {@link #workAgentResponseTopic}.
     */
    private String workAgentResponseGroupID;

    /**
     * The RocketMQ topic associated with the target agent, used as the destination for client requests.
     */
    private String agentTopic;

    /**
     * The HTTP URL where the agent's metadata service is exposed.
     */
    private String agentUrl;

    /**
     * Typically, a liteTopic that is bound to {@link #workAgentResponseTopic}.
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     */
    private String liteTopic;

    /**
     * Indicates whether the default message recovery mode should be enabled for streaming responses.
     */
    private boolean useDefaultRecoverMode = false;

    /**
     * Creates a configuration instance for RocketMQTransport.
     *
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param agentTopic the RocketMQ topic associated with the target agent, used as the destination for client requests.
     * @param httpClient HttpClient.
     */
    public RocketMQTransportConfig(String accessKey, String secretKey, String endpoint, String namespace, String agentTopic, A2AHttpClient httpClient) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
        this.namespace = namespace;
        this.agentTopic = agentTopic;
        this.httpClient = httpClient;
    }

    /**
     * Creates a configuration instance for RocketMQTransport.
     *
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param workAgentResponseTopic the lightweight topic used to receive asynchronous replies.
     * @param workAgentResponseGroupID the consumer group ID used when subscribing to the {@link #workAgentResponseTopic}.
     * @param agentTopic the RocketMQ topic associated with the target agent, used as the destination for client requests.
     * @param httpClient HttpClient.
     */
    public RocketMQTransportConfig(String accessKey, String secretKey, String endpoint, String namespace,
        String workAgentResponseTopic, String workAgentResponseGroupID, String agentTopic, A2AHttpClient httpClient) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
        this.namespace = namespace;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.workAgentResponseGroupID = workAgentResponseGroupID;
        this.agentTopic = agentTopic;
        this.httpClient = httpClient;
    }

    /**
     * Creates a configuration instance for RocketMQTransport.
     *
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param workAgentResponseTopic the lightweight topic used to receive asynchronous replies.
     * @param workAgentResponseGroupID the consumer group ID used when subscribing to the {@link #workAgentResponseTopic}.
     * @param agentTopic the RocketMQ topic associated with the target agent, used as the destination for client requests.
     * @param httpClient HttpClient
     * @param liteTopic Typically, a liteTopic that is bound to {@link #workAgentResponseTopic}.
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     * @param useDefaultRecoverMode whether to use the default recovery mode.
     */
    public RocketMQTransportConfig(String accessKey, String secretKey, String endpoint, String namespace, String workAgentResponseTopic, String workAgentResponseGroupID, String agentTopic, A2AHttpClient httpClient, String liteTopic, boolean useDefaultRecoverMode) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
        this.namespace = namespace;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.workAgentResponseGroupID = workAgentResponseGroupID;
        this.agentTopic = agentTopic;
        this.httpClient = httpClient;
        this.liteTopic = liteTopic;
        this.useDefaultRecoverMode = useDefaultRecoverMode;
    }

    private A2AHttpClient httpClient;

    public RocketMQTransportConfig() {
        this.httpClient = null;
    }

    public RocketMQTransportConfig(A2AHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public A2AHttpClient getHttpClient() {
        return httpClient;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getWorkAgentResponseTopic() {
        return workAgentResponseTopic;
    }

    public void setWorkAgentResponseTopic(String workAgentResponseTopic) {
        this.workAgentResponseTopic = workAgentResponseTopic;
    }

    public String getWorkAgentResponseGroupID() {
        return workAgentResponseGroupID;
    }

    public void setWorkAgentResponseGroupID(String workAgentResponseGroupID) {
        this.workAgentResponseGroupID = workAgentResponseGroupID;
    }

    public String getAgentTopic() {
        return agentTopic;
    }

    public void setAgentTopic(String agentTopic) {
        this.agentTopic = agentTopic;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getAgentUrl() {
        return agentUrl;
    }

    public void setAgentUrl(String agentUrl) {
        this.agentUrl = agentUrl;
    }

    public void setHttpClient(A2AHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String getLiteTopic() {
        return liteTopic;
    }

    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    public boolean isUseDefaultRecoverMode() {
        return useDefaultRecoverMode;
    }

    public void setUseDefaultRecoverMode(boolean useDefaultRecoverMode) {
        this.useDefaultRecoverMode = useDefaultRecoverMode;
    }
}

