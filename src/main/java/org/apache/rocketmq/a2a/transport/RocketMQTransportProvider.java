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
package org.apache.rocketmq.a2a.transport;

import io.a2a.client.http.JdkA2AHttpClient;
import io.a2a.client.transport.spi.ClientTransportProvider;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.ROCKETMQ_PROTOCOL;

/**
 * A provider for creating {@link RocketMQTransport} instances used in A2A client communication.
 * <p>
 * This class implements the {@link ClientTransportProvider} interface to support
 * RocketMQ-based message transport between A2A clients and remote agents.
 * It is responsible for instantiating transports with the appropriate configuration,
 * agent identity, and endpoint information.
 */
public class RocketMQTransportProvider implements ClientTransportProvider<RocketMQTransport, RocketMQTransportConfig> {

    /**
     * Creates a new {@link RocketMQTransport} instance using the provided configuration and agent details.
     * <p>
     * If the given {@code clientTransportConfig} is {@code null}, a default configuration
     * will be created using a standard {@link JdkA2AHttpClient}.
     *
     * @param clientTransportConfig the transport configuration (may be {@code null})
     * @param agentCard             the agent's identity and metadata
     * @param agentUrl              the remote agent's endpoint URL (used for routing or diagnostics)
     * @return a configured {@link RocketMQTransport} instance
     * @throws A2AClientException if transport creation fails due to invalid configuration or initialization error
     */
    @Override
    public RocketMQTransport create(RocketMQTransportConfig clientTransportConfig, AgentCard agentCard, String agentUrl) throws
        A2AClientException {
        if (clientTransportConfig == null) {
            clientTransportConfig = new RocketMQTransportConfig(new JdkA2AHttpClient());
        }
        return new RocketMQTransport(clientTransportConfig.getNamespace(), clientTransportConfig.getAccessKey(), clientTransportConfig.getSecretKey(), clientTransportConfig.getWorkAgentResponseTopic(), clientTransportConfig.getWorkAgentResponseGroupID(), clientTransportConfig.getInterceptors(), clientTransportConfig.getAgentUrl(), clientTransportConfig.getHttpClient(), clientTransportConfig.getLiteTopic(), clientTransportConfig.isUseDefaultRecoverMode(), agentCard);
    }

    /**
     * Returns the protocol name associated with this transport provider.
     *
     * @return the constant {@link #ROCKETMQ_PROTOCOL}, identifying the RocketMQ-based A2A transport
     */
    @Override
    public String getTransportProtocol() {
        return ROCKETMQ_PROTOCOL;
    }

    /**
     * Returns the concrete transport implementation class managed by this provider.
     *
     * @return the {@link RocketMQTransport} class
     */
    @Override
    public Class<RocketMQTransport> getTransportProtocolClass() {
        return RocketMQTransport.class;
    }
}
