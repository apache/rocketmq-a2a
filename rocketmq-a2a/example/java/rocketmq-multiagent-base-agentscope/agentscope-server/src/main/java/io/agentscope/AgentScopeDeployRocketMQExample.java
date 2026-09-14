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
import java.util.ArrayList;
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
 * Example demonstrating how to use AgentScope to proxy ReActAgent.
 */
public class AgentScopeDeployRocketMQExample {
    private static final Logger log = LoggerFactory.getLogger(AgentScopeDeployRocketMQExample.class);
    private static final String API_KEY = System.getProperty("apiKey", "");
    private static final String MODEL_NAME = "qwen-max";
    private static final String AGENT_NAME = "agents-cope-a2a-rocketmq-example-agent";

    public static void main(String[] args) {
        if (!validateRequiredConfig()) {
            System.exit(1);
        }
        runAgent();
    }

    /**
     * Deploys and runs the agent server with RocketMQ-backed A2A protocol support.
     * <p>
     * The agent is exposed via a local HTTP endpoint (port 10001), allowing other agents
     * to invoke it through the A2A SDK using RocketMQ for asynchronous message delivery.
     */
    private static void runAgent() {
        // Build the service URL used by clients to communicate via RocketMQ.
        String rocketMQServiceUrl = formatRocketMQServiceUrl(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, BIZ_TOPIC);
        // Define primary transport interface: RocketMQ A2A protocol.
        AgentInterface agentInterface = new AgentInterface(RocketMQA2AConstant.ROCKETMQ_PROTOCOL, rocketMQServiceUrl);
        // Create agent card — metadata describing how to connect to this agent.
        ConfigurableAgentCard agentCard = new ConfigurableAgentCard.Builder().url(rocketMQServiceUrl)
            .preferredTransport(RocketMQA2AConstant.ROCKETMQ_PROTOCOL).additionalInterfaces(List.of(agentInterface))
            .description("use rocketmq as transport").build();
        // Build and configure the actual agent logic (ReAct-style).
        AgentApp agentApp = new AgentApp(agent(agentBuilder(dashScopeChatModel(API_KEY))));
        agentApp.deployManager(LocalDeployManager.builder().protocolConfigs(List.of(new A2aProtocolConfig(agentCard, 60, 10))).port(10001).build());
        agentApp.cors(registry -> registry.addMapping("/**").allowedOriginPatterns("*").allowedMethods("GET", "POST", "PUT", "DELETE").allowCredentials(true));
        // Start the agent application
        agentApp.run();
    }

    /**
     * Constructs a ReActAgent builder pre-configured with model and prompt settings.
     *
     * @param model the LLM model backend (e.g., DashScope).
     * @return a configured {@link ReActAgent.Builder}.
     */
    public static ReActAgent.Builder agentBuilder(DashScopeChatModel model) {
        return ReActAgent.builder().model(model).name(AGENT_NAME).sysPrompt("You are an example of A2A(Agent2Agent) Protocol(use RocketmqTransport) Agent. You can answer some simple question according to your knowledge.");
    }

    /**
     * Wraps the agent in an {@link AgentScopeAgentHandler} to integrate with the AgentScope runtime.
     * Handles incoming requests and manages streaming responses.
     *
     * @param builder the agent builder used to instantiate the agent per request.
     * @return an agent handler supporting streaming query and health check.
     */
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
                    return agent.stream(Msg.builder().role(MsgRole.USER).build(), streamOptions);
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

    /**
     * Creates a DashScope chat model instance with streaming and thinking enabled.
     *
     * @param dashScopeApiKey the API key for authentication.
     * @return a configured {@link DashScopeChatModel}.
     * @throws IllegalArgumentException if the API key is missing.
     */
    public static DashScopeChatModel dashScopeChatModel(String dashScopeApiKey) {
        if (StringUtils.isEmpty(dashScopeApiKey)) {
            throw new IllegalArgumentException("DashScope API Key is empty. Please set system property 'apiKey'.");
        }
        return DashScopeChatModel.builder().apiKey(dashScopeApiKey).modelName(MODEL_NAME).stream(true).enableThinking(false).build();
    }

    /**
     * Constructs a formatted RocketMQ Lite HTTP endpoint URL for topic access.
     *
     * @return a valid RocketMQ Lite URL suitable for use with A2A SDK.
     * @throws IllegalArgumentException if either {@code #endpoint} or {@code #bizTopic} is null or blank,
     * indicating missing critical configuration.
     */
    public static String formatRocketMQServiceUrl(String endpoint, String namespace, String bizTopic) {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(bizTopic)) {
            log.warn("buildRocketMQUrl invalid params, endpoint: [{}], bizTopic: [{}]", endpoint, bizTopic);
            throw new IllegalArgumentException("buildRocketMQUrl invalid params, please check RocketMQ config");
        }
        return String.format("http://%s/%s/%s", endpoint, namespace, bizTopic);
    }

    /**
     * Validates required configuration parameters.
     * Logs specific error messages for missing fields.
     *
     * @return true if all required configs are present; false otherwise.
     */
    private static boolean validateRequiredConfig() {
        List<String> missing = new ArrayList<>();
        if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT)) {missing.add("rocketMQEndpoint");}
        if (StringUtils.isEmpty(BIZ_TOPIC)) {missing.add("bizTopic");}
        if (StringUtils.isEmpty(BIZ_CONSUMER_GROUP)) {missing.add("bizConsumerGroup");}
        if (StringUtils.isEmpty(API_KEY)) {missing.add("apiKey (DashScope)");}
        if (!missing.isEmpty()) {
            log.error("Missing required configuration parameter(s): [{}]", String.join(", ", missing));
            return false;
        }
        return true;
    }
}
