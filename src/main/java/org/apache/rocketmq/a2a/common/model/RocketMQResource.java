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
 * Encapsulates RocketMQ resource information, including endpoint, namespace, and topic.
 * Used for routing and connecting to specific RocketMQ clusters.
 */
public class RocketMQResource {
    /**
     * The namespace of RocketMQ service.
     */
    private String namespace;

    /**
     * The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster.
     */
    private String endpoint;

    /**
     * RocketMQ topic resource.
     */
    private String topic;

    /**
     * Constructs a new RocketMQResource.
     */
    public RocketMQResource() {}

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final RocketMQResource info = new RocketMQResource();

        public Builder namespace(String namespace) {
            info.setNamespace(namespace);
            return this;
        }

        public Builder endpoint(String endpoint) {
            info.setEndpoint(endpoint);
            return this;
        }

        public Builder topic(String topic) {
            info.setTopic(topic);
            return this;
        }

        public RocketMQResource build() {
            return info;
        }
    }
}
