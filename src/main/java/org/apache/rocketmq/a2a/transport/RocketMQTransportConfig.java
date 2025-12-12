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

public class RocketMQTransportConfig extends ClientTransportConfig<RocketMQTransport> {
    private String accessKey;
    private String secretKey;
    private String globalEndpoint;
    private String rocketMQNamespace;
    private String workAgentResponseTopic;
    private String workAgentResponseGroupID;
    private String agentTopic;
    private String agentUrl;
    private String liteTopic;
    private boolean useDefaultRecoverMode = false;

    public RocketMQTransportConfig(String accessKey, String secretKey, String globalEndpoint, String rocketMQNamespace,
        String workAgentResponseTopic, String workAgentResponseGroupID, String agentTopic, A2AHttpClient httpClient) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.globalEndpoint = globalEndpoint;
        this.rocketMQNamespace = rocketMQNamespace;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.workAgentResponseGroupID = workAgentResponseGroupID;
        this.agentTopic = agentTopic;
        this.httpClient = httpClient;
    }

    public RocketMQTransportConfig(String accessKey, String secretKey, String globalEndpoint, String rocketMQNamespace,
        String workAgentResponseTopic, String workAgentResponseGroupID, String agentTopic, A2AHttpClient httpClient, String liteTopic, boolean useDefaultRecoverMode) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.globalEndpoint = globalEndpoint;
        this.rocketMQNamespace = rocketMQNamespace;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.workAgentResponseGroupID = workAgentResponseGroupID;
        this.agentTopic = agentTopic;
        this.httpClient = httpClient;
        this.liteTopic = liteTopic;
        this.useDefaultRecoverMode = useDefaultRecoverMode;
    }

    public RocketMQTransportConfig(String accessKey, String secretKey, String globalEndpoint, String rocketMQNamespace, String agentTopic, A2AHttpClient httpClient) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.globalEndpoint = globalEndpoint;
        this.rocketMQNamespace = rocketMQNamespace;
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

    public String getGlobalEndpoint() {
        return globalEndpoint;
    }

    public void setGlobalEndpoint(String globalEndpoint) {
        this.globalEndpoint = globalEndpoint;
    }

    public String getRocketMQNamespace() {
        return rocketMQNamespace;
    }

    public void setRocketMQNamespace(String rocketMQNamespace) {
        this.rocketMQNamespace = rocketMQNamespace;
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

