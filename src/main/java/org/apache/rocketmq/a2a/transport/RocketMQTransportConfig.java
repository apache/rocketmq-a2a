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

import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.transport.spi.ClientTransportConfig;

/**
 * RocketMQTransport configuration class, used to encapsulate all required parameters for RocketMQ A2A communication
 */
public class RocketMQTransportConfig extends ClientTransportConfig<RocketMQTransport> {
    /**
     * RocketMQ Account Name
     */
    private String accessKey;

    /**
     * RocketMQ Account Password
     */
    private String secretKey;

    /**
     * The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     */
    private String endpoint;

    /**
     * Used for logical isolation of different business units or environments
     */
    private String namespace;

    /**
     * LiteTopic for clients to receive response results
     */
    private String workAgentResponseTopic;

    /**
     * The CID used by the client to subscribe to the LiteTopic for response results
     */
    private String workAgentResponseGroupID;

    /**
     * The Normal Topic bound to the target Agent
     */
    private String agentTopic;

    /**
     * The URL where the Agent provides services
     */
    private String agentUrl;

    /**
     * todo
     */
    private String liteTopic;

    /**
     * Whether to use the default recovery mode
     */
    private boolean useDefaultRecoverMode = false;

    /**
     * Create New RocketMQTransportConfig
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param namespace Used for logical isolation of different business units or environments
     * @param workAgentResponseTopic LiteTopic for clients to receive response results
     * @param workAgentResponseGroupID The CID used by the client to subscribe to the LiteTopic for response results
     * @param agentTopic The Normal Topic bound to the target Agent
     * @param httpClient HttpClient
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
     * Create New RocketMQTransportConfig
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param namespace Used for logical isolation of different business units or environments
     * @param workAgentResponseTopic LiteTopic for clients to receive response results
     * @param workAgentResponseGroupID The CID used by the client to subscribe to the LiteTopic for response results
     * @param agentTopic The Normal Topic bound to the target Agent
     * @param httpClient HttpClient
     * @param liteTopic todo
     * @param useDefaultRecoverMode Whether to use the default recovery mode
     */
    public RocketMQTransportConfig(String accessKey, String secretKey, String endpoint, String namespace,
        String workAgentResponseTopic, String workAgentResponseGroupID, String agentTopic, A2AHttpClient httpClient, String liteTopic, boolean useDefaultRecoverMode) {
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

    /**
     * Create New RocketMQTransportConfig
     * @param accessKey RocketMQ Account Name
     * @param secretKey RocketMQ Account Password
     * @param endpoint The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster
     * @param namespace Used for logical isolation of different business units or environments
     * @param agentTopic The Normal Topic bound to the target Agent
     * @param httpClient HttpClient
     */
    public RocketMQTransportConfig(String accessKey, String secretKey, String endpoint, String namespace, String agentTopic, A2AHttpClient httpClient) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.endpoint = endpoint;
        this.namespace = namespace;
        this.agentTopic = agentTopic;
        this.httpClient = httpClient;
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

