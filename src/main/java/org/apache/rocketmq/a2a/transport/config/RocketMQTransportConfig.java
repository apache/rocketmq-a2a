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
     * LiteTopic is a session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     */
    private String liteTopic;

    /**
     * Indicates whether the default message recovery mode should be enabled for streaming responses.
     */
    private boolean useDefaultRecoverMode = false;

    private A2AHttpClient httpClient;

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
     * LiteTopic is a session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
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

    /**
     * Default constructor.
     */
    public RocketMQTransportConfig() {}

    /**
     * Constructor with HttpClient parameter.
     *
     * @param httpClient the HTTP client to be used.
     */
    public RocketMQTransportConfig(A2AHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Gets the HTTP client.
     *
     * @return the HTTP client.
     */
    public A2AHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * Gets the secret key.
     *
     * @return the secret key.
     */
    public String getSecretKey() {
        return secretKey;
    }

    /**
     * Sets the secret key.
     *
     * @param secretKey the secret key to set.
     */
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /**
     * Gets the endpoint.
     *
     * @return the endpoint.
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the endpoint.
     *
     * @param endpoint the endpoint to set.
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Gets the namespace.
     *
     * @return the namespace.
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Sets the namespace.
     *
     * @param namespace the namespace to set.
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    /**
     * Gets the work agent response topic.
     *
     * @return the work agent response topic.
     */
    public String getWorkAgentResponseTopic() {
        return workAgentResponseTopic;
    }

    /**
     * Sets the work agent response topic.
     *
     * @param workAgentResponseTopic the work agent response topic to set.
     */
    public void setWorkAgentResponseTopic(String workAgentResponseTopic) {
        this.workAgentResponseTopic = workAgentResponseTopic;
    }

    /**
     * Gets the work agent response group ID.
     *
     * @return the work agent response group ID.
     */
    public String getWorkAgentResponseGroupID() {
        return workAgentResponseGroupID;
    }

    /**
     * Sets the work agent response group ID.
     *
     * @param workAgentResponseGroupID the work agent response group ID to set.
     */
    public void setWorkAgentResponseGroupID(String workAgentResponseGroupID) {
        this.workAgentResponseGroupID = workAgentResponseGroupID;
    }

    /**
     * Gets the agent topic.
     *
     * @return the agent topic.
     */
    public String getAgentTopic() {
        return agentTopic;
    }

    /**
     * Sets the agent topic.
     *
     * @param agentTopic the agent topic to set.
     */
    public void setAgentTopic(String agentTopic) {
        this.agentTopic = agentTopic;
    }

    /**
     * Gets the access key.
     *
     * @return the access key.
     */
    public String getAccessKey() {
        return accessKey;
    }

    /**
     * Sets the access key.
     *
     * @param accessKey the access key to set.
     */
    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    /**
     * Gets the agent URL.
     *
     * @return the agent URL.
     */
    public String getAgentUrl() {
        return agentUrl;
    }

    /**
     * Sets the agent URL.
     *
     * @param agentUrl the agent URL to set.
     */
    public void setAgentUrl(String agentUrl) {
        this.agentUrl = agentUrl;
    }

    /**
     * Sets the HTTP client.
     *
     * @param httpClient the HTTP client to set.
     */
    public void setHttpClient(A2AHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Gets the lite topic.
     *
     * @return the lite topic.
     */
    public String getLiteTopic() {
        return liteTopic;
    }

    /**
     * Sets the lite topic.
     *
     * @param liteTopic the lite topic to set.
     */
    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    /**
     * Checks if the default recover mode is enabled.
     *
     * @return true if the default recover mode is enabled, false otherwise.
     */
    public boolean isUseDefaultRecoverMode() {
        return useDefaultRecoverMode;
    }

    /**
     * Sets whether the default recover mode should be enabled.
     *
     * @param useDefaultRecoverMode true to enable the default recover mode, false otherwise.
     */
    public void setUseDefaultRecoverMode(boolean useDefaultRecoverMode) {
        this.useDefaultRecoverMode = useDefaultRecoverMode;
    }

    /**
     * Creates a new builder instance for constructing RocketMQTransportConfig objects.
     *
     * @return a new builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for RocketMQTransportConfig.
     */
    public static class Builder {
        private String accessKey;
        private String secretKey;
        private String endpoint;
        private String namespace;
        private String workAgentResponseTopic;
        private String workAgentResponseGroupID;
        private String agentTopic;
        private String agentUrl;
        private String liteTopic;
        private boolean useDefaultRecoverMode = false;
        private A2AHttpClient httpClient;

        /**
         * Sets the access key.
         *
         * @param accessKey the access key to set.
         * @return the builder instance.
         */
        public Builder accessKey(String accessKey) {
            this.accessKey = accessKey;
            return this;
        }

        /**
         * Sets the secret key.
         *
         * @param secretKey the secret key to set.
         * @return the builder instance.
         */
        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        /**
         * Sets the endpoint.
         *
         * @param endpoint the endpoint to set.
         * @return the builder instance.
         */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        /**
         * Sets the namespace.
         *
         * @param namespace the namespace to set.
         * @return the builder instance.
         */
        public Builder namespace(String namespace) {
            this.namespace = namespace;
            return this;
        }

        /**
         * Sets the work agent response topic.
         *
         * @param workAgentResponseTopic the work agent response topic to set.
         * @return the builder instance.
         */
        public Builder workAgentResponseTopic(String workAgentResponseTopic) {
            this.workAgentResponseTopic = workAgentResponseTopic;
            return this;
        }

        /**
         * Sets the work agent response group ID.
         *
         * @param workAgentResponseGroupID the work agent response group ID to set.
         * @return the builder instance.
         */
        public Builder workAgentResponseGroupID(String workAgentResponseGroupID) {
            this.workAgentResponseGroupID = workAgentResponseGroupID;
            return this;
        }

        /**
         * Sets the agent topic.
         *
         * @param agentTopic the agent topic to set.
         * @return the builder instance.
         */
        public Builder agentTopic(String agentTopic) {
            this.agentTopic = agentTopic;
            return this;
        }

        /**
         * Sets the agent URL.
         *
         * @param agentUrl the agent URL to set.
         * @return the builder instance.
         */
        public Builder agentUrl(String agentUrl) {
            this.agentUrl = agentUrl;
            return this;
        }

        /**
         * Sets the lite topic.
         *
         * @param liteTopic the lite topic to set.
         * @return the builder instance.
         */
        public Builder liteTopic(String liteTopic) {
            this.liteTopic = liteTopic;
            return this;
        }

        /**
         * Sets whether the default recover mode should be enabled.
         *
         * @param useDefaultRecoverMode true to enable the default recover mode, false otherwise.
         * @return the builder instance.
         */
        public Builder useDefaultRecoverMode(boolean useDefaultRecoverMode) {
            this.useDefaultRecoverMode = useDefaultRecoverMode;
            return this;
        }

        /**
         * Sets the HTTP client.
         *
         * @param httpClient the HTTP client to set.
         * @return the builder instance.
         */
        public Builder httpClient(A2AHttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        /**
         * Builds a RocketMQTransportConfig instance with the configured parameters.
         *
         * @return a new RocketMQTransportConfig instance.
         */
        public RocketMQTransportConfig build() {
            // Use full constructor to ensure all fields are properly initialized
            RocketMQTransportConfig config = new RocketMQTransportConfig(
                accessKey, secretKey, endpoint, namespace,
                workAgentResponseTopic, workAgentResponseGroupID,
                agentTopic, httpClient, liteTopic, useDefaultRecoverMode
            );
            config.setAgentUrl(agentUrl);
            return config;
        }
    }
}
