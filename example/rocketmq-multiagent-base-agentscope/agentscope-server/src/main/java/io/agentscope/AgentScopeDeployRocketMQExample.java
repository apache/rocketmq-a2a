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
package io.agentscope;

import java.util.List;
import io.a2a.spec.AgentInterface;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.runtime.LocalDeployManager;
import io.agentscope.runtime.adapters.agentscope.AgentScopeAgentHandler;
import io.agentscope.runtime.app.AgentApp;
import io.agentscope.runtime.engine.schemas.AgentRequest;
import io.agentscope.runtime.protocol.a2a.A2aProtocolConfig;
import io.agentscope.runtime.protocol.a2a.ConfigurableAgentCard;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.a2a.common.RocketMQA2AConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import static io.agentscope.runtime.protocol.a2a.RocketMQUtils.BIZ_CONSUMER_GROUP;
import static io.agentscope.runtime.protocol.a2a.RocketMQUtils.BIZ_TOPIC;
import static io.agentscope.runtime.protocol.a2a.RocketMQUtils.ROCKETMQ_ENDPOINT;
import static io.agentscope.runtime.protocol.a2a.RocketMQUtils.ROCKETMQ_NAMESPACE;

/**
 * Example demonstrating how to use AgentScope to proxy ReActAgent
 */
public class AgentScopeDeployRocketMQExample {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeDeployRocketMQExample.class);
    private static final String DASHSCOPE_API_KEY = System.getProperty("apiKey", "");
    private static final String AGENT_NAME = "agentscope-a2a-rocketmq-example-agent";

    public static void main(String[] args) {
        if (!checkConfigParam()) {
            System.exit(1);
        }
        runAgent();
    }

    private static void runAgent() {
        AgentInterface agentInterface = new AgentInterface(RocketMQA2AConstant.ROCKETMQ_PROTOCOL, formatRocketMQServiceUrl(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, BIZ_TOPIC));
        ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder().url(formatRocketMQServiceUrl(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, BIZ_TOPIC)).preferredTransport(RocketMQA2AConstant.ROCKETMQ_PROTOCOL).additionalInterfaces(List.of(agentInterface)).description("use rocketmq as transport").build();
        AgentApp agentApp = new AgentApp(agent(agentBuilder(dashScopeChatModel(DASHSCOPE_API_KEY))));
        agentApp.deployManager(LocalDeployManager.builder().protocolConfigs(List.of(new A2aProtocolConfig(agentCard, 60, 10))).port(10001).build());
        agentApp.cors(registry -> registry.addMapping("/**").allowedOriginPatterns("*").allowedMethods("GET", "POST", "PUT", "DELETE").allowCredentials(true));
        agentApp.run();
    }

    public static ReActAgent.Builder agentBuilder(DashScopeChatModel model) {
        return ReActAgent.builder().model(model).name(AGENT_NAME).sysPrompt("You are an example of A2A(Agent2Agent) Protocol(use RocketmqTransport) Agent. You can answer some simple question according to your knowledge.");
    }

    public static AgentScopeAgentHandler agent(ReActAgent.Builder builder) {
        return new AgentScopeAgentHandler() {
            @Override
            public boolean isHealthy() {
                return true;
            }

            @Override
            public Flux<?> streamQuery(AgentRequest request, Object messages) {
                ReActAgent agent = builder.build();
                StreamOptions streamOptions = StreamOptions.builder().eventTypes(EventType.REASONING, EventType.TOOL_RESULT).incremental(true).build();
                if (messages instanceof List<?>) {
                    return agent.stream((List<Msg>)messages, streamOptions);
                } else if (messages instanceof Msg) {
                    return agent.stream((Msg)messages, streamOptions);
                } else {
                    Msg msg = Msg.builder().role(MsgRole.USER).build();
                    return agent.stream(msg, streamOptions);
                }
            }
            @Override
            public String getName() {
                return builder.build().getName();
            }

            @Override
            public String getDescription() {
                return builder.build().getDescription();
            }
        };
    }
    public static DashScopeChatModel dashScopeChatModel(String dashScopeApiKey) {
        if (StringUtils.isEmpty(dashScopeApiKey)) {
            throw new IllegalArgumentException("DashScope API Key is empty, please set environment variable `AI_DASHSCOPE_API_KEY`");
        }
        return DashScopeChatModel.builder().apiKey(dashScopeApiKey).modelName("qwen-max").stream(true).enableThinking(true).build();
    }

    /**
     * Constructs a formatted RocketMQ Lite HTTP endpoint URL for topic access.
     *
     * @return a valid RocketMQ Lite URL suitable for use with A2A SDK.
     * @throws IllegalArgumentException if either {@code #endpoint} or {@code #bizTopic} is null or blank,
     * indicating missing critical configuration
     */
    public static String formatRocketMQServiceUrl(String endpoint, String namespace, String bizTopic) {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(bizTopic)) {
            log.warn("buildRocketMQUrl invalid params, endpoint: [{}], bizTopic: [{}]", endpoint, bizTopic);
            throw new IllegalArgumentException("buildRocketMQUrl invalid params, please check RocketMQ config");
        }
        return String.format("http://%s/%s/%s", endpoint, namespace, bizTopic);
    }

    private static boolean checkConfigParam() {
        if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT) || StringUtils.isEmpty(BIZ_TOPIC) || StringUtils.isEmpty(BIZ_CONSUMER_GROUP) || StringUtils.isEmpty(DASHSCOPE_API_KEY)) {
            if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT)) {
                System.err.println("rocketMQEndpoint is empty");
            }
            if (StringUtils.isEmpty(BIZ_TOPIC)) {
                System.err.println("bizTopic is empty");
            }
            if (StringUtils.isEmpty(BIZ_CONSUMER_GROUP)) {
                System.err.println("bizConsumerGroup is empty");
            }
            if (StringUtils.isEmpty(DASHSCOPE_API_KEY)) {
                System.err.println("apiKey is empty");
            }
            return false;
        }
        return true;
    }
}
