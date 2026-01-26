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
package agent;

import java.util.Collections;
import java.util.List;
import io.a2a.server.PublicAgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.commons.lang3.StringUtils;

import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.ROCKETMQ_PROTOCOL;

/**
 * Producer for the public {@link AgentCard} that describes this service's capabilities.
 * <p>
 * This card is used in A2A (Agent-to-Agent) ecosystems to enable discovery, interoperability,
 * and invocation of this agent’s skills (e.g., travel planning with weather awareness).
 * </p>
 * <p>
 * The card includes:
 * - Name, description, version
 * - Supported protocols
 * - Streaming and notification capabilities
 * - Skill definitions and usage examples
 * - Endpoint URL built from system properties
 * </p>
 * <p>
 * Requires the following system properties to be set:
 * - {@code rocketMQEndpoint}
 * - {@code rocketMQNamespace}
 * - {@code bizTopic}
 * </p>
 */
@ApplicationScoped
public class AgentCardProducer {
    // Required configuration keys
    private static final String PROP_ENDPOINT = "rocketMQEndpoint";
    private static final String PROP_NAMESPACE = "rocketMQNamespace";
    private static final String PROP_TOPIC = "bizTopic";

    // Read from system properties
    private static final String ROCKETMQ_ENDPOINT = System.getProperty(PROP_ENDPOINT, "");
    private static final String ROCKETMQ_NAMESPACE = System.getProperty(PROP_NAMESPACE, "");
    private static final String BIZ_TOPIC = System.getProperty(PROP_TOPIC, "");

    /**
     * Produces the public agent card used for service discovery and capability negotiation.
     * @return fully configured {@link AgentCard}
     * @throws IllegalArgumentException if required config is missing
     */
    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {

        return new AgentCard.Builder()
                .name("行程规划助手Agent")
                .description("擅长按照天气的信息帮助用户制定旅行等规划")
                .url(buildRocketMQUrl())
                .version("1.0.0")
                .documentationUrl("http://example.com/docs")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(true)
                        .pushNotifications(true)
                        .stateTransitionHistory(true)
                        .build())
                .defaultInputModes(Collections.singletonList("text"))
                .defaultOutputModes(Collections.singletonList("text"))
                .skills(Collections.singletonList(new AgentSkill.Builder()
                                .id("行程规划助手Agent")
                                .name("行程规划助手Agent")
                                .description("擅长按照天气的信息帮助用户制定旅行等规划")
                                .tags(Collections.singletonList("智能出行规划助手"))
                                .examples(List.of("帮我做一个明天杭州周边自驾游的规划"))
                                .build()))
                .preferredTransport(ROCKETMQ_PROTOCOL)
                .protocolVersion("0.3.0")
                .build();
    }

    private static String buildRocketMQUrl() {
        if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT) || StringUtils.isEmpty(BIZ_TOPIC)) {
            throw new IllegalArgumentException("buildRocketMQUrl param error, please check rocketmq config");
        }
        return String.format("http://%s/%s/%s", ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, BIZ_TOPIC);
    }

}

