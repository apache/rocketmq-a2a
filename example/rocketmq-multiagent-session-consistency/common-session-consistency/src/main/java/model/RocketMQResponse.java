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
 */
public class RocketMQResponse {

    /**
     * The dedicated topic for receiving reply messages from the target agent.
     * LiteTopic is a session identifier, similar to a SessionId, dynamically created at runtime for data
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
     * Constructs a new RocketMQResponse instance.
     *
     * @param liteTopic the dedicated topic for receiving reply messages from the target agent.
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at
     * runtime for data storage and isolation.
     * @param responseBody the actual response body, typically a JSON string.
     * @param stream indicates whether this response is part of a streaming sequence.
     * if {@code true}, the consumer should expect multiple messages until {@link #isEnd()} is {@code true}.
     * @param end indicates whether this is the final message in a streaming sequence. Always {@code true} for
     * non-streaming responses.
     * @param userId unique identifier of the user who initiated the request.
     * @param taskId unique task ID for tracking.
     * @param question the original user question (optional).
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

    /**
     * Default constructor.
     */
    public RocketMQResponse() {}

    /**
     * Returns a new Builder instance for fluent construction.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Sets the lite topic.
     */
    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    /**
     * Sets the response body.
     */
    public void setResponseBody(String responseBody) {
        this.responseBody = responseBody;
    }

    /**
     * Checks if this response is part of a streaming sequence.
     */
    public boolean isStream() {
        return stream;
    }

    /**
     * Sets whether this response is part of a streaming sequence.
     */
    public void setStream(boolean stream) {
        this.stream = stream;
    }

    /**
     * Checks if this is the final message in a streaming sequence.
     */
    public boolean isEnd() {
        return end;
    }

    /**
     * Sets whether this is the final message in a streaming sequence.
     */
    public void setEnd(boolean end) {
        this.end = end;
    }

    /**
     * Sets the task ID.
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Gets the lite topic.
     */
    public String getLiteTopic() {
        return liteTopic;
    }

    /**
     * Gets the response body.
     */
    public String getResponseBody() {
        return responseBody;
    }

    /**
     * Gets the task ID.
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Gets the user ID.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user ID.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Gets the original user question.
     */
    public String getQuestion() {
        return question;
    }

    /**
     * Sets the original user question.
     */
    public void setQuestion(String question) {
        this.question = question;
    }

    /**
     * Builder class for constructing RocketMQResponse instances fluently.
     */
    public static class Builder {
        private final RocketMQResponse rocketMQResponse = new RocketMQResponse();

        /**
         * Sets the lite topic.
         */
        public Builder liteTopic(String liteTopic) {
            rocketMQResponse.setLiteTopic(liteTopic);
            return this;
        }

        /**
         * Sets the response body.
         */
        public Builder responseBody(String responseBody) {
            rocketMQResponse.setResponseBody(responseBody);
            return this;
        }

        /**
         * Sets whether this response is part of a streaming sequence.
         */
        public Builder stream(boolean stream) {
            rocketMQResponse.setStream(stream);
            return this;
        }

        /**
         * Sets whether this is the final message in a streaming sequence.
         */
        public Builder end(boolean end) {
            rocketMQResponse.setEnd(end);
            return this;
        }

        /**
         * Sets the user ID.
         */
        public Builder userId(String userId) {
            rocketMQResponse.setUserId(userId);
            return this;
        }

        /**
         * Sets the task ID.
         */
        public Builder taskId(String taskId) {
            rocketMQResponse.setTaskId(taskId);
            return this;
        }

        /**
         * Sets the original user question.
         */
        public Builder question(String question) {
            rocketMQResponse.setQuestion(question);
            return this;
        }

        /**
         * Returns a new RocketMQResponse instance.
         */
        public RocketMQResponse build() {
            return rocketMQResponse;
        }
    }
}
