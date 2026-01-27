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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import com.alibaba.fastjson.JSON;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import common.model.Mission;
import common.qwen.QwenModel;
import common.qwen.QwenModelRegistry;
import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.TaskUpdateEvent;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import org.apache.rocketmq.a2a.transport.impl.RocketMQTransport;
import org.apache.rocketmq.a2a.transport.config.RocketMQTransportConfig;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

/**
 * SupervisorAgent: The master control agent class that implements multi-agent
 * coordination based on the Qwen (Qwen) model.
 *
 * <p>Responsible for orchestrating collaborative tasks between specialized agents,
 * such as WeatherAgent and TravelAgent, to fulfill complex user requests —
 * including weather querying and travel planning.
 *
 * <p>Uses Apache RocketMQ as the A2A (Application-to-Application) messaging middleware
 * for asynchronous, decoupled communication between agents.
 */
public class SupervisorAgentMain {
    private static final Logger log = LoggerFactory.getLogger(SupervisorAgentMain.class);

    /**
     * The logical name of this agent in the multi-agent system.
     * Used for message routing, logging, and identification in distributed communication.
     */
    private static final String AGENT_NAME = "SupervisorAgent";
    private static final String USER_ID = "rocketmq_a2a_user";
    private static final String APP_NAME = "rocketmq_a2a";
    // Sub-agent names and URLs
    private static final String WEATHER_AGENT_NAME = "WeatherAgent";
    private static final String WEATHER_AGENT_URL = "http://localhost:8080";
    private static final String TRAVEL_AGENT_NAME = "TravelAgent";
    private static final String TRAVEL_AGENT_URL = "http://localhost:8888";

    /**
     * The dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     */
    private static final String WORK_AGENT_RESPONSE_TOPIC = System.getProperty("workAgentResponseTopic");

    /**
     * The consumer group ID used when subscribing to the {@link #WORK_AGENT_RESPONSE_TOPIC}.
     */
    private static final String WORK_AGENT_RESPONSE_GROUP_ID = System.getProperty("workAgentResponseGroupID");

    /**
     * The namespace used for logical isolation of RocketMQ resources.
     */
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace");

    /**
     * The access key for authenticating with the RocketMQ service.
     */
    private static final String ACCESS_KEY = System.getProperty("rocketMQAK");

    /**
     * The secret key for authenticating with the RocketMQ service.
     */
    private static final String SECRET_KEY = System.getProperty("rocketMQSK");

    /**
     * The API key used to authenticate requests to the Qwen service.
     */
    private static final String API_KEY = System.getProperty("apiKey");
    // Role identifiers
    private static final String YOU = "You";
    private static final String AGENT = "Agent";
    private static String lastQuestion = "";
    private static final String LEFT_BRACE = "{";

    /**
     * Service for managing conversational sessions and preserving chat history.
     */
    private static BaseSessionService sessionService;

    /**
     * Maps agent names (e.g., "WeatherAgent") to their corresponding A2A client instances.
     * Enables dynamic dispatch of messages to the appropriate remote agent.
     */
    private static final Map<String, Client> AgentClientMap = new HashMap<>();

    /**
     * Current session identifier for grouping related interactions.
     * Maintains continuity across multiple turns in a conversation.
     */
    private static String sessionId;
    private static Runner runner;

