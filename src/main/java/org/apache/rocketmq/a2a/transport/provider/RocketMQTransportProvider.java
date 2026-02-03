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
package org.apache.rocketmq.a2a.transport.provider;
import io.a2a.client.transport.spi.ClientTransportProvider;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import org.apache.rocketmq.a2a.transport.impl.RocketMQTransport;
import org.apache.rocketmq.a2a.transport.config.RocketMQTransportConfig;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.ROCKETMQ_PROTOCOL;

/**
 * A provider for creating {@link RocketMQTransport} instances used in A2A client communication.
 * <p>
 * This class implements the {@link ClientTransportProvider} interface to support
 * RocketMQ-based message transport between A2A clients and remote agents.
 * It is responsible for instantiating transports with the appropriate configuration, agent identity, and endpoint information.
 */
public class RocketMQTransportProvider implements ClientTransportProvider<RocketMQTransport, RocketMQTransportConfig> {

    /**
     * Creates a {@link RocketMQTransport} instance with the given configuration and agent metadata.
     *
     * @param clientTransportConfig transport configuration.
     * @param agentCard agent identity and metadata.
     * @param agentUrl remote agent endpoint.
     * @return configured RocketMQTransport instance.
     * @throws A2AClientException if transport creation fails.
     * @throws IllegalArgumentException if clientTransportConfig is null.
     */
    @Override
    public RocketMQTransport create(RocketMQTransportConfig clientTransportConfig, AgentCard agentCard, String agentUrl)
        throws A2AClientException {
        if (clientTransportConfig == null) {
            throw new IllegalArgumentException("RocketMQTransportProvider create RocketMQTransport param error, clientTransportConfig is null");
        }
        return new RocketMQTransport(clientTransportConfig, agentCard);
    }

    /**
     * Returns the protocol name associated with this transport provider.
     *
     * @return the constant ROCKETMQ_PROTOCOL, identifying the RocketMQ-based A2A transport.
     */
    @Override
    public String getTransportProtocol() {
        return ROCKETMQ_PROTOCOL;
    }

    /**
     * Returns the concrete transport implementation class managed by this provider.
     *
     * @return the {@link RocketMQTransport} class.
     */
    @Override
    public Class<RocketMQTransport> getTransportProtocolClass() {
        return RocketMQTransport.class;
    }
}
