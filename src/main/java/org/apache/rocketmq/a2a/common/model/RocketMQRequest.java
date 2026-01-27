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
 *   and LiteTopic ({@link #liteTopic}, LiteTopic is a lightweight session identifier,
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
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     */
    private String liteTopic;

    /**
     * Constructs a new RocketMQRequest with the given parameters.
     *
     * @param requestBody the serialized request body (e.g., JSON).
     * @param requestHeader headers containing A2A metadata.
     * @param destAgentTopic the destination agent topic where the request is sent.
     * @param workAgentResponseTopic The dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     * @param liteTopic The dedicated topic for receiving reply messages from the target agent(Typically, a liteTopic that is bound to {@link #workAgentResponseTopic})
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation
     */
    public RocketMQRequest(String requestBody, Map<String, String> requestHeader, String destAgentTopic, String workAgentResponseTopic, String liteTopic) {
        this.requestBody = requestBody;
        this.requestHeader = requestHeader;
        this.destAgentTopic = destAgentTopic;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.liteTopic = liteTopic;
    }

    public RocketMQRequest() {}

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public Map<String, String> getRequestHeader() {
        return requestHeader;
    }

    public void setRequestHeader(Map<String, String> requestHeader) {
        this.requestHeader = requestHeader;
    }

    public String getDestAgentTopic() {
        return destAgentTopic;
    }

    public void setDestAgentTopic(String destAgentTopic) {
        this.destAgentTopic = destAgentTopic;
    }

    public String getLiteTopic() {
        return liteTopic;
    }

    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    public void addHeader(String key, String value) {
        if (null == requestHeader) {
            requestHeader = new HashMap<>();
        }
        requestHeader.put(key, value);
    }

    public String getWorkAgentResponseTopic() {
        return workAgentResponseTopic;
    }

    public void setWorkAgentResponseTopic(String workAgentResponseTopic) {
        this.workAgentResponseTopic = workAgentResponseTopic;
    }

    /**
     * Returns a new {@link Builder} instance for constructing a {@link RocketMQRequest}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for creating {@link RocketMQRequest} instances.
     */
    public static class Builder {
        private final RocketMQRequest request = new RocketMQRequest();

        public Builder requestBody(String requestBody) {
            request.setRequestBody(requestBody);
            return this;
        }

        public Builder destAgentTopic(String destAgentTopic) {
            request.setDestAgentTopic(destAgentTopic);
            return this;
        }

        public Builder workAgentResponseTopic(String workAgentResponseTopic) {
            request.setWorkAgentResponseTopic(workAgentResponseTopic);
            return this;
        }

        public Builder liteTopic(String liteTopic) {
            request.setLiteTopic(liteTopic);
            return this;
        }

        public Builder requestHeader(Map<String, String> requestHeader) {
            request.setRequestHeader(requestHeader);
            return this;
        }

        public Builder addHeader(String key, String value) {
            request.addHeader(key, value);
            return this;
        }

        public RocketMQRequest build() {
            return request;
        }
    }
}