    /**
     * Main entry point of the application.
     */
    public static void main(String[] args) {
        // Validate configuration parameters
        validateConfigParams();
        // Initialize the main agent
        BaseAgent baseAgent = initAgent(WEATHER_AGENT_NAME, TRAVEL_AGENT_NAME);
        printSystemInfo("🚀 启动 QWen为底座模型的 " + AGENT_NAME + "，擅长处理天气问题与行程安排规划问题，在本例中使用RocketMQ LiteTopic版本实现多个Agent之间的通讯");
        printSystemInfo("📋 初始化会话...");
        // Initialize ADK-related service components
        sessionService = new InMemorySessionService();
        runner = new Runner(baseAgent, APP_NAME, new InMemoryArtifactService(), sessionService, /* memoryService= */ null);
        // Create a user session
        Session session = runner.sessionService().createSession(APP_NAME, USER_ID).blockingGet();
        printSystemSuccess("✅ 会话创建成功: " + session.id());
        sessionId = session.id();
        // Initialize and register sub-agent clients
        registerAgentClient(WEATHER_AGENT_NAME, WEATHER_AGENT_URL);
        registerAgentClient(TRAVEL_AGENT_NAME, TRAVEL_AGENT_URL);
        printSystemInfo("💡 输入 'quit' 退出，输入 'help' 查看帮助");
        // Enter the user interaction loop
        startInteractionLoop();
    }

