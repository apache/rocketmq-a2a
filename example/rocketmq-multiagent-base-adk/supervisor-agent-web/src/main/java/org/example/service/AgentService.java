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
package org.example.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import com.alibaba.fastjson.JSON;
import autovalue.shaded.com.google.common.collect.ImmutableList;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.BaseSessionService;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
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
import io.a2a.spec.TaskIdParams;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant;
import org.apache.rocketmq.a2a.transport.config.RocketMQTransportConfig;
import org.apache.rocketmq.a2a.transport.impl.RocketMQTransport;
import org.example.model.TaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

/**
 * Core service for managing multi-agent coordination via RocketMQ LiteTopic.
 * <p>
 * Handles streaming chat, session/task lifecycle, agent registration, and message routing
 * between SupervisorAgent and specialized agents (e.g., WeatherAgent, TravelAgent).
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /**
     * The logical name of this agent in the multi-agent system.
     */
    private static final String AGENT_NAME = "SupervisorAgent";
    private static final String APP_NAME = "rocketmq_a2a";

    /**
     * Sub-agent names and URLs.
     */
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

    private final Map<String /* agentName */, Client /* agentClient */> agentClientMap = new ConcurrentHashMap<>();
    private final Map<String /* sessionId */, Session /* session */> sessionMap = new ConcurrentHashMap<>();
    private final Map<String /* taskId */, TaskInfo /* taskInfo */> taskMap = new ConcurrentHashMap<>();
    private final Map<String /* userId */, Map<String /* sessionId */, List<TaskInfo> /* taskInfo */>> userSessionTaskListMap = new ConcurrentHashMap<>();
    /**
     * Service for managing conversational sessions and preserving chat history.
     */
    private BaseSessionService sessionService;
    private Runner runner;
    private String lastQuestion = "";
    private static final String LEFT_BRACE = "{";

    /**
     * Initializes the service after Spring context is loaded.
     * <p>
     * Validates configuration, initializes base agent, registers external agents, and sets up internal services.
     */
    @PostConstruct
    public void init() {
        validateConfigParams();
        BaseAgent baseAgent = initAgent(WEATHER_AGENT_NAME, TRAVEL_AGENT_NAME);
        printSystemInfo("🚀 启动 Qwen为底座模型的 " + AGENT_NAME + "，擅长处理天气问题与行程安排规划问题，在本例中使用RocketMQ LiteTopic版本实现多个Agent之间的通讯");
        sessionService = new InMemorySessionService();
        runner = new Runner(baseAgent, APP_NAME, new InMemoryArtifactService(), sessionService, /* memoryService= */ null);
        initAgentCardInfo(ACCESS_KEY, SECRET_KEY, WEATHER_AGENT_NAME, WEATHER_AGENT_URL);
        initAgentCardInfo(ACCESS_KEY, SECRET_KEY, TRAVEL_AGENT_NAME, TRAVEL_AGENT_URL);
    }

    /**
     * Validates required system properties are present.
     *
     * @throws IllegalArgumentException if any required config is missing.
     */
    private static void validateConfigParams() {
        List<String> missing = new ArrayList<>();
        if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC)) {
            missing.add("workAgentResponseTopic (RocketMQ Lightweight topic for agent responses)");
        }
        if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID)) {
            missing.add("workAgentResponseGroupID (RocketMQ consumer group ID for Lightweight topic)");
        }
        if (StringUtils.isEmpty(API_KEY)) {
            missing.add("apiKey (API key for SupervisorAgent using Qwen-plus model)");
        }
        if (!missing.isEmpty()) {
            String msg = "missing required configuration: " + String.join(", ", missing);
            log.error(msg);
            throw new IllegalArgumentException(msg);
        }
    }

    /**
     * Starts a new streaming chat session with the supervisor agent.
     *
     * @param userId user identifier.
     * @param sessionId session identifier.
     * @param question user input question.
     * @return a reactive stream of response chunks.
     */
    public Flux<String> startStreamChat(String userId, String sessionId, String question) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId) || StringUtils.isEmpty(question)) {
            log.warn("startStreamChat param error, userId: [{}], sessionId: [{}], question: [{}]", userId, sessionId, question);
            return Flux.error(new IllegalArgumentException("userId, sessionId, and question must not be empty"));
        }
        Session userSession = sessionMap.computeIfAbsent(sessionId, k -> runner.sessionService().createSession(APP_NAME, userId, null, sessionId).blockingGet());
        Map<String, List<TaskInfo>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        List<TaskInfo> taskList = sessionTaskListMap.computeIfAbsent(sessionId, k -> new ArrayList<>());
        Flowable<Event> events = runner.runAsync(userId, userSession.id(), Content.fromParts(Part.fromText(question)));
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        events.blockingForEach(event -> {
            processEventContent(event.stringifyContent(), sink, taskList, userId, sessionId);
        });
        return Flux.from(sink.asFlux());
    }

    /**
     * Terminates the streaming chat session for a specific user and session.
     * <p>
     * This method performs the following actions:
     * Cleans up all active {@link TaskInfo} instances associated with the session.
     * Emits an error signal to each task's sink to notify subscribers of disconnection.
     * Sends a resubscribe command with closure metadata to all registered clients, triggering resource cleanup on agent side.
     *
     * @param userId user identifier.
     * @param sessionId session identifier.
     */
    public void endStreamChat(String userId, String sessionId) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.warn("endStreamChat param error, invalid userId: [{}] or sessionId: [{}].", userId, sessionId);
            return;
        }
        terminateSessionTasks(userId, sessionId);
        notifyClientsToUnsubscribe(sessionId);
    }

    /**
     * Terminates all tasks associated with a specific user session.
     *
     * @param userId the ID of the user whose tasks are to be terminated.
     * @param sessionId the ID of the session whose tasks are to be terminated.
     */
    private void terminateSessionTasks(String userId, String sessionId) {
        Map<String, List<TaskInfo>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId,
            k -> new ConcurrentHashMap<>());
        List<TaskInfo> taskInfos = sessionTaskListMap.get(sessionId);
        if (taskInfos == null || taskInfos.isEmpty()) {
            return;
        }
        for (TaskInfo taskInfo : taskInfos) {
            if (null == taskInfo || null == taskInfo.getSink()) {
                continue;
            }
            try {
                taskInfo.getSink().emitError(new RuntimeException("user disconnected from the stream"),
                    Sinks.EmitFailureHandler.FAIL_FAST);
            } catch (Exception e) {
                log.error("Failed to emit error for taskInfo: [{}]", taskInfo, e);
            }
        }
    }

    /**
     * Notifies all registered clients to end a specific session.
     *
     * @param sessionId the ID of the session to be ended.
     */
    private void notifyClientsToUnsubscribe(String sessionId) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RocketMQA2AConstant.UNSUB_LITE_TOPIC, sessionId);
        Collection<Client> clients = agentClientMap.values();
        if (CollectionUtils.isEmpty(clients)) {
            log.debug("endStreamChat success, clients is empty");
            return;
        }
        for (Client client : clients) {
            try {
                client.resubscribe(new TaskIdParams("", metadata));
            } catch (Exception e) {
                log.error("endStreamChat error, Client: [{}], sessionId: [{}]", client, sessionId, e);
            }
        }
    }

    /**
     * Re-establishes a streaming chat session for a given user and session ID.
     *
     * @param userId    the unique identifier of the user.
     * @param sessionId the unique identifier of the chat session.
     * @return a {@link Flux<String>} emitting stream data or a completion message.
     */
    public Flux<String> resubscribeStream(String userId, String sessionId) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.warn("resubscribeStream param error, userId: [{}], sessionId: [{}]", userId, sessionId);
            return Flux.error(new IllegalArgumentException("userId, sessionId must not be empty"));
        }
        try {
            Map<String, List<TaskInfo>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
            List<TaskInfo> taskInfoList = sessionTaskListMap.computeIfAbsent(sessionId, k -> new ArrayList<>());
            Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
            if (CollectionUtils.isEmpty(taskInfoList)) {
                log.debug("no active tasks found for session. Returning completion message. userId: [{}], sessionId: [{}]", userId, sessionId);
                return Flux.just("all task have been completed");
            }
            bindSinkToTasks(taskInfoList, sink);
            notifyClientsToResubscribe(sessionId);
            log.debug("resubscribeStream successfully, userId: [{}], sessionId: [{}]", userId, sessionId);
            return Flux.from(sink.asFlux());
        } catch (Exception e) {
            log.error("Unexpected error during resubscribeStream, userId: [{}], sessionId: [{}]", userId, sessionId, e);
            return Flux.error(new RuntimeException("resubscribeStream error", e));
        }
    }

    /**
     * Binds the specified sink to each task in the task list.
     *
     * @param taskInfoList List of task information containing tasks to bind the sink to.
     * @param sink         The sink object to be bound to the tasks.
     */
    private void bindSinkToTasks(List<TaskInfo> taskInfoList, Sinks.Many<String> sink) {
        for (TaskInfo taskInfo : taskInfoList) {
            if (taskInfo != null) {
                taskInfo.setSink(sink);
            }
        }
    }

    /**
     * Notifies all clients to resubscribe to the specified session.
     *
     * @param sessionId The session ID identifying the session to resubscribe to.
     */
    private void notifyClientsToResubscribe(String sessionId) {
        Collection<Client> clients = agentClientMap.values();
        if (CollectionUtils.isEmpty(clients)) {
            return;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RocketMQA2AConstant.SUB_LITE_TOPIC, sessionId);
        for (Client client : clients) {
            try {
                client.resubscribe(new TaskIdParams("", metadata));
                log.debug("Sent resubscribe command to client: [{}], sessionId: [{}]", client, sessionId);
            } catch (Exception e) {
                log.error("Failed to resubscribe client during stream recovery. client: [{}], sessionId: [{}]", client, sessionId, e);
            }
        }
    }

    /**
     * Processes incoming event content and routes it appropriately.
     *
     * @param content the raw event content; can be JSON ({@link Mission}) or plain text.
     * @param sink the reactive sink used to emit responses to the client.
     * @param taskList the list to which new tasks will be added.
     * @param userId the id of the user associated with this session.
     * @param sessionId the id of the current session.
     */
    private void processEventContent(String content, Sinks.Many<String> sink, List<TaskInfo> taskList, String userId, String sessionId) {
        if (StringUtils.isEmpty(content) || sink == null || StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.warn("processEventContent param error, content: [{}], sink: [{}], userId: [{}], sessionId: [{}]", content, sink, userId, sessionId);
            return;
        }
        if (!content.startsWith(LEFT_BRACE)) {
            emitMessage(sink, content, true);
            return;
        }
        try {
            Mission mission = JSON.parseObject(content, Mission.class);
            if (mission == null) {
                log.warn("parse content to mission is null, content: [{}], Skip it", content);
                return;
            }
            String taskId = UUID.randomUUID().toString();
            TaskInfo taskInfo = taskMap.computeIfAbsent(taskId, k -> TaskInfo.builder().taskId(taskId).taskDesc(mission.getMessageInfo()).sessionId(sessionId).userId(userId).sink(sink).build());
            if (null != taskList) {
                taskList.add(taskInfo);
            }
            log.debug("转发请求到其他的Agent, 等待其响应，Agent: [{}], 问题: [{}]", mission.getAgent(), mission.getMessageInfo());
            emitMessage(sink, "******" + AGENT_NAME + "转发请求到其他的Agent, 等待其响应，Agent: " + mission.getAgent() + "，问题: " + mission.getMessageInfo(), false);
            handleMissionByMessage(mission, taskId, sessionId);
        } catch (Exception e) {
            log.error("processEventContent parse error", e);
        }
    }

    /**
     * Sends a mission message to the target agent via its registered client.
     *
     * @param mission the mission object containing agent name and message content.
     * @param taskId the unique identifier for this task.
     * @param sessionId the session ID associated with the conversation.
     */
    private void handleMissionByMessage(Mission mission, String taskId, String sessionId) {
        if (null == mission || StringUtils.isEmpty(mission.getAgent()) || StringUtils.isEmpty(mission.getMessageInfo()) || StringUtils.isEmpty(taskId) || StringUtils.isEmpty(sessionId)) {
            log.warn("handleMissionByMessage param error, mission: [{}], taskId: [{}], sessionId: [{}]", JSON.toJSONString(mission), taskId, sessionId);
            return;
        }
        try {
            Client client = agentClientMap.get(mission.getAgent().replaceAll(" ", ""));
            if (null == client) {
                log.warn("handleMissionByMessage client is null");
                return;
            }
            client.sendMessage(A2A.createUserTextMessage(mission.getMessageInfo(), sessionId, taskId));
            log.debug("handleMissionByMessage message: [{}]", mission.getMessageInfo());
        } catch (Exception e) {
            log.error("handleMissionByMessage error, mission: [{}], taskId: [{}], sessionId: [{}]", JSON.toJSONString(mission), taskId, sessionId, e);
        }
    }

    /**
     * Initializes the main agent.
     *
     * @param weatherAgent the name of the Weather Agent.
     * @param travelAgent the name of the Travel Planning Agent.
     * @return a configured BaseAgent instance.
     */
    public BaseAgent initAgent(String weatherAgent, String travelAgent) {
        if (StringUtils.isEmpty(weatherAgent)) {
            log.warn("initAgent param error: weatherAgent is empty");
            return null;
        }
        if (StringUtils.isEmpty(travelAgent)) {
            log.warn("initAgent param error: travelAgent is empty");
            return null;
        }
        QwenModel qwenModel = QwenModelRegistry.getModel(API_KEY);
        if (qwenModel == null) {
            log.error("Failed to initialize QwenModel with API_KEY: [{}]", API_KEY);
            return null;
        }
        String instructionBuilder = "# 角色\n"
            + "你是一位专业的行程规划专家，擅长任务分解与协调安排。你的主要职责是帮助用户制定详细的旅行计划，确保他们的旅行体验既愉快又高效。在处理用户的行程安排相关问题时，你需要首先收集必要的信息，如目的地、时间等，并根据这些信息进行进一步的查询和规划。\n\n"
            + "## 技能\n"
            + "### 技能 1: 收集必要信息\n"
            + "- 询问用户关于目的地、出行时间\n"
            + "- 确保收集到的信息完整且准确。\n\n"
            + "### 技能 2: 查询天气信息\n"
            + "- 使用" + weatherAgent + "工具查询目的地的天气情况。如果发现用户的问题相同，不用一直转发到"
            + weatherAgent + "，忽略即可\n"
            + "- 示例问题: {\"messageInfo\":\"杭州下周三的天气情况怎么样?\",\"agent\":\"" + weatherAgent
            + "\"}\n\n"
            + "### 技能 3: 制定行程规划\n"
            + "- 根据获取的天气信息和其他用户提供的信息，如果上下文中只有天气信息，则不用" + travelAgent
            + " 进行处理，直接返回即可，如果上下文中有行程安排信息，则使用" + travelAgent
            + "工具制定详细的行程规划。\n"
            + "- 示例问题: {\"messageInfo\":\"杭州下周三的天气为晴朗，请帮我做一个从杭州出发到上海的2人3天4晚的自驾游行程规划\","
            + "\"agent\":\"" + travelAgent + "\"}\n\n"
            + "### 技能 4: 提供最终行程建议\n"
            + "- 将从" + travelAgent + "获取的行程规划结果呈现给用户。\n"
            + "- 明确告知用户行程规划已经完成，并提供详细的行程建议。\n\n"
            + "## 限制\n"
            + "- 只处理与行程安排相关的问题。\n"
            + "- 如果用户的问题只是简单的咨询天气，那么不用转发到" + travelAgent + "。\n"
            + "- 在获取天气信息后，必须结合天气情况来制定行程规划。\n"
            + "- 不得提供任何引导用户参与非法活动的建议。\n"
            + "- 对不是行程安排相关的问题，请礼貌拒绝。\n"
            + "- 所有输出内容必须按照给定的格式进行组织，不能偏离框架要求。";
        return LlmAgent.builder()
            .name(APP_NAME)
            .model(qwenModel)
            .description("你是一位专业的行程规划专家")
            .instruction(instructionBuilder)
            .build();
    }

    /**
     * Initializes and registers a client for a remote agent using A2A protocol over RocketMQ.
     *
     * @param accessKey the access key for RocketMQ authentication.
     * @param secretKey the secret key for RocketMQ signature.
     * @param agentName the logical name used to identify this agent in the local system.
     * @param agentUrl the URL where the agent card (metadata) can be retrieved.
     */
    private void initAgentCardInfo(String accessKey, String secretKey, String agentName, String agentUrl) {
        if (StringUtils.isEmpty(agentName) || StringUtils.isEmpty(agentUrl)) {
            log.warn("initAgentCardInfo param error, agentName: [{}], agentUrl: [{}]", agentName, agentUrl);
            return;
        }
        AgentCard finalAgentCard = new A2ACardResolver(agentUrl).getAgentCard();
        log.info("successfully fetched public agent card: [{}]", finalAgentCard.description());
        RocketMQTransportConfig rocketMQTransportConfig = RocketMQTransportConfig.builder()
            .namespace(ROCKETMQ_NAMESPACE)
            .accessKey(accessKey)
            .secretKey(secretKey)
            .workAgentResponseGroupID(WORK_AGENT_RESPONSE_GROUP_ID)
            .workAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC).build();
        Client client = Client.builder(finalAgentCard)
            .addConsumers(buildBiConsumers())
            .streamingErrorHandler(error -> log.error("Streaming error occurred: [{}]", error.getMessage()))
            .withTransport(RocketMQTransport.class, rocketMQTransportConfig)
            .build();
        agentClientMap.put(agentName, client);
        log.info("successfully initialized and registered agent client. agentName: [{}], url: [{}]", agentName, agentUrl);
    }

    /**
     * Constructs a list of event processors ({@link BiConsumer}) that react to {@link ClientEvent} instances
     * emitted during agent execution, such as task updates from an LLM engine.
     * @return a list containing one or more {@code BiConsumer<ClientEvent, AgentCard>} handlers;
     */
    private List<BiConsumer<ClientEvent, AgentCard>> buildBiConsumers() {
        List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();
        consumers.add((event, agentCard) -> {
            if (event instanceof TaskUpdateEvent taskUpdateEvent) {
                Task task = taskUpdateEvent.getTask();
                if (null == task) {
                    return;
                }
                TaskInfo taskInfo = taskMap.get(task.getId());
                if (null == taskInfo) {
                    return;
                }
                Many<String> sink = taskInfo.getSink();
                List<Artifact> artifacts = task.getArtifacts();
                if (null != artifacts && artifacts.size() == 1) {
                    emitMessage(sink, "\n \n", false);
                }
                if (!CollectionUtils.isEmpty(artifacts)) {
                    String msg = extractTextFromMessage(artifacts.get(artifacts.size() - 1));
                    log.debug("receive msg: [{}]", msg);
                    if (TaskState.COMPLETED != task.getStatus().state()) {
                        if (!emitMessage(sink, msg, false)) {
                            throw new RuntimeException("client close stream");
                        }
                        return;
                    }
                    StringBuilder resultBuilder = new StringBuilder();
                    for (Artifact tempArtifact : artifacts) {
                        resultBuilder.append(extractTextFromMessage(tempArtifact));
                    }
                    processAgentResponse(resultBuilder.toString(), taskInfo.getUserId(), taskInfo.getSessionId(), taskInfo.getTaskId());
                }
            }
        });
        return consumers;
    }

    /**
     * Extracts all text content from the given {@link Artifact} by iterating over its parts.
     *
     * @param artifact the artifact to extract text.
     * @return concatenated text from all text parts, or empty string if null or no text found.
     */
    private static String extractTextFromMessage(Artifact artifact) {
        if (artifact == null || CollectionUtils.isEmpty(artifact.parts())) {
            return "";
        }
        List<io.a2a.spec.Part<?>> parts = artifact.parts();
        StringBuilder textBuilder = new StringBuilder();
        for (io.a2a.spec.Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                String text = textPart.getText();
                if (text != null) {
                    textBuilder.append(text);
                }
            }
        }
        return textBuilder.toString();
    }

    /**
     * Processes the final response from a remote agent and triggers next-step actions.
     *
     * @param result the raw response content from the agent.
     * @param userId the ID of the user.
     * @param sessionId the current session ID.
     * @param taskId the associated task ID for tracking.
     */
    private void processAgentResponse(String result, String userId, String sessionId, String taskId) {
        if (StringUtils.isEmpty(result) || StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId) || StringUtils.isEmpty(taskId)) {
            log.warn("processAgentResponse param error, result: [{}], userId: [{}], sessionId: [{}], taskId: [{}]", result, userId, sessionId, taskId);
            return;
        }
        Maybe<Session> sessionMaybe = sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty());
        Event event = Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId(UUID.randomUUID().toString())
            .author(APP_NAME)
            .content(buildContent(result))
            .build();
        sessionService.appendEvent(sessionMaybe.blockingGet(), event);
        Flowable<Event> events = runner.runAsync(userId, sessionId, Content.fromParts(Part.fromText(result)));
        iterEvents(events, taskId, sessionId);
    }

    /**
     * Iterates over a stream of {@link Event} objects emitted by the agent,
     * processes each event in blocking mode, and handles potential task delegation requests.
     *
     * @param events a reactive stream of Event objects (from an agent system).
     * @param taskId the unique identifier of the current task, used to retrieve {@link TaskInfo}.
     * @param sessionId the session ID associated with this interaction, for context tracking.
     */
    private void iterEvents(Flowable<Event> events, String taskId, String sessionId) {
        events.blockingForEach(eventSub -> {
            TaskInfo taskInfo = taskMap.get(taskId);
            if (null == taskInfo || null == taskInfo.getSink()) {
                log.warn("iterEvents taskInfo/sink is null");
                return;
            }
            String content = eventSub.stringifyContent();
            if (StringUtils.isEmpty(content)) {
                return;
            }
            Many<String> sink = taskInfo.getSink();
            if (lastQuestion.equals(content) || !content.startsWith(LEFT_BRACE)) {
                sink.tryEmitComplete();
                completeTask(taskInfo);
                return;
            }
            lastQuestion = content;
            try {
                Mission mission = JSON.parseObject(content, Mission.class);
                if (null != mission && !StringUtils.isEmpty(mission.getMessageInfo()) && !StringUtils.isEmpty(mission.getAgent())) {
                    log.debug("转发到其他的Agent, 等待其他Agent响应，Agent: [{}], 问题: [{}]", mission.getAgent(), mission.getMessageInfo());
                    emitMessage(sink, "\n \n ******" + AGENT_NAME + " 转发请求到其他的Agent, 等待其响应，Agent: " + mission.getAgent() + "， 问题: " + mission.getMessageInfo(), false);
                    handleMissionByMessage(mission, taskId, sessionId);
                }
            } catch (Exception e) {
                log.error("iterEvents parse result error", e);
            }
        });
    }

    /**
     * Completes and cleans up a task by removing it from shared state maps.
     *
     * @param taskInfo the task to be completed.
     */
    private void completeTask(TaskInfo taskInfo) {
        if (null == taskInfo || StringUtils.isEmpty(taskInfo.getTaskId())) {
            log.warn("completeTask taskInfo is null or taskId is empty");
            return;
        }
        String taskId = taskInfo.getTaskId();
        taskMap.remove(taskId);
        log.debug("completeTask taskMap clear success, taskId: [{}]", taskId);
        Map<String, List<TaskInfo>> sessionTaskListMap = userSessionTaskListMap.get(taskInfo.getUserId());
        if (null == sessionTaskListMap) {
            return;
        }
        List<TaskInfo> taskInfos = sessionTaskListMap.get(taskInfo.getSessionId());
        if (CollectionUtils.isEmpty(taskInfos)) {
            return;
        }
        boolean result = taskInfos.removeIf(next -> next.getTaskId().equals(taskId));
        log.debug("completeTask userSessionTaskListMap clear success, taskId: [{}], result: [{}]", taskId, result);
    }

    /**
     * Constructs a structured {@link Content} object from a plain text string.
     *
     * @param content content the raw text input (e.g., user query or agent response).
     * @return a built {@link Content} object with role set to {@link #APP_NAME} and text wrapped in a Part,
     * or {@code null} if content is blank.
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
     * Prints a system-level informational message in blue color to the console.
     *
     * @param message the message to display and log.
     */
    private static void printSystemInfo(String message) {
        System.out.println("\u001B[34m[SYSTEM] " + message + "\u001B[0m");
    }

    /**
     * Emits a message through the reactive sink ({@link Sinks.Many}) to downstream subscribers
     * (e.g., SSE) and optionally completes the stream.
     *
     * @param sink the {@link Sinks.Many} instance used to push messages to clients.
     * @param msg the message string to emit (can be partial text, JSON, or status info).
     * @param isFinish if {@code true}, signals that this is the final message — attempts to complete the stream afterward.
     * @return {@code true} if the message was sent successfully and no terminal error occurred.
     * {@code false} if emission failed due to overflow, cancellation, or termination.
     */
    private static boolean emitMessage(Sinks.Many<String> sink, String msg, boolean isFinish) {
        Sinks.EmitResult result = sink.tryEmitNext(msg);
        switch (result) {
            case OK:
                log.info("📤 成功发送: [{}]", msg);
                break;
            case FAIL_OVERFLOW:
            case FAIL_CANCELLED:
            case FAIL_TERMINATED:
                log.error("🛑 上游检测到问题，停止发送。原因: [{}]", result);
                return false;
            default:
                log.error("⚠️ 发送状态: [{}]", result);
        }
        if (isFinish) {
            sink.tryEmitComplete();
        }
        return true;
    }

}
