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
package org.example.model;

import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

/**
 * Encapsulates task context information for asynchronous stream processing.
 * <p>
 * Contains task metadata (id, description, user/session identifiers) and
 * a Sink for streaming real-time responses to the client.
 */
public class TaskInfo {
    /**
     * Unique identifier of the task.
     */
    private String taskId;

    /**
     * Human-readable description of the task.
     */
    private String taskDesc;

    /**
     * Unique identifier of the user who initiated the task.
     */
    private String userId;

    /**
     * Unique identifier of the session associated with the task.
     */
    private String sessionId;

    /**
     * A reactive sink used to push real-time response chunks to the client.
     */
    private Sinks.Many<String> sink;

    /**
     * Constructs a {@link TaskInfo} with basic metadata and a response sink.
     *
     * @param taskId    the unique task ID.
     * @param taskDesc  the task description.
     * @param userId    the user ID.
     * @param sessionId the session ID.
     * @param sink      the reactive sink for streaming responses.
     */
    public TaskInfo(String taskId, String taskDesc, String sessionId, String userId, Sinks.Many<String> sink) {
        this.taskId = taskId;
        this.taskDesc = taskDesc;
        this.sessionId = sessionId;
        this.userId = userId;
        this.sink = sink;
    }

    /**
     * Default constructor for {@link TaskInfo}.
     */
    public TaskInfo() {}

    /**
     * Gets the unique identifier of the task.
     *
     * @return the task ID.
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Sets the unique identifier of the task.
     *
     * @param taskId the task ID to set.
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Gets the human-readable description of the task.
     *
     * @return the task description.
     */
    public String getTaskDesc() {
        return taskDesc;
    }

    /**
     * Sets the human-readable description of the task.
     *
     * @param taskDesc the task description to set.
     */
    public void setTaskDesc(String taskDesc) {
        this.taskDesc = taskDesc;
    }

    /**
     * Gets the unique identifier of the session associated with the task.
     *
     * @return the session ID.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Sets the unique identifier of the session associated with the task.
     *
     * @param sessionId the session ID to set.
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Gets the unique identifier of the user who initiated the task.
     *
     * @return the user ID.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the unique identifier of the user who initiated the task.
     *
     * @param userId the user ID to set.
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Gets the reactive sink used to push real-time response chunks to the client.
     *
     * @return the reactive sink.
     */
    public Many<String> getSink() {
        return sink;
    }

    /**
     * Sets the reactive sink used to push real-time response chunks to the client.
     *
     * @param sink the reactive sink to set.
     */
    public void setSink(Many<String> sink) {
        this.sink = sink;
    }

    /**
     * Creates a new instance of the {@link Builder} class for constructing {@link TaskInfo} objects.
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing {@link TaskInfo} instances with a fluent API.
     */
    public static class Builder {
        private final TaskInfo taskInfo = new TaskInfo();

        /**
         * Sets the task ID for the {@link TaskInfo} being built.
         *
         * @param taskId the task ID to set.
         * @return the current {@link Builder} instance.
         */
        public Builder taskId(String taskId) {
            taskInfo.setTaskId(taskId);
            return this;
        }

        /**
         * Sets the task description for the {@link TaskInfo} being built.
         *
         * @param taskDesc the task description to set.
         * @return the current {@link Builder} instance.
         */
        public Builder taskDesc(String taskDesc) {
            taskInfo.setTaskDesc(taskDesc);
            return this;
        }

        /**
         * Sets the user ID for the {@link TaskInfo} being built.
         *
         * @param userId the user ID to set.
         * @return the current {@link Builder} instance.
         */
        public Builder userId(String userId) {
            taskInfo.setUserId(userId);
            return this;
        }

        /**
         * Sets the session ID for the {@link TaskInfo} being built.
         *
         * @param sessionId the session ID to set.
         * @return the current {@link Builder} instance.
         */
        public Builder sessionId(String sessionId) {
            taskInfo.setSessionId(sessionId);
            return this;
        }

        /**
         * Sets the reactive sink for the {@link TaskInfo} being built.
         *
         * @param sink the reactive sink to set.
         * @return the current {@link Builder} instance.
         */
        public Builder sink(Sinks.Many<String> sink) {
            taskInfo.setSink(sink);
            return this;
        }

        /**
         * Builds and returns a new {@link TaskInfo} instance with the configured properties.
         *
         * @return a new {@link TaskInfo} instance.
         */
        public TaskInfo build() {
            return taskInfo;
        }
    }
}
