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
package org.example.common;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

/**
 * Encapsulates task context information for asynchronous stream processing.
 * <p>
 * Contains task metadata (ID, description, user/session identifiers) and a Sink
 * for streaming real-time responses to the client.
 * </p>
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

    private String lastOutput = "";

    /**
     * A reactive sink used to push real-time response chunks to the client.
     */
    private Sinks.Many<String> sink;

    /**
     * Constructs a {@code TaskInfo} with basic metadata and a response sink.
     *
     * @param taskId     the unique task ID
     * @param taskDesc   the task description
     * @param userId     the user ID
     * @param sessionId  the session ID
     * @param sink       the reactive sink for streaming responses
     */
    public TaskInfo(String taskId, String taskDesc, String sessionId, String userId, Sinks.Many<String> sink) {
        this.taskId = taskId;
        this.taskDesc = taskDesc;
        this.sessionId = sessionId;
        this.userId = userId;
        this.sink = sink;
    }

    public TaskInfo() {}

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
}
