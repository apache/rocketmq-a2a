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
 * Represents a request sent from a client to an AI agent via RocketMQ.
 *
 * <p>Encapsulates the user's input, target agent address, session context,
 * and routing topics required for asynchronous reply handling.
 */
public class RocketMQRequest {
    /**
     * The user's input question to the AI agent.
     */
    private String question;

    /**
     * The RocketMQ topic used by the agent service to receive this request.
     */
    private String agentTopic;

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
     * Unique identifier of the user initiating the request.
     */
    private String userId;

    /**
     * Unique identifier for tracking this specific task or conversation.
     */
    private String taskId;

    /**
     * Construct a new RocketMQRequest instance
     *
     * @param question the user's input question to the AI agent.
     * @param agentTopic the RocketMQ topic used by the agent service to receive this request.
     * @param workAgentResponseTopic the dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     * @param userId the unique identifier of the user initiating the request.
     * @param liteTopic the dedicated topic for receiving reply messages from the target agent.
     * typically, a liteTopic that is bound to {@link #workAgentResponseTopic}.
     * liteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     * @param taskId unique identifier for tracking this specific task or conversation.
     */
    public RocketMQRequest(String question, String agentTopic, String workAgentResponseTopic, String userId, String liteTopic, String taskId) {
        this.question = question;
        this.agentTopic = agentTopic;
        this.workAgentResponseTopic = workAgentResponseTopic;
        this.userId = userId;
        this.liteTopic = liteTopic;
        this.taskId = taskId;
    }

    public RocketMQRequest() {}

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

    public String getAgentTopic() {
        return agentTopic;
    }

    public void setAgentTopic(String agentTopic) {
        this.agentTopic = agentTopic;
    }

    public String getWorkAgentResponseTopic() {
        return workAgentResponseTopic;
    }

    public void setWorkAgentResponseTopic(String workAgentResponseTopic) {
        this.workAgentResponseTopic = workAgentResponseTopic;
    }

    public String getLiteTopic() {
        return liteTopic;
    }

    public void setLiteTopic(String liteTopic) {
        this.liteTopic = liteTopic;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String question;
        private String agentTopic;
        private String workAgentResponseTopic;
        private String userId;
        private String liteTopic;
        private String taskId;

        public Builder question(String question) {
            this.question = question;
            return this;
        }

        public Builder agentTopic(String agentTopic) {
            this.agentTopic = agentTopic;
            return this;
        }

        public Builder workAgentResponseTopic(String workAgentResponseTopic) {
            this.workAgentResponseTopic = workAgentResponseTopic;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder liteTopic(String liteTopic) {
            this.liteTopic = liteTopic;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public RocketMQRequest build() {
            return new RocketMQRequest(question, agentTopic, workAgentResponseTopic, userId, liteTopic, taskId);
        }
    }
}
