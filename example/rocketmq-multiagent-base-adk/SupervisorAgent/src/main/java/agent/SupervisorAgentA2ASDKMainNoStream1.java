package agent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.alibaba.fastjson.JSON;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.artifacts.InMemoryArtifactService;
import com.google.adk.events.Event;
import com.google.adk.runner.Runner;
import com.google.adk.sessions.InMemorySessionService;
import com.google.adk.sessions.Session;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import common.Mission;
import common.QWModel;
import common.QWModelRegistry;
import io.a2a.A2A;
import io.a2a.client.Client;
import io.a2a.client.ClientEvent;
import io.a2a.client.MessageEvent;
import io.a2a.client.config.ClientConfig;
import io.a2a.client.http.A2ACardResolver;
import io.a2a.spec.A2AClientException;
import io.a2a.spec.AgentCard;
import io.a2a.spec.Artifact;
import io.a2a.spec.Task;
import io.a2a.spec.TaskQueryParams;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import org.apache.rocketmq.a2a.transport.RocketMQTransport;
import org.apache.rocketmq.a2a.transport.RocketMQTransportConfig;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SupervisorAgentA2ASDKMainNoStream1 {
    private static final Logger log = LoggerFactory.getLogger(SupervisorAgentA2ASDKMainNoStream1.class);
    private static final String AGENT_NAME = "SupervisorAgent";
    private static final String USER_ID = "rocketmq_a2a_user";
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
    private static final String YOU = "You";
    private static final String AGENT = "Agent";
    private static String lastQuestion = "";
    private static InMemorySessionService sessionService;
    private static final Map<String, Client> AgentClientMap = new HashMap<>();
    private static String sessionId;
    private static Runner runner;
    private static ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static Set<Mission> taskIdSet = new HashSet<>();


    public static void main(String[] args) throws Exception {
        if (!checkConfigParam()) {
            System.out.println("配置参数不完整，请检查参数配置情况");
            return;
        }
        BaseAgent baseAgent = initAgent(WEATHER_AGENT_NAME, TRAVEL_AGENT_NAME);
        printSystemInfo("🚀 启动 QWen为底座模型的 " + AGENT_NAME + "，擅长处理天气问题与行程安排规划问题，在本例中使用RocketMQ LiteTopic版本实现多个Agent之间的通讯");
        printSystemInfo("📋 初始化会话...");
        InMemoryArtifactService artifactService = new InMemoryArtifactService();
        sessionService = new InMemorySessionService();
        runner = new Runner(baseAgent, APP_NAME, artifactService, sessionService, /* memoryService= */ null);
        Session session = runner
            .sessionService()
            .createSession(APP_NAME, USER_ID)
            .blockingGet();
        printSystemSuccess("✅ 会话创建成功: " + session.id());
        initSchedulerAndCheckTask();
        sessionId = session.id();
        initAgentCardInfo(ACCESS_KEY, SECRET_KEY, WEATHER_AGENT_NAME, WEATHER_AGENT_URL);
        initAgentCardInfo(ACCESS_KEY, SECRET_KEY, TRAVEL_AGENT_NAME, TRAVEL_AGENT_URL);
        printSystemInfo("💡 输入 'quit' 退出，输入 'help' 查看帮助");
        try (Scanner scanner = new Scanner(System.in, String.valueOf(StandardCharsets.UTF_8))) {
            while (true) {
                printPrompt(YOU);
                String userInput = scanner.nextLine().trim();
                if ("quit".equalsIgnoreCase(userInput)) {
                    printSystemInfo("👋 再见！");
                    System.exit(0);
                    break;
                }
                if ("help".equalsIgnoreCase(userInput)) {
                    printHelp();
                    continue;
                }
                if (StringUtils.isEmpty(userInput)) {
                    printSystemInfo("请不要输入空值");
                    continue;
                }
                printSystemInfo("🤔 正在思考...");
                log.info("用户输入: {}", userInput);
                Content userMsg = Content.fromParts(Part.fromText(userInput));
                Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);
                events.blockingForEach(event -> {
                    String content = event.stringifyContent();
                    dealEventContent(content);
                });
            }
        }
    }

    private static boolean checkConfigParam() {
        if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC) || StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID) || StringUtils.isEmpty(API_KEY)) {
            if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC)) {
                System.out.println("请配置RocketMQ 的轻量消息Topic workAgentResponseTopic");
            }
            if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID)) {
                System.out.println("请配置RocketMQ 的轻量消息消费者 workAgentResponseGroupID");
            }
            if (StringUtils.isEmpty(API_KEY)) {
                System.out.println("请配置SupervisorAgent qwen-plus apiKey");
            }
            return false;
        }
        return true;
    }

    private static void dealEventContent(String content) {
        if (StringUtils.isEmpty(content)) {
            return;
        }
        if (content.startsWith("{")) {
            try {
                Mission mission = JSON.parseObject(content, Mission.class);
                if (null != mission) {
                    printPrompt(AGENT);
                    System.out.println(AGENT_NAME + " 转发请求到其他的Agent, 等待其响应，Agent: " + mission.getAgent() + " 问题: " + mission.getMessageInfo());
                    dealMissionByMessage(mission);
                }
            } catch (Exception e) {
                System.out.println("解析过程出现异常");
            }
        } else {
            printPrompt(AGENT);
            System.out.println(content);
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

    private static void dealMissionByMessage(Mission mission) {
        if (null == mission || StringUtils.isEmpty(mission.getAgent()) || StringUtils.isEmpty(mission.getMessageInfo())) {
            return;
        }
        try {
            String agent = mission.getAgent().replaceAll(" ", "");
            Client client = AgentClientMap.get(agent);
            io.a2a.spec.Message a2aMessage = A2A.toUserMessage(mission.getMessageInfo()); // the message ID will be automatically generated for you
            String taskId = UUID.randomUUID().toString();
            a2aMessage.setTaskId(taskId);
            mission.setTaskId(taskId);
            //向选择的Agent发送消息
            client.sendMessage(a2aMessage);
            taskIdSet.add(mission);
            System.out.println("Sending message: " + mission.getMessageInfo());
        } catch (Exception e) {
            System.out.println("error" + e.getMessage());
        }
    }

    private static void printSystemInfo(String message) {
        System.out.println("\u001B[34m[SYSTEM] " + message + "\u001B[0m");
        log.info(message);
    }

    private static void printSystemSuccess(String message) {
        System.out.println("\u001B[32m[SUCCESS] " + message + "\u001B[0m");
        log.info(message);
    }
    private static void printPrompt(String role) {
        System.out.print("\n\u001B[36m" + role + " > \u001B[0m");
    }

    private static void printHelp() {
        System.out.println("\n\u001B[35m📖 帮助信息:\u001B[0m");
        System.out.println("  • 询问天气: '杭州下周的天气情况怎么样'");
        System.out.println("  • 帮忙安排行程: '帮我做一个下周三到下周日杭州周边自驾游方案'");
        System.out.println("  • 退出程序: 'quit'");
        System.out.println("  • 显示帮助: 'help'");
    }

    public static BaseAgent initAgent(String weatherAgent, String travelAgent) {
        if (StringUtils.isEmpty(weatherAgent) || StringUtils.isEmpty(travelAgent)) {
            System.out.println("initAgent 参数缺失，请补充天气助手weatherAgent、行程安排助手travelAgent");
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

    private static void initAgentCardInfo(String accessKey, String secretKey, String agentName, String agentUrl) {
        if (StringUtils.isEmpty(accessKey) || StringUtils.isEmpty(secretKey)) {
            System.out.println("initAgentCardInfo param error");
            return;
        }
        AgentCard finalAgentCard = new A2ACardResolver(agentUrl).getAgentCard();
        System.out.println("Successfully fetched public agent card: " + finalAgentCard.description());
        final CompletableFuture<String> messageResponse = new CompletableFuture<>();
        List<BiConsumer<ClientEvent, AgentCard>> consumers = new ArrayList<>();
        consumers.add((event, agentCard) -> {
            if (event instanceof MessageEvent messageEvent) {
                io.a2a.spec.Message responseMessage = messageEvent.getMessage();
                StringBuilder textBuilder = new StringBuilder();
                if (responseMessage.getParts() != null) {
                    for (io.a2a.spec.Part<?> part : responseMessage.getParts()) {
                        if (part instanceof TextPart textPart) {
                            textBuilder.append(textPart.getText());
                        }
                    }
                }
                messageResponse.complete(textBuilder.toString());
            } else {
                System.out.println("Received client event: " + event.getClass().getSimpleName());
            }
        });
        Consumer<Throwable> streamingErrorHandler = (error) -> {
            System.err.println("Streaming error occurred: " + error.getMessage());
            error.printStackTrace();
            messageResponse.completeExceptionally(error);
        };
        RocketMQTransportConfig rocketMQTransportConfig = new RocketMQTransportConfig();
        rocketMQTransportConfig.setNamespace(ROCKETMQ_NAMESPACE);
        rocketMQTransportConfig.setAccessKey(accessKey);
        rocketMQTransportConfig.setSecretKey(secretKey);
        rocketMQTransportConfig.setWorkAgentResponseGroupID(WORK_AGENT_RESPONSE_GROUP_ID);
        rocketMQTransportConfig.setWorkAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC);

        Client client = Client.builder(finalAgentCard)
            .addConsumers(consumers)
            .clientConfig(ClientConfig.builder().setPolling(true).build())
            .streamingErrorHandler(streamingErrorHandler)
            .withTransport(RocketMQTransport.class, rocketMQTransportConfig)
            .build();
        AgentClientMap.put(agentName, client);
        System.out.println("init success");
    }

    private static String extractTextFromMessage(Artifact artifact) {
        StringBuilder textBuilder = new StringBuilder();
        List<io.a2a.spec.Part<?>> parts = artifact.parts();
        for (io.a2a.spec.Part part : parts) {
            if (part instanceof TextPart textPart) {
                textBuilder.append(textPart.getText());
            }
        }
        return textBuilder.toString();
    }

    private static void initSchedulerAndCheckTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Iterator<Mission> iterator = taskIdSet.iterator();
                while (iterator.hasNext()) {
                    Mission nextMission = iterator.next();
                    if (null == nextMission || StringUtils.isEmpty(nextMission.getTaskId()) || StringUtils.isEmpty(nextMission.getAgent()) || StringUtils.isEmpty(nextMission.getMessageInfo())) {
                        System.out.println("schedulerAndCheckTask param error, nextMission: " + JSON.toJSONString(nextMission));
                        iterator.remove();
                    }
                    if (null == AgentClientMap.get(nextMission.getAgent())) {
                        System.out.println("schedulerAndCheckTask client not found, topic: " + nextMission.getAgent());
                        iterator.remove();
                    }
                    Client client = AgentClientMap.get(nextMission.getAgent());
                    try {
                        //查询task
                        Task task = client.getTask(new TaskQueryParams(nextMission.getTaskId()));
                        System.out.println("start query task taskStatus " + task.getStatus().state());
                        if (task.getStatus().state() == TaskState.COMPLETED) {
                            String result = extractTextFromMessage(task.getArtifacts().get(task.getArtifacts().size() - 1));
                            printPrompt("Agent");
                            iterator.remove();

                            Maybe<Session> sessionMaybe = sessionService.getSession(APP_NAME, USER_ID, sessionId, Optional.empty());
                            Event event = Event.builder()
                                .id(UUID.randomUUID().toString())
                                .invocationId(UUID.randomUUID().toString())
                                .author(APP_NAME)
                                .content(buildContent(result))
                                .build();
                            Session session = sessionMaybe.blockingGet();
                            sessionService.appendEvent(session, event);
                            Content userMsg = Content.fromParts(Part.fromText(result));
                            Flowable<Event> events = runner.runAsync(USER_ID, session.id(), userMsg);
                            events.blockingForEach(eventSub -> {
                                boolean equals = lastQuestion.equals(eventSub.stringifyContent());
                                if (equals) {
                                    return;
                                }
                                lastQuestion = eventSub.stringifyContent();
                                String content = lastQuestion;
                                if (content != null && !content.trim().isEmpty()) {
                                    if (content.startsWith("{")) {
                                        try {
                                            Mission mission = JSON.parseObject(content, Mission.class);
                                            if (null != mission && !StringUtils.isEmpty(mission.getMessageInfo()) && !StringUtils.isEmpty(mission.getAgent())) {
                                                printPrompt("Agent");
                                                System.out.println("转发到其他的Agent, 等待其他Agent响应，AgentTopic: " + mission.getAgent() + " 问题: " + mission.getMessageInfo());
                                                dealMissionByMessage(mission);
                                            }
                                        } catch (Exception e) {
                                            System.out.println("解析过程出现异常");
                                        }
                                    } else {
                                        System.out.println(content);
                                    }
                                } else {
                                    System.out.println(content);
                                    log.debug("Agent 响应: {}", content);
                                }
                            });
                            printPrompt("You");
                        } else {
                            printPrompt("Agent");
                            System.out.println("检查任务 TaskId: " + nextMission.getTaskId() + "任务当前的状态为: " + task.getStatus().state());
                            printPrompt("You");
                        }
                    } catch (A2AClientException e) {
                        System.out.println("check task error " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                System.out.println("initSchedulerAndCheckTask error " + e.getMessage());
            }
        }, 5, 20, TimeUnit.SECONDS);
    }

}
