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
package model;

/**
 * Represents a response message sent via RocketMQ from an AI agent service.
 * <p>
 * This class carries the result of processing a {@link RocketMQRequest}, including
 * the response payload, routing metadata, and stream control flags.
 */
public class RocketMQResponse {

    /**
     * The dedicated topic for receiving reply messages from the target agent.
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data
     * storage and isolation.
     */
    private String liteTopic;

    /**
     * The actual response body, typically a JSON string.
     */
    private String responseBody;

    /**
     * Indicates whether this response is part of a streaming sequence.
     * If {@code true}, the consumer should expect multiple messages until {@link #isEnd()} is {@code true}.
     */
    private boolean stream;

    /**
     * Indicates whether this is the final message in a streaming sequence.
     * Always {@code true} for non-streaming responses.
     */
    private boolean end;

    /**
     * Unique identifier of the user who initiated the request.
     */
    private String userId;

    /**
     * Unique task ID for tracking.
     */
    private String taskId;

    /**
     * The original user question (optional).
     */
    private String question;

    /**
     * construct a new RocketMQResponse instance
     * @param liteTopic The dedicated topic for receiving reply messages from the target agent.
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     * @param responseBody The actual response body, typically a JSON string.
     * @param stream Indicates whether this response is part of a streaming sequence.
     * If {@code true}, the consumer should expect multiple messages until {@link #isEnd()} is {@code true}.
     * @param end Indicates whether this is the final message in a streaming sequence. Always {@code true} for non-streaming responses.
     * @param userId Unique identifier of the user who initiated the request.
     * @param taskId Unique task ID for tracking.
     * @param question The original user question (optional).
     */
    public RocketMQResponse(String liteTopic, String responseBody, boolean stream, boolean end,
        String userId, String taskId, String question) {
        this.liteTopic = liteTopic;
        this.responseBody = responseBody;
        this.stream = stream;
        this.end = end;
        this.userId = userId;
        this.taskId = taskId;
        this.question = question;
    }

    public RocketMQResponse() {}

    public static Builder builder() {
        return new Builder();
    }

    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public boolean isEnd() {
        return end;
    }

    public void setEnd(boolean end) {
        this.end = end;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getLiteTopic() {
        return liteTopic;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public static class Builder {
        private String liteTopic;
        private String responseBody;
        private boolean stream;
        private boolean end;
        private String userId;
        private String taskId;
        private String question;

        public Builder liteTopic(String liteTopic) {
            this.liteTopic = liteTopic;
            return this;
        }

        public Builder responseBody(String responseBody) {
            this.responseBody = responseBody;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder end(boolean end) {
            this.end = end;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder question(String question) {
            this.question = question;
            return this;
        }

        public RocketMQResponse build() {
            return new RocketMQResponse(liteTopic, responseBody, stream, end, userId, taskId, question);
        }
    }
}
