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

import java.util.HashMap;
import java.util.Map;

/**
 * RocketMQRequest encapsulates request data for A2A (Agent-to-Agent) protocol communication built on top of RocketMQ as the underlying messaging component.
 * This class serves as the message payload for asynchronous request-response interactions between agents via RocketMQ.
 */
public class RocketMQRequest {
    /**
     * Request headers storing A2A protocol metadata in key-value pairs
     */
    private Map<String, String> requestHeader;

    /**
     * The request body, typically a serialized payload (e.g., JSON)
     */
    private String requestBody;

    /**
     * Destination Agent Topic, used for sending the original A2A request message
     */
    private String agentTopic;

    /**
     * Dedicated response topic for receiving reply messages from the target agent (typically a LiteTopic)
     */
    private String workAgentResponseTopic;

    /**
     * //todo 这种一般怎么命名呢？
     */
    private String liteTopic;

    public RocketMQRequest(String requestBody, Map<String, String> requestHeader, String desAgentTopic, String workAgentResponseTopic, String liteTopic) {
        this.requestBody = requestBody;
        this.requestHeader = requestHeader;
        this.agentTopic = desAgentTopic;
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

    public String getAgentTopic() {
        return agentTopic;
    }

    public void setAgentTopic(String agentTopic) {
        this.agentTopic = agentTopic;
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
}
