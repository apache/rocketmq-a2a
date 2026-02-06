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
package org.apache.rocketmq.a2a.common.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates a request message for A2A (Agent-to-Agent) communication over RocketMQ.
 *
 * <p>This class serves as the payload of an asynchronous request sent from one agent to another.
 * It includes:
 * <ul>
 *   <li>Protocol headers in {@link #requestHeader}</li>
 *   <li>The serialized {@link #requestBody} (typically JSON)</li>
 *   <li>Routing information: destination topic ({@link #destAgentTopic})</li>
 *   <li>Reply routing: response lightweight topic ({@link #workAgentResponseTopic})
 *   and LiteTopic ({@link #liteTopic}, LiteTopic is a session identifier,
 *   similar to a SessionId, dynamically created at runtime for data storage and isolation.)</li>
 * </ul>
 *
 **/
public class RocketMQRequest {

    /**
     * Headers carrying A2A protocol metadata (e.g., method name) as key-value pairs.
     * Initialized lazily in {@link #addHeader(String, String)}.
     */
    private Map<String, String> requestHeader;

    /**
     * The serialized request body, typically in JSON format.
     */
    private String requestBody;

    /**
     * The destination agent topic where this request is sent.
     */
    private String destAgentTopic;

    /**
     * The dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     */
    private String workAgentResponseTopic;

    /**
     * The dedicated topic for receiving reply messages from the target agent.
     * Typically, a liteTopic that is bound to {@link #workAgentResponseTopic}.
     * LiteTopic is a session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     */
    private String liteTopic;

    /**
     * Unique identifier for the request, used for tracking and correlation.
     */
    private String requestId;

    /**
     * Default constructor for creating an instance of RocketMQRequest.
     */
    public RocketMQRequest() {}

    /**
     * Gets the serialized request body.
     *
     * @return the request body as a string.
     */
    public String getRequestBody() {
        return requestBody;
    }

    /**
     * Sets the serialized request body.
     *
     * @param requestBody the request body to set.
     */
    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    /**
     * Gets the protocol headers.
     *
     * @return the map of request headers.
     */
    public Map<String, String> getRequestHeader() {
        return requestHeader;
    }

    /**
     * Sets the protocol headers.
     *
     * @param requestHeader the map of request headers to set.
     */
    public void setRequestHeader(Map<String, String> requestHeader) {
        this.requestHeader = requestHeader;
    }

    /**
     * Gets the destination agent topic.
     *
     * @return the destination agent topic.
     */
    public String getDestAgentTopic() {
        return destAgentTopic;
    }

    /**
     * Sets the destination agent topic.
     *
     * @param destAgentTopic the destination agent topic to set.
     */
    public void setDestAgentTopic(String destAgentTopic) {
        this.destAgentTopic = destAgentTopic;
    }

    /**
     * Gets the LiteTopic used for session identification.
     *
     * @return the LiteTopic.
     */
    public String getLiteTopic() {
        return liteTopic;
    }

    /**
     * Sets the LiteTopic used for session identification.
     *
     * @param liteTopic the LiteTopic to set.
     */
    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    /**
     * Gets the unique request identifier.
     *
     * @return the request ID.
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * Sets the unique request identifier.
     *
     * @param requestId the request ID to set.
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * Adds a header to the request headers map.
     * Initializes the map if it is null.
     *
     * @param key the header key.
     * @param value the header value.
     */
    public void addHeader(String key, String value) {
        if (null == requestHeader) {
            requestHeader = new HashMap<>();
        }
        requestHeader.put(key, value);
    }

    /**
     * Gets the dedicated topic for receiving reply messages.
     *
     * @return the work agent response topic.
     */
    public String getWorkAgentResponseTopic() {
        return workAgentResponseTopic;
    }

    /**
     * Sets the dedicated topic for receiving reply messages.
     *
     * @param workAgentResponseTopic the work agent response topic to set.
     */
    public void setWorkAgentResponseTopic(String workAgentResponseTopic) {
        this.workAgentResponseTopic = workAgentResponseTopic;
    }

    /**
     * Creates a new builder instance for constructing RocketMQRequest objects.
     *
     * @return a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing RocketMQRequest instances.
     */
    public static class Builder {
        private final RocketMQRequest request = new RocketMQRequest();

        /**
         * Sets the request body.
         *
         * @param requestBody the request body to set.
         * @return the current Builder instance.
         */
        public Builder requestBody(String requestBody) {
            request.setRequestBody(requestBody);
            return this;
        }

        /**
         * Sets the destination agent topic.
         *
         * @param destAgentTopic the destination agent topic to set.
         * @return the current Builder instance.
         */
        public Builder destAgentTopic(String destAgentTopic) {
            request.setDestAgentTopic(destAgentTopic);
            return this;
        }

        /**
         * Sets the work agent response topic.
         *
         * @param workAgentResponseTopic the work agent response topic to set.
         * @return the current Builder instance.
         */
        public Builder workAgentResponseTopic(String workAgentResponseTopic) {
            request.setWorkAgentResponseTopic(workAgentResponseTopic);
            return this;
        }

        /**
         * Sets the LiteTopic.
         *
         * @param liteTopic the LiteTopic to set.
         * @return the current Builder instance.
         */
        public Builder liteTopic(String liteTopic) {
            request.setLiteTopic(liteTopic);
            return this;
        }

        /**
         * Sets the request ID.
         *
         * @param requestId the request ID to set.
         * @return the current Builder instance.
         */
        public Builder requestId(String requestId) {
            request.setRequestId(requestId);
            return this;
        }

        /**
         * Sets the request headers.
         *
         * @param requestHeader the map of request headers to set.
         * @return the current Builder instance.
         */
        public Builder requestHeader(Map<String, String> requestHeader) {
            request.setRequestHeader(requestHeader);
            return this;
        }

        /**
         * Adds a single header to the request headers.
         *
         * @param key the header key.
         * @param value the header value.
         * @return the current Builder instance.
         */
        public Builder addHeader(String key, String value) {
            request.addHeader(key, value);
            return this;
        }

        /**
         * Builds and returns the constructed RocketMQRequest instance.
         *
         * @return the constructed RocketMQRequest.
         */
        public RocketMQRequest build() {
            return request;
        }
    }
}
