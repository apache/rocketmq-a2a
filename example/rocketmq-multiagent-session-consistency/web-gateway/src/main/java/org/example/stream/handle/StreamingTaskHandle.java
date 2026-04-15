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
package org.example.stream.handle;

import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

/**
 * Represents the runtime execution context of a streamable task — such as an LLM (Large Language Model)
 * generation, data processing, or asynchronous workflow.
 *
 * <p>This class holds all necessary metadata and communication components for managing the lifecycle of a task,
 * including user/session association, progress tracking, recovery state, and real-time output streaming.
 */
public class StreamingTaskHandle {
    /**
     * The ID of the user who initiated this task.
     */
    private String userId;

    /**
     * The session ID associated with this task. Used to group related tasks within a conversation or interaction.
     */
    private String sessionId;

    /**
     * A unique identifier for this specific task instance.
     */
    private String taskId;

    /**
     * A human-readable description or type of the task.
     */
    private String taskDesc;

    /**
     * The last output produced by this task before interruption.
     * Used during recovery to resume from the correct point or avoid duplication.
     */
    private String lastOutput = "";

    /**
     * Flag indicating whether the task has completed its execution.
     * Can be observed externally to track progress.
     */
    private boolean isComplete = false;

    /**
     * Flag indicating whether this task is being executed in recovery mode.
     * If {@code true}, the system should attempt to restore state and continue from where it left off.
     */
    private boolean isRecover = false;

    /**
     * A reactive sink for pushing string-based messages (e.g., tokens, lines) to subscribers in real time.
     * Typically used with Server-Sent Events (SSE) to stream results to the client.
     *
     * @see Sinks.Many
     */
    private Sinks.Many<String> sink;

    /**
     * Constructs a new StreamingTaskHandle instance.
     *
     * @param taskId the unique ID of the task
     * @param taskDesc the description or type of the task
     * @param sessionId the session ID associated with this task
     * @param userId the ID of the user who owns this task
     * @param sink the sink used to emit real-time output to clients
     * @param isRecover whether this task is resuming from a previous state
     */
    public StreamingTaskHandle(String taskId, String taskDesc, String sessionId, String userId, Sinks.Many<String> sink, boolean isRecover) {
        this.taskId = taskId;
        this.taskDesc = taskDesc;
        this.sessionId = sessionId;
        this.userId = userId;
        this.sink = sink;
        this.isRecover = isRecover;
    }

    /**
     * Checks if the task has completed.
     *
     * @return {@code true} if the task is complete, {@code false} otherwise
     */
    public boolean isComplete() {
        return isComplete;
    }

    /**
     * Sets the completion status of the task.
     *
     * @param complete {@code true} to mark the task as complete, {@code false} otherwise
     */
    public void setComplete(boolean complete) {
        isComplete = complete;
    }

    /**
     * Default constructor for StreamingTaskHandle.
     */
    public StreamingTaskHandle() {
    }

    /**
     * Returns a new Builder instance for creating StreamingTaskHandle objects.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the task ID.
     *
     * @return the task ID
     */
    public String getTaskId() {
        return taskId;
    }

    /**
     * Sets the task ID.
     *
     * @param taskId the task ID to set
     */
    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    /**
     * Gets the task description.
     *
     * @return the task description
     */
    public String getTaskDesc() {
        return taskDesc;
    }

    /**
     * Sets the task description.
     *
     * @param taskDesc the task description to set
     */
    public void setTaskDesc(String taskDesc) {
        this.taskDesc = taskDesc;
    }

    /**
     * Gets the session ID.
     *
     * @return the session ID
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Sets the session ID.
     *
     * @param sessionId the session ID to set
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * Gets the user ID.
     *
     * @return the user ID
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Sets the user ID.
     *
     * @param userId the user ID to set
     */
    public void setUserId(String userId) {
        this.userId = userId;
    }

    /**
     * Gets the reactive sink used for streaming output.
     *
     * @return the sink
     */
    public Many<String> getSink() {
        return sink;
    }

    /**
     * Sets the reactive sink used for streaming output.
     *
     * @param sink the sink to set
     */
    public void setSink(Many<String> sink) {
        this.sink = sink;
    }

    /**
     * Gets the last output produced by the task.
     *
     * @return the last output
     */
    public String getLastOutput() {
        return lastOutput;
    }

    /**
     * Sets the last output produced by the task.
     *
     * @param lastOutput the last output to set
     */
    public void setLastOutput(String lastOutput) {
        this.lastOutput = lastOutput;
    }

    /**
     * Checks if the task is running in recovery mode.
     *
     * @return {@code true} if in recovery mode, {@code false} otherwise
     */
    public boolean isRecover() {
        return isRecover;
    }

    /**
     * Sets the recovery mode flag.
     *
     * @param recover {@code true} to enable recovery mode, {@code false} otherwise
     */
    public void setRecover(boolean recover) {
        isRecover = recover;
    }

    /**
     * Builder class for constructing StreamingTaskHandle instances.
     */
    public static class Builder {
        private StreamingTaskHandle streamingTaskHandle = new StreamingTaskHandle();

        /**
         * Sets the task ID for the StreamingTaskHandle being built.
         *
         * @param taskId the task ID.
         * @return this Builder instance.
         */
        public Builder taskId(String taskId) {
            streamingTaskHandle.setTaskId(taskId);
            return this;
        }

        /**
         * Sets the task description for the StreamingTaskHandle being built.
         *
         * @param taskDesc the task description.
         * @return this Builder instance.
         */
        public Builder taskDesc(String taskDesc) {
            streamingTaskHandle.setTaskDesc(taskDesc);
            return this;
        }

        /**
         * Sets the session ID for the StreamingTaskHandle being built.
         *
         * @param sessionId the session ID.
         * @return this Builder instance.
         */
        public Builder sessionId(String sessionId) {
            streamingTaskHandle.setSessionId(sessionId);
            return this;
        }

        /**
         * Sets the user ID for the StreamingTaskHandle being built.
         *
         * @param userId the user ID.
         * @return this Builder instance.
         */
        public Builder userId(String userId) {
            streamingTaskHandle.setUserId(userId);
            return this;
        }

        /**
         * Sets the reactive sink for the StreamingTaskHandle being built.
         *
         * @param sink the sink.
         * @return this Builder instance.
         */
        public Builder sink(Sinks.Many<String> sink) {
            streamingTaskHandle.setSink(sink);
            return this;
        }

        /**
         * Sets the recovery mode flag for the StreamingTaskHandle being built.
         *
         * @param isRecover {@code true} to enable recovery mode, {@code false} otherwise
         * @return this Builder instance.
         */
        public Builder isRecover(boolean isRecover) {
            streamingTaskHandle.setRecover(isRecover);
            return this;
        }

        /**
         * Returns a new StreamingTaskHandle instance with the configured properties.
         *
         * @return a new StreamingTaskHandle instance.
         */
        public StreamingTaskHandle build() {
            return streamingTaskHandle;
        }
    }
}
