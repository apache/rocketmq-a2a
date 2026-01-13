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
 * RocketMQTransport Provider
 */
public class RocketMQTransportProvider implements ClientTransportProvider<RocketMQTransport, RocketMQTransportConfig> {

    /**
     * Create a client transport based RocketMQ
     * @param clientTransportConfig the client transport config to use
     * @param agentCard agentCard Info
     * @param agentUrl the remote agent's URL
     * @return RocketMQTransport
     * @throws A2AClientException A2AClientException
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
     * Get the name of the client transport
     * @return ROCKETMQ_PROTOCOL
     */
    @Override
    public String getTransportProtocol() {
        return ROCKETMQ_PROTOCOL;
    }

    @Override
    public Class<RocketMQTransport> getTransportProtocolClass() {
        return RocketMQTransport.class;
    }
}
