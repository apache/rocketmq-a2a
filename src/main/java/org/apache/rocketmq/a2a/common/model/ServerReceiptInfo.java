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
package org.apache.rocketmq.a2a.common.model;

/**
 * Server receipt information
 */
public class ServerReceiptInfo {
    /**
     * The response topic designated for this specific client-server interaction,
     * used to achieve a "sticky" communication pattern where all replies from the server
     * are sent back through this dedicated channel.
     */
    private String serverWorkAgentResponseTopic;

    /**
     * Typically, a serverLiteTopic that is bound to {@code #serverWorkAgentResponseTopic}.
     * LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     */
    private String serverLiteTopic;

    public ServerReceiptInfo(String serverWorkAgentResponseTopic, String serverLiteTopic) {
        this.serverWorkAgentResponseTopic = serverWorkAgentResponseTopic;
        this.serverLiteTopic = serverLiteTopic;
    }

    public String getServerWorkAgentResponseTopic() {
        return serverWorkAgentResponseTopic;
    }

    public String getServerLiteTopic() {
        return serverLiteTopic;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String serverWorkAgentResponseTopic;
        private String serverLiteTopic;

        public Builder serverWorkAgentResponseTopic(String serverWorkAgentResponseTopic) {
            this.serverWorkAgentResponseTopic = serverWorkAgentResponseTopic;
            return this;
        }

        public Builder serverLiteTopic(String serverLiteTopic) {
            this.serverLiteTopic = serverLiteTopic;
            return this;
        }

        public ServerReceiptInfo build() {
            if (serverWorkAgentResponseTopic == null || serverWorkAgentResponseTopic.trim().isEmpty()) {
                throw new IllegalArgumentException("serverWorkAgentResponseTopic must not be null or empty");
            }
            if (serverLiteTopic == null || serverLiteTopic.trim().isEmpty()) {
                throw new IllegalArgumentException("serverLiteTopic must not be null or empty");
            }
            return new ServerReceiptInfo(serverWorkAgentResponseTopic, serverLiteTopic);
        }
    }
}
