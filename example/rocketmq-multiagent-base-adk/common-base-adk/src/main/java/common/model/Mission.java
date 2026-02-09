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
package common.model;

/**
 * Represents a mission (or task assignment) sent from a client to an agent in the A2A system.
 * <p>
 * A mission encapsulates the essential metadata required to route and process a request,
 * including the target agent, message payload, task identifier, and session context.
 */
public class Mission {
    /**
     * The identifier of the target agent responsible for executing this mission.
     */
    private String agent;

    /**
     * The question to be processed by the agent.
     */
    private String messageInfo;

    /**
     * A unique identifier for the task associated with this mission.
     */
    private String taskId;

    /**
     * The session ID used to correlate related missions within the same user or client session.
     */
    private String sessionId;

    /**
     * Default constructor for creating an empty Mission instance.
     */
    public Mission() {}

    /**
     * Constructs a Mission with the specified agent, message info, task ID, and session ID.
     *
     * @param agent the identifier of the target agent
     * @param messageInfo the question or message to be processed by the agent
     * @param taskId the unique identifier for the task
     * @param sessionId the session ID for correlating related missions
     */
    public Mission(String agent, String messageInfo, String taskId, String sessionId) {
        this.agent = agent;
        this.messageInfo = messageInfo;
        this.taskId = taskId;
        this.sessionId = sessionId;
    }

    /**
     * Returns the identifier of the target agent.
     *
     * @return the agent identifier.
     */
    public String getAgent() {
        return agent;
    }

    /**
     * Sets the identifier of the target agent.
     *
     * @param agent the agent identifier to set.
     */
    public void setAgent(String agent) {
        this.agent = agent;
    }

    /**
     * Returns the message or question to be processed by the agent.
     *
     * @return the message info.
     */
    public String getMessageInfo() {
        return messageInfo;
    }

    /**
     * Sets the message or question to be processed by the agent.
     *
     * @param messageInfo the message info to set.
     */
    public void setMessageInfo(String messageInfo) {
        this.messageInfo = messageInfo;
    }

    /**
     * Returns the unique identifier for the task.
     *
     * @return the task ID.
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Sets the unique identifier for the task.
     *
     * @param taskId the task ID to set.
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Returns the session ID used to correlate related missions.
     *
     * @return the session ID.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Sets the session ID used to correlate related missions.
     *
     * @param sessionId the session ID to set.
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Returns a new Builder instance for constructing a Mission object.
     *
     * @return a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing Mission objects with a fluent API.
     */
    public static class Builder {
        private final Mission mission = new Mission();

        /**
         * Sets the agent identifier for the mission.
         *
         * @param agent the agent identifier.
         * @return the Builder instance for method chaining.
         */
        public Builder agent(String agent) {
            mission.setAgent(agent);
            return this;
        }

        /**
         * Sets the message or question for the mission.
         *
         * @param messageInfo the message info.
         * @return the Builder instance for method chaining.
         */
        public Builder messageInfo(String messageInfo) {
            mission.setMessageInfo(messageInfo);
            return this;
        }

        /**
         * Sets the task ID for the mission.
         *
         * @param taskId the task ID.
         * @return the Builder instance for method chaining.
         */
        public Builder taskId(String taskId) {
            mission.setTaskId(taskId);
            return this;
        }

        /**
         * Sets the session ID for the mission.
         *
         * @param sessionId the session ID.
         * @return the Builder instance for method chaining.
         */
        public Builder sessionId(String sessionId) {
            mission.setSessionId(sessionId);
            return this;
        }

        /**
         * returns a new Mission instance with the configured properties.
         *
         * @return a new Mission instance.
         */
        public Mission build() {
            return mission;
        }
    }
}
