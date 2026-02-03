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

    public Mission() {}

    public Mission(String agent, String messageInfo, String taskId, String sessionId) {
        this.agent = agent;
        this.messageInfo = messageInfo;
        this.taskId = taskId;
        this.sessionId = sessionId;
    }

    public String getAgent() {
        return agent;
    }

    public void setAgent(String agent) {
        this.agent = agent;
    }

    public String getMessageInfo() {
        return messageInfo;
    }

    public void setMessageInfo(String messageInfo) {
        this.messageInfo = messageInfo;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String agent;
        private String messageInfo;
        private String taskId;
        private String sessionId;

        public Builder agent(String agent) {
            this.agent = agent;
            return this;
        }

        public Builder messageInfo(String messageInfo) {
            this.messageInfo = messageInfo;
            return this;
        }

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Mission build() {
            return new Mission(agent, messageInfo, taskId, sessionId);
        }
    }
}
