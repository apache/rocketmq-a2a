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
 * Encapsulates an A2A (Agent-to-Agent) protocol response message delivered via RocketMQ.
 *
 * <p>This class is used to return results from one agent to another in asynchronous or streaming scenarios.
 * It supports:
 * <ul>
 *   <li>Standard request-response via {@link #responseBody}</li>
 *   <li>Streaming responses using {@link #isStream} and {@link #isEnd} flags</li>
 *   <li>Session correlation via {@link #contextId}</li>
 *   <li>Message acknowledgment via {@link #messageId}</li>
 *   <li>Client-server affinity through {@link #serverLiteTopic} and {@link #serverWorkAgentResponseTopic}</li>
 * </ul>
 *
 * <p><strong>Note:</strong> For streaming responses, multiple {@code RocketMQResponse} messages may be sent,
 * ending with one where {@code isEnd = true}.
 */
public class RocketMQResponse {

    //The LiteTopic subscribed to by the client
    //todo
    private String liteTopic;

    /**
     * Context ID, used to associate a complete A2A request-response session.
     * Must not be null.
     */
    private String contextId;

    /**
     * Response body content, typically serialized business data (e.g., JSON).
     */
    private String responseBody;

    /**
     * Task ID, which identifies the specific task corresponding to this A2A operation.
     */
    private String taskId;

    /**
     * The message ID obtained by the RocketMQ client upon successfully sending an A2A request.
     * Used for message tracking and acknowledgment.
     */
    private String messageId;

    /**
     * The response topic used by the server for asynchronous replies.
     * Optional, used for routing follow-up requests.
     */
    private String serverWorkAgentResponseTopic;

    /**
     * The lite topic used by the server to indicate that subsequent requests of a specific type
     * should be routed back to this particular server instance (sticky session).
     * Optional. todo
     */
    private String serverLiteTopic;

    /**
     * Whether this response is part of a streaming sequence.
     */
    private boolean isStream;

    /**
     * Whether this is the final message in a streaming sequence.
     */
    private boolean isEnd;

    /**
     * Creates a basic RocketMQResponse instance for simple responses.
     *
     * @param liteTopic the LiteTopic subscribed by the client
     * @param contextId session correlation ID
     * @param responseBody the response payload (e.g., JSON)
     * @param messageId the original request message ID for acknowledgment
     * @param isStream true if this is a streaming response
     * @param isEnd true if this is the last message in the stream
     */
    public RocketMQResponse(String liteTopic, String contextId, String responseBody, String messageId, boolean isStream, boolean isEnd) {
        this.liteTopic = liteTopic;
        this.contextId = contextId;
        this.responseBody = responseBody;
        this.messageId = messageId;
        this.isStream = isStream;
        this.isEnd = isEnd;
    }

    /**
     * Creates a full RocketMQResponse with task and server routing information.
     * Suitable for complex workflows requiring sticky sessions or callback routing.
     */
    public RocketMQResponse(String liteTopic, String contextId, String responseBody, String messageId, boolean isStream, boolean isEnd, String taskId, String serverWorkAgentResponseTopic, String serverLiteTopic) {
        this(liteTopic, contextId, responseBody, messageId, isStream, isEnd);
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
