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
     * Constructs a new StreamingTaskHandle instance
     * @param taskId the unique ID of the task.
     * @param taskDesc the description or type of the task.
     * @param sessionId the session ID associated with this task.
     * @param userId the ID of the user who owns this task.
     * @param sink the sink used to emit real-time output to clients.
     * @param isRecover whether this task is resuming from a previous state.
     */
    public StreamingTaskHandle(String taskId, String taskDesc, String sessionId, String userId, Sinks.Many<String> sink, boolean isRecover) {
        this.taskId = taskId;
        this.taskDesc = taskDesc;
        this.sessionId = sessionId;
        this.userId = userId;
        this.sink = sink;
        this.isRecover = isRecover;
    }

    public boolean isComplete() {
        return isComplete;
    }

    public void setComplete(boolean complete) {
        isComplete = complete;
    }

    public StreamingTaskHandle() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getTaskDesc() {
        return taskDesc;
    }

    public void setTaskDesc(String taskDesc) {
        this.taskDesc = taskDesc;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Many<String> getSink() {
        return sink;
    }

    public void setSink(Many<String> sink) {
        this.sink = sink;
    }

    public String getLastOutput() {
        return lastOutput;
    }

    public void setLastOutput(String lastOutput) {
        this.lastOutput = lastOutput;
    }

    public boolean isRecover() {
        return isRecover;
    }

    public void setRecover(boolean recover) {
        isRecover = recover;
    }

    public static class Builder {
        private String taskId;
        private String taskDesc;
        private String sessionId;
        private String userId;
        private Sinks.Many<String> sink;
        private String lastOutput = "";
        private boolean isComplete = false;
        private boolean isRecover = false;

        public Builder taskId(String taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder taskDesc(String taskDesc) {
            this.taskDesc = taskDesc;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder sink(Sinks.Many<String> sink) {
            this.sink = sink;
            return this;
        }

        public Builder lastOutput(String lastOutput) {
            this.lastOutput = lastOutput;
            return this;
        }

        public Builder isComplete(boolean isComplete) {
            this.isComplete = isComplete;
            return this;
        }

        public Builder isRecover(boolean isRecover) {
            this.isRecover = isRecover;
            return this;
        }

        public StreamingTaskHandle build() {
            StreamingTaskHandle streamingTaskHandle = new StreamingTaskHandle(taskId, taskDesc, sessionId, userId, sink, isRecover);
            streamingTaskHandle.setLastOutput(lastOutput);
            streamingTaskHandle.setComplete(isComplete);
            return streamingTaskHandle;
        }
    }
}
