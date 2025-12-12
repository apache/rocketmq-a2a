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
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import com.alibaba.fastjson.JSON;
import autovalue.shaded.com.google.common.collect.ImmutableList;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import common.Mission;
import common.QWModel;
import common.QWModelRegistry;
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
import org.apache.rocketmq.a2a.common.RocketMQA2AConstant;
import org.apache.rocketmq.a2a.transport.RocketMQTransport;
import org.apache.rocketmq.a2a.transport.RocketMQTransportConfig;
import org.example.common.TaskInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private static final String AGENT_NAME = "SupervisorAgent";
    private static final String APP_NAME = "rocketmq_a2a";
    private static final String WEATHER_AGENT_NAME = "WeatherAgent";
    private static final String WEATHER_AGENT_URL = "http://localhost:8080";
    private static final String TRAVEL_AGENT_NAME = "TravelAgent";
    private static final String TRAVEL_AGENT_URL = "http://localhost:8888";
    private static final String WORK_AGENT_RESPONSE_TOPIC = System.getProperty("workAgentResponseTopic");
    private static final String WORK_AGENT_RESPONSE_GROUP_ID = System.getProperty("workAgentResponseGroupID");
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace");
    private static final String ACCESS_KEY = System.getProperty("rocketMQAK");
    private static final String SECRET_KEY = System.getProperty("rocketMQSK");
    private static final String API_KEY = System.getProperty("apiKey");

    private final Map<String /* agentName */, Client /* agentClient */> AgentClientMap = new HashMap<>();
    private final Map<String /* sessionId */, Session /* session */> sessionMap = new HashMap<>();
    private final Map<String /* taskId */, TaskInfo /* taskInfo */> taskMap = new HashMap<>();
    private final Map<String /* userId */, Map<String /* sessionId */, List<TaskInfo> /* taskInfo */>> userSessionTaskListMap = new HashMap<>();

    private InMemorySessionService sessionService;
    private Runner runner;
    private String lastQuestion = "";
    @PostConstruct
    public void init() {
        if (!checkConfigParam()) {
            log.error("please check the config param");
            throw new RuntimeException("please check the config param");
        }
        BaseAgent baseAgent = initAgent(WEATHER_AGENT_NAME, TRAVEL_AGENT_NAME);
        printSystemInfo("🚀 启动 QWen为底座模型的 " + AGENT_NAME + "，擅长处理天气问题与行程安排规划问题，在本例中使用RocketMQ LiteTopic版本实现多个Agent之间的通讯");
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        sessionService = new InMemorySessionService();
        runner = new Runner(baseAgent, APP_NAME, artifactService, sessionService, /* memoryService= */ null);
        initAgentCardInfo(ACCESS_KEY, SECRET_KEY, WEATHER_AGENT_NAME, WEATHER_AGENT_URL);
        initAgentCardInfo(ACCESS_KEY, SECRET_KEY, TRAVEL_AGENT_NAME, TRAVEL_AGENT_URL);
    }

    private static boolean checkConfigParam() {
        if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC) || StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID) || StringUtils.isEmpty(API_KEY)) {
            if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC)) {
                log.error("请配置RocketMQ 的轻量消息Topic workAgentResponseTopic");
            }
            if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID)) {
                log.error("请配置RocketMQ 的轻量消息消费者 workAgentResponseGroupID");
            }
            if (StringUtils.isEmpty(API_KEY)) {
                log.error("请配置SupervisorAgent qwen-plus apiKey");
            }
            return false;
        }
        return true;
    }

    public Flux<String> streamChat(String userId, String sessionId, String question) {
        Session userSession = sessionMap.computeIfAbsent(sessionId, k -> {
            return runner.sessionService().createSession(APP_NAME, userId, null, sessionId).blockingGet();
        });
        Map<String, List<TaskInfo>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId, k -> new HashMap<>());
        List<TaskInfo> taskList = sessionTaskListMap.computeIfAbsent(sessionId, k -> new ArrayList<>());
        Content userMsg = Content.fromParts(Part.fromText(question));
        Flowable<Event> events = runner.runAsync(userId, userSession.id(), userMsg);
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        events.blockingForEach(event -> {
            String content = event.stringifyContent();
            dealEventContent(content, sink, taskList, userId, sessionId);
        });
        return Flux.from(sink.asFlux());
    }

    public void closeStreamChat(String userId, String sessionId) {
        Map<String, List<TaskInfo>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId, k -> new HashMap<>());
        List<TaskInfo> taskInfos = sessionTaskListMap.computeIfAbsent(sessionId, k -> new ArrayList<>());
        for (TaskInfo taskInfo : taskInfos) {
            taskInfo.getSink().emitError(new RuntimeException("用户断开连接"), Sinks.EmitFailureHandler.FAIL_FAST);
        }
        Collection<Client> clients = AgentClientMap.values();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RocketMQA2AConstant.CLOSE_LITE_TOPIC, sessionId);
        if (!CollectionUtils.isEmpty(clients)) {
            for (Client client : clients) {
                client.resubscribe(new TaskIdParams("", metadata));
                log.info("closeStream userId: {}, sessionId: {}", userId, sessionId);
            }
        }
    }

    public Flux<String> resubscribeStream(String userId, String sessionId) {
        try {
            Map<String, List<TaskInfo>> sessionTaskList = userSessionTaskListMap.computeIfAbsent(userId, k -> new HashMap<>());
            List<TaskInfo> taskInfoList = sessionTaskList.computeIfAbsent(sessionId, k -> new ArrayList<>());
            Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
            if (CollectionUtils.isEmpty(taskInfoList)) {
                return Flux.just("任务均已完成，请重新提问");
            }
            for (TaskInfo taskInfo : taskInfoList) {
                taskInfo.setSink(sink);
            }
            Collection<Client> clients = AgentClientMap.values();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put(RocketMQA2AConstant.LITE_TOPIC, sessionId);
            if (!CollectionUtils.isEmpty(clients)) {
                for (Client client : clients) {
                    try {
                        client.resubscribe(new TaskIdParams("", metadata));
                    } catch (Exception e) {
                        log.error("resubscribeStream  client.resubscribe error, userId: {}, sessionId: {}, error: {}", userId, sessionId, e.getMessage());
                    }
                }
            }
            return Flux.from(sink.asFlux());
        } catch (Exception e) {
            log.error("resubscribeStream error, userId: {}, sessionId: {}, error: {}", userId, sessionId, e.getMessage());
        }
        return null;
    }

    private void dealEventContent(String content, Sinks.Many<String> sink, List<TaskInfo> taskList, String userId, String sessionId) {
        if (StringUtils.isEmpty(content) || null == sink || StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            return;
        }
        String taskId = UUID.randomUUID().toString();
        if (content.startsWith("{")) {
            try {
                Mission mission = JSON.parseObject(content, Mission.class);
                if (null != mission) {
                    TaskInfo taskInfo = taskMap.computeIfAbsent(taskId, k -> {return new TaskInfo(taskId, mission.getMessageInfo(), sessionId, userId, sink);});
                    if (null != taskList) {
                        taskList.add(taskInfo);
                    }
                    log.info("转发请求到其他的Agent, 等待其响应，Agent: {}, 问题: {}", mission.getAgent(), mission.getMessageInfo());
                    emitMessage(sink, "******" + AGENT_NAME + "转发请求到其他的Agent, 等待其响应，Agent: " + mission.getAgent() + "，问题: " + mission.getMessageInfo(), false);
                    dealMissionByMessage(mission, taskId, sessionId);
                }
            } catch (Exception e) {
                log.error("解析过程出现异常, " + e.getMessage());
            }
        } else {
            emitMessage(sink, content, true);
        }
    }

    private void dealMissionByMessage(Mission mission, String taskId, String sessionId) {
        if (null == mission || StringUtils.isEmpty(mission.getAgent()) || StringUtils.isEmpty(mission.getMessageInfo()) || StringUtils.isEmpty(taskId) || StringUtils.isEmpty(sessionId)) {
            log.error("dealMissionByMessage param error, mission: {}, taskId: {}, sessionId: {}", JSON.toJSONString(mission), taskId, sessionId);
            return;
        }
        try {
            String agentName = mission.getAgent().replaceAll(" ", "");
            Client client = AgentClientMap.get(agentName);
            client.sendMessage(A2A.createUserTextMessage(mission.getMessageInfo(), sessionId, taskId));
            log.info("dealMissionByMessage message: {}", mission.getMessageInfo());
        } catch (Exception e) {
            log.error("dealMissionByMessage error, mission: {}, taskId: {}, sessionId: {}, error: {}", JSON.toJSONString(mission), taskId, sessionId, e.getMessage());
        }
    }

    public BaseAgent initAgent(String weatherAgent, String travelAgent) {
        if (StringUtils.isEmpty(weatherAgent) || StringUtils.isEmpty(travelAgent)) {
            log.error("initAgent 参数缺失，请补充天气助手weatherAgent、行程安排助手travelAgent");
            return null;
        }
        QWModel qwModel = QWModelRegistry.getModel(API_KEY);
        return LlmAgent.builder()
            .name(APP_NAME)
            .model(qwModel)
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

    private void initAgentCardInfo(String accessKey, String secretKey, String agentName, String agentUrl) {
        if (StringUtils.isEmpty(agentName) || StringUtils.isEmpty(agentUrl)) {
            log.error("initAgentCardInfo param error, agentName: {}, agentUrl: {}", agentName, agentUrl);
            return;
        }
        AgentCard finalAgentCard = new A2ACardResolver(agentUrl).getAgentCard();
        log.info("Successfully fetched public agent card: {}", finalAgentCard.description());
        List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();
        consumers.add((event, agentCard) -> {
            if (event instanceof TaskUpdateEvent taskUpdateEvent) {
                Task task = taskUpdateEvent.getTask();
                if (null == task) {
                    return;
                }
                TaskInfo taskInfo = taskMap.get(task.getId());
                Many<String> sink = taskInfo.getSink();
                List<Artifact> artifacts = task.getArtifacts();
                if (null != artifacts && artifacts.size() == 1) {
                    emitMessage(sink, "\n \n", false);
                }
                if (!CollectionUtils.isEmpty(artifacts)) {
                    TaskState state = task.getStatus().state();
                    String msg = extractTextFromMessage(artifacts.get(artifacts.size() - 1));
                    log.info("receive msg: {}", msg);
                    boolean result = emitMessage(sink, msg, false);
                    if (!result) {
                        throw new RuntimeException("client close stream");
                    }
                    if (state == TaskState.COMPLETED) {
                        StringBuilder stringBuilder = new StringBuilder();
                        for (Artifact tempArtifact : artifacts) {
                            stringBuilder.append(extractTextFromMessage(tempArtifact));
                        }
                        dealAgentResponse(stringBuilder.toString(), taskInfo.getUserId(), taskInfo.getSessionId(), taskInfo.getTaskId());
                    }
                }
            }
        });

        Consumer<Throwable> streamingErrorHandler = (error) -> {
            log.error("Streaming error: {}", error.getMessage());
        };
        //config rocketmq info
        RocketMQTransportConfig rocketMQTransportConfig = new RocketMQTransportConfig();
        rocketMQTransportConfig.setRocketMQNamespace(ROCKETMQ_NAMESPACE);
        rocketMQTransportConfig.setAccessKey(accessKey);
        rocketMQTransportConfig.setSecretKey(secretKey);
        rocketMQTransportConfig.setWorkAgentResponseGroupID(WORK_AGENT_RESPONSE_GROUP_ID);
        rocketMQTransportConfig.setWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);
        Client client = Client.builder(finalAgentCard)
            .addConsumers(consumers)
            .streamingErrorHandler(streamingErrorHandler)
            .withTransport(RocketMQTransport.class, rocketMQTransportConfig)
            .build();
        AgentClientMap.put(agentName, client);
        log.info("init success");
    }

    private static String extractTextFromMessage(Artifact artifact) {
        if (null == artifact) {
            return "";
        }
        List<io.a2a.spec.Part<?>> parts = artifact.parts();
        if (CollectionUtils.isEmpty(parts)) {
            return "";
        }
        StringBuilder textBuilder = new StringBuilder();
        for (io.a2a.spec.Part part : parts) {
            if (part instanceof TextPart textPart) {
                textBuilder.append(textPart.getText());
            }
        }
        return textBuilder.toString();
    }

    private void dealAgentResponse(String result, String userId, String sessionId, String taskId) {
        if (StringUtils.isEmpty(result)) {
            return;
        }
        Maybe<Session> sessionMaybe = sessionService.getSession(APP_NAME, userId, sessionId, Optional.empty());
        Event event = Event.builder()
            .id(UUID.randomUUID().toString())
            .invocationId(UUID.randomUUID().toString())
            .author(APP_NAME)
            .content(buildContent(result))
            .build();
        Session session = sessionMaybe.blockingGet();
        sessionService.appendEvent(session, event);
        Content userMsg = Content.fromParts(Part.fromText(result));
        Flowable<Event> events = runner.runAsync(userId, sessionId, userMsg);
        events.blockingForEach(eventSub -> {
            boolean equals = lastQuestion.equals(eventSub.stringifyContent());
            if (equals) {
                return;
            }
            lastQuestion = eventSub.stringifyContent();
            String content = lastQuestion;
            TaskInfo taskInfo = taskMap.get(taskId);
            Many<String> sink = taskInfo.getSink();
            if (!StringUtils.isEmpty(content)) {
                if (content.startsWith("{")) {
                    try {
                        Mission mission = JSON.parseObject(content, Mission.class);
                        if (null != mission && !StringUtils.isEmpty(mission.getMessageInfo()) && !StringUtils.isEmpty(mission.getAgent())) {
                            log.info("转发到其他的Agent, 等待其他Agent响应，Agent: {}, 问题: {}", mission.getAgent(), mission.getMessageInfo());
                            emitMessage(sink,"\n \n ******" + AGENT_NAME + " 转发请求到其他的Agent, 等待其响应，Agent: " + mission.getAgent() + "， 问题: " + mission.getMessageInfo(), false);
                            dealMissionByMessage(mission, taskId, sessionId);
                        }
                    } catch (Exception e) {
                        log.error("parse result error: {}", e.getMessage());
                    }
                } else {
                    sink.tryEmitComplete();
                    completeTask(taskInfo);
                }
            }
        });
    }

    /**
     * 对Task相关的资源进行清理
     * @param taskInfo
     */
    private void completeTask(TaskInfo taskInfo) {
        if (null == taskInfo || StringUtils.isEmpty(taskInfo.getTaskId())) {
            log.error("completeTask taskInfo is null or taskId is empty");
            return;
        }
        String taskId = taskInfo.getTaskId();
        taskMap.remove(taskId);
        log.info("completeTask taskMap clear success taskId: {}", taskId);
        Map<String, List<TaskInfo>> sessionTaskListMap = userSessionTaskListMap.get(taskInfo.getUserId());
        if (null != sessionTaskListMap) {
            List<TaskInfo> taskInfos = sessionTaskListMap.get(taskInfo.getSessionId());
            if (CollectionUtils.isEmpty(taskInfos)) {
                return;
            }
            boolean result = taskInfos.removeIf(next -> next.getTaskId().equals(taskId));
            log.info("completeTask userSessionTaskListMap clear success, taskId: {}, result: {}", taskId, result);
        }
    }

    private static Content buildContent(String content) {
        if (StringUtils.isEmpty(content)) {
            return null;
        }
        return Content.builder()
            .role(APP_NAME)
            .parts(ImmutableList.of(Part.builder().text(content).build()))
            .build();
    }

    private static void printSystemInfo(String message) {
        System.out.println("\u001B[34m[SYSTEM] " + message + "\u001B[0m");
    }

    private static boolean emitMessage(Sinks.Many<String> sink, String msg, boolean isFinish) {
        Sinks.EmitResult result = sink.tryEmitNext(msg);
        switch (result) {
            case OK:
                log.info("📤 成功发送: {}", msg);
                break;
            case FAIL_OVERFLOW:
            case FAIL_CANCELLED:
            case FAIL_TERMINATED:
                log.error("🛑 上游检测到问题，停止发送。原因: {}", result);
                return false;
            default:
                log.error("⚠️ 发送状态: {}", result);
        }
        if (isFinish) {
            sink.tryEmitComplete();
        }
        return true;
    }

}