    /**
     * Validates required configuration parameters.
     *
     * @throws IllegalArgumentException if any critical parameter is missing.
     */
    private static void validateConfigParams() {
        List<String> missingParams = new ArrayList<>();
        if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC)) {
            missingParams.add("workAgentResponseTopic (RocketMQ LiteTopic for agent responses)");
        }
        if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID)) {
            missingParams.add("workAgentResponseGroupID (RocketMQ consumer group ID for LiteTopic)");
        }
        if (StringUtils.isEmpty(API_KEY)) {
            missingParams.add("apiKey (API key for SupervisorAgent using Qwen-plus model)");
        }
        if (!missingParams.isEmpty()) {
            String message = "The following required configuration parameters are missing." + String.join("\n", missingParams);
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Initializes the main agent.
     *
     * @param weatherAgent the name of the Weather Agent.
     * @param travelAgent  the name of the Travel Planning Agent.
     * @return a configured BaseAgent instance.
     */
    public static BaseAgent initAgent(String weatherAgent, String travelAgent) {
        if (StringUtils.isEmpty(weatherAgent) || StringUtils.isEmpty(travelAgent)) {
            log.error("Missing parameters in initAgent, please provide both weatherAgent and travelAgent names.");
            throw new IllegalArgumentException("SupervisorAgentMain Missing required agent names. Please specify both weatherAgent and travelAgent.");
        }
        QwenModel qwenModel = QwenModelRegistry.getModel(API_KEY);
        return LlmAgent.builder()
            .name(APP_NAME)
            .model(qwenModel)
            .description("你是一位专业的行程规划专家")
            .instruction("# 角色\n"
                + "你是一位专业的行程规划专家，擅长任务分解与协调安排。你的主要职责是帮助用户制定详细的旅行计划，确保他们的旅行体验既愉快又高效。在处理用户的行程安排相关问题时，你需要首先收集必要的信息，如目的地、时间等，并根据这些信息进行进一步的查询和规划。\n"
                + "\n"
                + "## 技能\n"
                + "### 技能 1: 收集必要信息\n"
                + "- 询问用户关于目的地、出行时间\n"
                + "- 确保收集到的信息完整且准确。\n"
                + "\n"
                + "### 技能 2: 查询天气信息\n"
                + "- 使用" + weatherAgent + "工具查询目的地的天气情况。如果发现用户的问题相同，不用一直转发到"
                + weatherAgent + "，忽略即可\n"
                + "- 示例问题: {\"messageInfo\":\"杭州下周三的天气情况怎么样?\",\"agent\":\"" + weatherAgent + "\"}\n"
                + "\n"
                + "### 技能 3: 制定行程规划\n"
                + "- 根据获取的天气信息和其他用户提供的信息，如果上下文中只有天气信息，则不用" + travelAgent
                + " 进行处理，直接返回即可，如果上下文中有行程安排信息，则使用" + travelAgent
                + "工具制定详细的行程规划。\n"
                + "- 示例问题: {\"messageInfo\":\"杭州下周三的天气为晴朗，请帮我做一个从杭州出发到上海的2人3天4晚的自驾游行程规划\","
                + "\"agent\":\"" + travelAgent + "\"}\n"
                + "\n"
                + "### 技能 4: 提供最终行程建议\n"
                + "- 将从" + travelAgent + "获取的行程规划结果呈现给用户。\n"
                + "- 明确告知用户行程规划已经完成，并提供详细的行程建议。\n"
                + "\n"
                + "## 限制\n"
                + "- 只处理与行程安排相关的问题。\n"
                + "- 如果用户的问题只是简单的咨询天气，那么不用转发到" + travelAgent + "。\n"
                + "- 在获取天气信息后，必须结合天气情况来制定行程规划。\n"
                + "- 不得提供任何引导用户参与非法活动的建议。\n"
                + "- 对不是行程安排相关的问题，请礼貌拒绝。\n"
                + "- 所有输出内容必须按照给定的格式进行组织，不能偏离框架要求。"
            )
            .build();
    }

    /**
     * Starts the main user interaction loop.
     */
    private static void startInteractionLoop() {
        try (Scanner scanner = new Scanner(System.in, String.valueOf(StandardCharsets.UTF_8))) {
            while (true) {
                printPrompt(YOU);
                String userInput = scanner.nextLine().trim();
                if ("quit".equalsIgnoreCase(userInput)) {
                    printSystemInfo("👋 Goodbye!");
                    System.exit(0);
                    break;
                }
                if ("help".equalsIgnoreCase(userInput)) {
                    printHelp();
                    continue;
                }
                if (StringUtils.isEmpty(userInput)) {
                    printSystemInfo("请不要输入空值.");
                    continue;
                }
                printSystemInfo("🤔 思考中...");
                log.info("用户输入: [{}]", userInput);
                Content userMsg = Content.fromParts(Part.fromText(userInput));
                Flowable<Event> events = runner.runAsync(USER_ID, sessionId, userMsg);
                events.blockingForEach(event -> {
                    String content = event.stringifyContent();
                    dealEventContent(content);
                });
            }
        }
    }

    /**
     * Processes the response content returned by the main Agent's LLM and triggers subsequent reasoning workflows.
     *
     * @param eventContent The response content returned by the LLM.
     */
    private static void dealEventContent(String eventContent) {
        if (StringUtils.isEmpty(eventContent)) {
            log.warn("dealEventContent eventContent is empty");
            return;
        }
        if (!eventContent.startsWith(LEFT_BRACE)) {
            printPrompt(AGENT);
            log.debug(eventContent);
            return;
        }
        try {
            Mission mission = JSON.parseObject(eventContent, Mission.class);
            if (null != mission) {
                printPrompt(AGENT);
                log.debug("Agent: [{}], forwarding request to another agent and waiting for its response. Target Agent: [{}], Query: [{}]", AGENT_NAME, mission.getAgent(), mission.getMessageInfo());
                forwardMissionToAgent(mission);
            }
        } catch (Exception e) {
            log.error("An error occurred while parsing the event content", e);
        }
    }

    /**
     * Forwards a task to the specified agent.
     *
     * @param mission The task instruction to be forwarded.
     */
    private static void forwardMissionToAgent(Mission mission) {
        if (null == mission || StringUtils.isEmpty(mission.getAgent()) || StringUtils.isEmpty(mission.getMessageInfo())) {
            log.error("forwardMissionToAgent param error, mission: [{}]", JSON.toJSONString(mission));
            return;
        }
        try {
            String agentName = mission.getAgent().replaceAll(" ", "");
            Client client = AgentClientMap.get(agentName);
            client.sendMessage(A2A.toUserMessage(mission.getMessageInfo()));
            log.info("forwardMissionToAgent messageInfo: [{}]", mission.getMessageInfo());
        } catch (Exception e) {
            log.error("forwardMissionToAgent error occurred while forwarding mission to agent", e);
        }
    }

    /**
     * Registers a remote agent client (via AgentCard).
     *
     * @param agentName The name of the agent.
     * @param agentUrl  The service URL of the agent.
     */
    private static void registerAgentClient(String agentName, String agentUrl) {
        if (StringUtils.isEmpty(agentName) || StringUtils.isEmpty(agentUrl)) {
            log.error("Invalid parameters in registerAgentClient: agentName: [{}], agentUrl: [{}]", agentName, agentUrl);
            return;
        }
        AgentCard finalAgentCard = new A2ACardResolver(agentUrl).getAgentCard();
        log.info("Successfully fetched public agent card: [{}]", finalAgentCard.description());
        // Build event consumers
        List<BiConsumer<ClientEvent, AgentCard>> consumers = buildEventConsumers();
        RocketMQTransportConfig rocketMQTransportConfig = new RocketMQTransportConfig();
        rocketMQTransportConfig.setNamespace(ROCKETMQ_NAMESPACE);
        rocketMQTransportConfig.setAccessKey(ACCESS_KEY);
        rocketMQTransportConfig.setSecretKey(SECRET_KEY);
        rocketMQTransportConfig.setWorkAgentResponseGroupID(WORK_AGENT_RESPONSE_GROUP_ID);
        rocketMQTransportConfig.setWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
        Client client = Client.builder(finalAgentCard)
            .addConsumers(consumers)
            .streamingErrorHandler(error -> log.error("Streaming error occurred: [{}]", error.getMessage()))
            .withTransport(RocketMQTransport.class, rocketMQTransportConfig)
            .build();
        AgentClientMap.put(agentName, client);
        log.info("Agent [{}] initialized successfully", agentName);
    }

    /**
     * Builds a list of event consumers that react to agent task events.
     * Extracts text from Artifacts and forwards to output handler.
     */
    private static List<BiConsumer<ClientEvent, AgentCard>> buildEventConsumers() {
        List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();
        consumers.add((event, agentCard) -> {
            if (event instanceof TaskUpdateEvent taskUpdateEvent) {
                Task task = taskUpdateEvent.getTask();
                if (null == task) {
                    log.error("EventConsumer task is null");
                    return;
                }
                List<Artifact> artifacts = task.getArtifacts();
                if (null != artifacts && artifacts.size() == 1) {
                    printPrompt(AGENT);
                }
                if (!CollectionUtils.isEmpty(artifacts)) {
                    TaskState state = task.getStatus().state();
                    System.out.print(extractTextFromMessage(artifacts.get(artifacts.size() - 1)));
                    if (state == TaskState.COMPLETED) {
                        StringBuilder stringBuilder = new StringBuilder();
                        for (Artifact tempArtifact : artifacts) {
                            stringBuilder.append(extractTextFromMessage(tempArtifact));
                        }
                        dealAgentResponse(stringBuilder.toString());
                    }
                }
            }
        });
        return consumers;
    }

    /**
     * Extracts the text content from an Artifact.
     *
     * @param artifact The content fragment (Artifact) to extract text from.
     * @return A concatenated string of all text parts, or an empty string if none exist.
     */
    private static String extractTextFromMessage(Artifact artifact) {
        if (artifact == null || CollectionUtils.isEmpty(artifact.parts())) {return "";}
        return artifact.parts().stream()
            .filter(part -> part instanceof TextPart)
            .map(part -> ((TextPart)part).getText())
            .collect(Collectors.joining());
    }

    /**
     * Processes the response from a sub-agent and triggers subsequent reasoning steps.
     *
     * @param result The content returned by the sub-agent.
     */
    private static void dealAgentResponse(String result) {
        if (StringUtils.isEmpty(result)) {
            return;
        }
        Maybe<Session> sessionMaybe = sessionService.getSession(APP_NAME, USER_ID, sessionId, Optional.empty());
        Session session = sessionMaybe.blockingGet();
        // Construct an event and append it to the session history
        Event event = Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId(UUID.randomUUID().toString())
            .author(APP_NAME)
            .content(buildContent(result))
            .build();
        sessionService.appendEvent(session, event);
        Content userMsg = Content.fromParts(Part.fromText(result));
        iterEvents(runner.runAsync(USER_ID, session.id(), userMsg));
        printPrompt(YOU);
    }

    /**
     * Iterates over a stream of {@link Event} objects emitted by the agent (e.g., LLM or workflow engine),
     * processes each event in blocking mode, and handles potential task delegation requests.
     *
     * @param events a reactive stream of Event objects (typically from an agent system)
     */
    private static void iterEvents(Flowable<Event> events) {
        events.blockingForEach(eventSub -> {
            boolean isDuplicate = lastQuestion.equals(eventSub.stringifyContent());
            if (isDuplicate) {
                return;
            }
            lastQuestion = eventSub.stringifyContent();
            String content = lastQuestion;
            if (StringUtils.isEmpty(content) || !content.startsWith(LEFT_BRACE)) {
                log.debug("Agent response: [{}]", content);
                return;
            }
            try {
                Mission mission = JSON.parseObject(content, Mission.class);
                if (null != mission && !StringUtils.isEmpty(mission.getMessageInfo()) && !StringUtils.isEmpty(mission.getAgent())) {
                    printPrompt(AGENT);
                    log.debug("Forwarding to another agent and waiting for its response. Target Agent: [{}], Query: [{}]", mission.getAgent(), mission.getMessageInfo());
                    forwardMissionToAgent(mission);
                }
            } catch (Exception e) {
                log.error("An error occurred while parsing the response content", e);
            }
        });
    }

    /**
     * Constructs a structured {@link Content} object from a plain text string.
     * Used when preparing input messages to send to the LLM or agent system.
     *
     * @param content content the raw text input (e.g., user query or agent response)
     * @return a built {@link Content} object with role set to {@link #APP_NAME} and text wrapped in a Part,
     * or {@code null} if content is blank
     */
    private static Content buildContent(String content) {
        if (StringUtils.isEmpty(content)) {
            return null;
        }
        return Content.builder()
            .role(APP_NAME)
            .parts(ImmutableList.of(Part.builder().text(content).build()))
            .build();
    }

    /**
     * Prints a system-level informational message in blue color to the console,
     * and logs it at INFO level.
     *
     * <p>Used for displaying internal status, initialization steps, or non-critical notifications.
     *
     * @param message the message to display and log
     */
    private static void printSystemInfo(String message) {
        System.out.println("\u001B[34m[SYSTEM] " + message + "\u001B[0m");
        log.info(message);
    }

    /**
     * Prints a success message in green color to the console,
     * and logs it at INFO level.
     *
     * <p>Indicates successful completion of an operation (e.g., connection established, task completed).
     *
     * @param message the success message to display and log
     */
    private static void printSystemSuccess(String message) {
        System.out.println("\u001B[32m[SUCCESS] " + message + "\u001B[0m");
        log.info(message);
    }

    /**
     * Prints a prompt indicator in cyan color to signal that the agent or user is about to write.
     *
     * <p>Typical format: {@code Agent > } or {@code You > }, followed by text without line break.
     *
     * @param role the speaker role, e.g., "You" or "Agent"
     */
    private static void printPrompt(String role) {
        System.out.print("\n\u001B[36m" + role + " > \u001B[0m");
    }

    /**
     * Displays a help menu in magenta/purple color listing available commands.
     *
     * <p>Shown when the user types 'help'. Provides guidance on supported queries and actions.
     */
    private static void printHelp() {
        System.out.println("\n\u001B[35m📖 帮助信息:\u001B[0m");
        System.out.println("  • 询问天气: '杭州明天的天气情况怎么样'");
        System.out.println("  • 帮忙安排行程: '帮我做一个明天杭州周边自驾游方案'");
        System.out.println("  • 退出程序: 'quit'");
        System.out.println("  • 显示帮助: 'help'");
    }
}
