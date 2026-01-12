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

/**
 * RocketMQResponse is used to encapsulate A2A protocol response messages returned via RocketMQ.
 */
public class RocketMQResponse {

    /**
     * The LiteTopic subscribed to by the client.
     */
    private String liteTopic;

    /**
     * Context ID, used to associate a complete A2A request-response session.
     */
    private String contextId;

    /**
     * Response body content, typically serialized business data (e.g., JSON etc.)
     */
    private String responseBody;

    /**
     * Task ID, which identifies the specific task corresponding to this A2A operation
     */
    private String taskId;

    /**
     * The message ID obtained by the RocketMQ client upon successfully sending an A2A request
     */
    private String messageId;

    /**
     * The response topic used by the server
     */
    private String serverWorkAgentResponseTopic;

    /**
     * The lite topic used by the server to indicate that subsequent requests of a specific type should be routed back to this particular server instance
     */
    private String serverLiteTopic;

    /**
     * Whether it is a streaming response
     */
    private boolean isStream;

    /**
     * Whether it is the end marker of a streaming response
     */
    private boolean isEnd;

    public RocketMQResponse(String liteTopic, String contextId, String responseBody, String messageId, boolean isStream, boolean isEnd) {
        this.liteTopic = liteTopic;
        this.contextId = contextId;
        this.responseBody = responseBody;
        this.messageId = messageId;
        this.isStream = isStream;
        this.isEnd = isEnd;
    }

    public RocketMQResponse(String liteTopic, String contextId, String responseBody, String messageId, boolean isStream, boolean isEnd, String taskId, String serverWorkAgentResponseTopic, String serverLiteTopic) {
        this.liteTopic = liteTopic;
        this.contextId = contextId;
        this.responseBody = responseBody;
        this.messageId = messageId;
        this.isStream = isStream;
        this.isEnd = isEnd;
        this.taskId = taskId;
        this.serverWorkAgentResponseTopic = serverWorkAgentResponseTopic;
        this.serverLiteTopic = serverLiteTopic;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getServerWorkAgentResponseTopic() {
        return serverWorkAgentResponseTopic;
    }

    public String getServerLiteTopic() {
        return serverLiteTopic;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public void setServerWorkAgentResponseTopic(String serverWorkAgentResponseTopic) {
        this.serverWorkAgentResponseTopic = serverWorkAgentResponseTopic;
    }

    public void setServerLiteTopic(String serverLiteTopic) {
        this.serverLiteTopic = serverLiteTopic;
    }

    public RocketMQResponse() {}

    public String getLiteTopic() {
        return liteTopic;
    }

    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    public String getContextId() {
        return contextId;
    }

    public void setContextId(String contextId) {
        this.contextId = contextId;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public boolean isStream() {
        return isStream;
    }

    public void setStream(boolean stream) {
        isStream = stream;
    }

    public boolean isEnd() {
        return isEnd;
    }

    public void setEnd(boolean end) {
        isEnd = end;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }
}
