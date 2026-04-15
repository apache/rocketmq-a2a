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
 * ServerReceipt represents the server's receipt information for client-server interactions.
 * It encapsulates details such as the response topic and lite topic used for communication.
 */
public class ServerReceipt {
    /**
     * The response topic designated for this specific client-server interaction,
     * used to achieve a "sticky" communication pattern where all replies from the server
     * are sent back through this dedicated channel.
     */
    private final String serverWorkAgentResponseTopic;

    /**
     * Typically, a serverLiteTopic that is bound to {@code #serverWorkAgentResponseTopic}.
     * LiteTopic is a session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     */
    private final String serverLiteTopic;

    /**
     * Constructs a ServerReceipt instance with the specified response topic and lite topic.
     *
     * @param serverWorkAgentResponseTopic The response topic for server communication.
     * @param serverLiteTopic              The lite topic associated with the response topic.
     */
    public ServerReceipt(String serverWorkAgentResponseTopic, String serverLiteTopic) {
        this.serverWorkAgentResponseTopic = serverWorkAgentResponseTopic;
        this.serverLiteTopic = serverLiteTopic;
    }

    /**
     * Returns the server work agent response topic.
     *
     * @return The response topic used for server communication.
     */
    public String getServerWorkAgentResponseTopic() {
        return serverWorkAgentResponseTopic;
    }

    /**
     * Returns the server lite topic.
     *
     * @return The lite topic associated with the response topic.
     */
    public String getServerLiteTopic() {
        return serverLiteTopic;
    }

    /**
     * Creates a new Builder instance for constructing ServerReceipt objects.
     *
     * @return A new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing ServerReceipt instances in a fluent manner.
     */
    public static class Builder {
        private String serverWorkAgentResponseTopic;
        private String serverLiteTopic;

        /**
         * Sets the server work agent response topic for the ServerReceipt being built.
         *
         * @param serverWorkAgentResponseTopic The response topic for server communication.
         * @return This Builder instance for method chaining.
         */
        public Builder serverWorkAgentResponseTopic(String serverWorkAgentResponseTopic) {
            this.serverWorkAgentResponseTopic = serverWorkAgentResponseTopic;
            return this;
        }

        /**
         * Sets the server lite topic for the ServerReceipt being built.
         *
         * @param serverLiteTopic The lite topic associated with the response topic.
         * @return This Builder instance for method chaining.
         */
        public Builder serverLiteTopic(String serverLiteTopic) {
            this.serverLiteTopic = serverLiteTopic;
            return this;
        }

        /**
         * Builds and returns a new ServerReceipt instance with the configured properties.
         *
         * @return A new ServerReceipt instance.
         */
        public ServerReceipt build() {
            return new ServerReceipt(serverWorkAgentResponseTopic, serverLiteTopic);
        }
    }
}
