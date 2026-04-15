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

    /**
     * Gets the endpoint of the RocketMQ service.
     *
     * @return the endpoint as a String.
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Sets the endpoint of the RocketMQ service.
     *
     * @param endpoint the endpoint to set.
     */
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    /**
     * Gets the topic of the RocketMQ service.
     *
     * @return the topic as a String.
     */
    public String getTopic() {
        return topic;
    }

    /**
     * Sets the topic of the RocketMQ service.
     *
     * @param topic the topic to set.
     */
    public void setTopic(String topic) {
        this.topic = topic;
    }

    /**
     * Gets the namespace of the RocketMQ service.
     *
     * @return the namespace as a String.
     */
    public String getNamespace() {
        return namespace;
    }

    /**
     * Sets the namespace of the RocketMQ service.
     *
     * @param namespace the namespace to set.
     */
    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    /**
     * Creates a new Builder instance for constructing RocketMQResource objects.
     *
     * @return a new Builder instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for constructing RocketMQResource instances.
     */
    public static class Builder {
        private final RocketMQResource info = new RocketMQResource();

        /**
         * Sets the namespace for the RocketMQResource being built.
         *
         * @param namespace the namespace to set.
         * @return the current Builder instance.
         */
        public Builder namespace(String namespace) {
            info.setNamespace(namespace);
            return this;
        }

        /**
         * Sets the endpoint for the RocketMQResource being built.
         *
         * @param endpoint the endpoint to set.
         * @return the current Builder instance.
         */
        public Builder endpoint(String endpoint) {
            info.setEndpoint(endpoint);
            return this;
        }

        /**
         * Sets the topic for the RocketMQResource being built.
         *
         * @param topic the topic to set.
         * @return the current Builder instance.
         */
        public Builder topic(String topic) {
            info.setTopic(topic);
            return this;
        }

        /**
         * Builds and returns the constructed RocketMQResource instance.
         *
         * @return the constructed RocketMQResource instance.
         */
        public RocketMQResource build() {
            return info;
        }
    }
}
