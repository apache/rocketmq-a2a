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

public class TaskInfo {
    private String taskId;
    private String taskDesc;
    private String userId;
    private String sessionId;
    private Sinks.Many<String> sink;

    public TaskInfo(String taskId, String taskDesc, String sessionId, String userId, Sinks.Many<String> sink) {
        this.taskId = taskId;
        this.taskDesc = taskDesc;
        this.sessionId = sessionId;
        this.userId = userId;
        this.sink = sink;
    }

    public TaskInfo() {
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
}
