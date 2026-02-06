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

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;

import com.alibaba.fastjson.JSON;

import jakarta.annotation.PostConstruct;
import model.RocketMQRequest;
import model.RocketMQResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.OffsetOption;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.java.message.MessageViewImpl;
import org.example.stream.event.StreamEvent;
import org.example.stream.handle.StreamingTaskHandle;
import org.example.stream.recovery.StreamRecoveryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;
import util.RocketMQUtil;

import static util.RocketMQUtil.checkRocketMQConfigParamClient;

/**
 * Service class for handling RocketMQ operations including message sending, subscription management, and stream recovery.
 * Manages user sessions and streaming tasks through RocketMQ LiteTopics.
 */
@Service
public class RocketMQService {
    private static final Logger log = LoggerFactory.getLogger(RocketMQService.class);
    private static final String WORK_AGENT_RESPONSE_TOPIC = System.getProperty("workAgentResponseTopic");
    private static final String WORK_AGENT_RESPONSE_GROUP_ID = System.getProperty("workAgentResponseGroupID");
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace");
    private static final String ROCKETMQ_ENDPOINT = System.getProperty("rocketMQEndpoint");
    private static final String ACCESS_KEY = System.getProperty("rocketMQAK");
    private static final String SECRET_KEY = System.getProperty("rocketMQSK");
    private static final String AGENT_TOPIC = System.getProperty("agentTopic");
    private final Map<String /* userId */, Map<String /* sessionId */, Map<String /* taskId */ , StreamingTaskHandle /* taskInfo */>>> userSessionTaskListMap = new ConcurrentHashMap<>();
    private final Map<String /* userId */, Map<String /* sessionId */, StreamRecoveryContext /* recoverInfo */>> userSessionDefaultSinkMap = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(6, 6, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10_0000), new CallerRunsPolicy());
    private Producer producer;
    private LitePushConsumer litePushConsumer;

    @PostConstruct
    public void init() throws ClientException {
        checkRocketMQConfigParamClient();
        this.producer = RocketMQUtil.buildProducer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY);
        this.litePushConsumer = RocketMQUtil.buildLitePushConsumer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, WORK_AGENT_RESPONSE_GROUP_ID, WORK_AGENT_RESPONSE_TOPIC, buildMessageListener());
        log.info("RocketMQService init success");
    }

    /**
     * Sends a message to RocketMQ and returns the message ID.
     * Subscribes to the session's LiteTopic and creates a new task handle for tracking.
     *
     * @param userId the user's unique identifier.
     * @param sessionId the session's unique identifier.
     * @param question the user's question/query.
     * @param sink the reactive sink for pushing response messages.
     * @return the message ID of the message, or null if sending failed.
     * @throws IllegalArgumentException if any required parameter is null or empty.
     */
    public String sendMessage(String userId, String sessionId, String question, Sinks.Many<String> sink) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId) || StringUtils.isEmpty(question) || null == sink) {
            log.warn("RocketMQService sendMessage param is invalid, userId: [{}], sessionId: [{}], question: [{}], sink: [{}]", userId, sessionId, question, sink);
            throw new IllegalArgumentException("RocketMQService sendMessage param is invalid");
        }
        try {
            this.litePushConsumer.subscribeLite(sessionId);
            log.debug("RocketMQService subscribeLite liteTopic: [{}]", sessionId);
        } catch (Exception e) {
            log.error("RocketMQService sendMessage subscribeLite error", e);
            return null;
        }
        Map<String, Map<String, StreamingTaskHandle>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        Map<String, StreamingTaskHandle> taskInfoMap = sessionTaskListMap.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        String taskId = UUID.randomUUID().toString();
        taskInfoMap.put(taskId, StreamingTaskHandle.builder().taskId(taskId).taskDesc(question).sessionId(sessionId).userId(userId).sink(sink).isRecover(false).build());
        final ClientServiceProvider provider = ClientServiceProvider.loadService();
        try {
            String requestStr = JSON.toJSONString(RocketMQRequest.builder().question(question).agentTopic(AGENT_TOPIC).workAgentResponseTopic(WORK_AGENT_RESPONSE_TOPIC).userId(userId).liteTopic(sessionId).taskId(taskId).build());
            SendReceipt sendReceipt = producer.send(provider.newMessageBuilder().setTopic(AGENT_TOPIC).setBody(requestStr.getBytes(StandardCharsets.UTF_8)).build());
            String msgId = sendReceipt.getMessageId().toString();
            log.debug("RocketMQService sendMessage success, messageId: [{}]", msgId);
            return msgId;
        } catch (Exception e) {
            log.error("RocketMQService sendMessage failed", e);
            return null;
        }
    }

    /**
     * Re-subscribes to a LiteTopic for a user session, optionally starting from a specific offset.
     * Used for recovering streaming sessions after disconnection.
     *
     * @param userId the user's unique identifier.
     * @param sessionId the session's unique identifier.
     * @param sink the reactive sink for pushing messages.
     * @param lastOffset the offset to start consuming from, or null to start from latest.
     * @throws IllegalArgumentException if required parameters are invalid.
     */
    public void reSubLiteTopic(String userId, String sessionId, Sinks.Many<String> sink, Long lastOffset) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId) || null == sink) {
            log.warn("RocketMQService reSubLiteTopic param is invalid, userId: [{}], liteTopic: [{}], sink: [{}]", userId, sessionId, sink);
            throw new IllegalArgumentException("RocketMQService reSubLiteTopic param is invalid");
        }
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        setupStreamRecoveryContext(userId, sessionId, sink, completableFuture);
        try {
            if (null != lastOffset && lastOffset >= 0L) {
                this.litePushConsumer.subscribeLite(sessionId, OffsetOption.ofOffset(lastOffset));
            } else {
                this.litePushConsumer.subscribeLite(sessionId);
            }
            asyncWaitCompleteResult(completableFuture, sink);
        } catch (Exception e) {
            log.error("RocketMQService reSubLiteTopic error", e);
        }
    }

    /**
     * Sets up the stream recovery context for a given user and session.
     * @param userId the user's unique identifier.
     * @param sessionId the session's unique identifier.
     * @param sink the reactive sink to emit data.
     * @param completableFuture completion callback to set result.
     */
    private void setupStreamRecoveryContext(String userId, String sessionId, Sinks.Many<String> sink, CompletableFuture<Boolean> completableFuture) {
        Map<String, Map<String, StreamingTaskHandle>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        sessionTaskListMap.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>());
        Map<String, StreamRecoveryContext> sessionRecoverMap = userSessionDefaultSinkMap.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        sessionRecoverMap.put(sessionId, StreamRecoveryContext.builder().sink(sink).completableFuture(completableFuture).build());
    }

    /**
     * Asynchronously waits for the completion result and handles the sink accordingly.
     *
     * @param completableFuture the future to wait for completion.
     * @param sink the reactive sink to update when complete.
     */
    private void asyncWaitCompleteResult(CompletableFuture<Boolean> completableFuture, Sinks.Many<String> sink) {
        this.executor.submit(() -> {
            try {
                log.debug("RocketMQService reSubLiteTopic completableFuture get result: [{}]", completableFuture.get(180, TimeUnit.SECONDS));
            } catch (Exception e) {
                log.error("asyncWaitCompleteResult error", e);
                sink.tryEmitNext("All tasks had been finished");
                sink.tryEmitComplete();
            }
        });
    }

    /**
     * Unsubscribes from a LiteTopic and cleans up session resources.
     * Emits errors to all incomplete tasks and removes the subscription.
     *
     * @param userId the user's unique identifier.
     * @param liteTopic the LiteTopic to unsubscribe from.
     */
    public void ubSubLiteTopic(String userId, String liteTopic) {
        Map<String, Map<String, StreamingTaskHandle>> sessionTaskListMap = userSessionTaskListMap.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        Map<String, StreamingTaskHandle> taskInfoMap = sessionTaskListMap.computeIfAbsent(liteTopic, k -> new ConcurrentHashMap<>());
        userSessionTaskListMap.remove(userId);
        userSessionDefaultSinkMap.remove(userId);
        List<StreamingTaskHandle> unCompleteList = taskInfoMap.values().stream().filter(i -> !i.isComplete()).toList();
        unCompleteList.forEach(i -> {
            i.getSink().emitError(new RuntimeException("Client disconnected"), Sinks.EmitFailureHandler.FAIL_FAST);
            log.info("Client disconnected success");
        });
        try {
            this.litePushConsumer.unsubscribeLite(liteTopic);
            log.debug("RocketMQService ubSubLiteTopic success, userId: [{}], liteTopic: [{}]", userId, liteTopic);
        } catch (Exception e) {
            log.error("RocketMQService ubSubLiteTopic failed, userId: [{}], liteTopic: [{}]", userId, liteTopic);
        }
    }

    /**
     * Builds a message listener for consuming messages from RocketMQ.
     * Processes incoming messages and routes them to appropriate sinks.
     *
     * @return A message listener implementation.
     */
    private MessageListener buildMessageListener() {
        return messageView -> {
            try {
                Optional<String> liteTopicOpt = messageView.getLiteTopic();
                String liteTopic = liteTopicOpt.get();
                if (StringUtils.isEmpty(liteTopic)) {
                    log.warn("RocketMQService consume message, liteTopic is empty");
                    return ConsumeResult.SUCCESS;
                }
                if (!this.litePushConsumer.getLiteTopicSet().contains(liteTopic)) {
                    log.warn("RocketMQService consume message lite topic has been removed, liteTopic: [{}]", liteTopic);
                    return ConsumeResult.SUCCESS;
                }
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                RocketMQResponse response = JSON.parseObject(new String(result, StandardCharsets.UTF_8), RocketMQResponse.class);
                if (null == response || StringUtils.isEmpty(response.getTaskId())) {
                    log.warn("RocketMQService consume message error, response is null or taskId is empty");
                    return ConsumeResult.SUCCESS;
                }
                processResponseResult(response, ((MessageViewImpl)messageView).getOffset());
                return ConsumeResult.SUCCESS;
            } catch (Exception e) {
                log.error("RocketMQService consumer message error, msgId: [{}]", messageView.getMessageId(), e);
                return ConsumeResult.SUCCESS;
            }
        };
    }

    /**
     * Processes the received response message and emits it to the appropriate sink.
     * Handles task completion logic based on response flags.
     *
     * @param response The response message from RocketMQ.
     * @param offset The offset of the message in the topic.
     */
    private void processResponseResult(RocketMQResponse response, Long offset) {
        if (null == response) {
            log.warn("dealResult response is null");
            return;
        }
        String taskId = response.getTaskId();
        Map<String, Map<String, StreamingTaskHandle>> sessionTaskMap = userSessionTaskListMap.computeIfAbsent(response.getUserId(), k -> new HashMap<>());
        Map<String, StreamingTaskHandle> taskInfoMap = sessionTaskMap.get(response.getLiteTopic());
        StreamRecoveryContext streamRecoveryContext = null;
        if (CollectionUtils.isEmpty(taskInfoMap)) {
            streamRecoveryContext = handleTaskRecoverIfNeed(taskInfoMap, response);
        }
        StreamingTaskHandle streamingTaskHandle = taskInfoMap.get(taskId);
        if (null == streamingTaskHandle || null == streamingTaskHandle.getSink()) {
            log.warn("dealResult streamingTaskHandle/getSink is null");
            return;
        }
        String result = response.getResponseBody();
        Many<String> sink = streamingTaskHandle.getSink();
        sink.tryEmitNext(JSON.toJSONString(StreamEvent.builder().offset(offset).content(result).build()));
        log.debug("dealResult sink emit result: [{}], offset: [{}]", result, offset);
        handleTaskCompletion(response, streamRecoveryContext, streamingTaskHandle, sink, taskInfoMap, taskId);
    }

    /**
     * Handles task recovery if needed when task information map is empty.
     * Checks if there's recovery context for the user session and creates a recovery task handle.
     *
     * @param taskInfoMap map containing existing task handles.
     * @param response response message from RocketMQ.
     * @return StreamRecoveryContext if recovery is needed, otherwise null.
     */
    private StreamRecoveryContext handleTaskRecoverIfNeed(Map<String, StreamingTaskHandle> taskInfoMap, RocketMQResponse response) {
        StreamRecoveryContext streamRecoveryContext = null;
        if (CollectionUtils.isEmpty(taskInfoMap)) {
            Map<String, StreamRecoveryContext> sessionSink = userSessionDefaultSinkMap.get(response.getUserId());
            if (null == sessionSink || !sessionSink.containsKey(response.getLiteTopic())) {
                log.warn("handleTaskRecoverIfNeed taskSinkMap is null or do not contains taskId: [{}]", response.getTaskId());
                throw new RuntimeException("user session has benn remove");
            }
            streamRecoveryContext = sessionSink.get(response.getLiteTopic());
            StreamingTaskHandle streamingTaskHandle = StreamingTaskHandle.builder()
                .taskId(response.getTaskId())
                .taskDesc(response.getQuestion())
                .sessionId(response.getLiteTopic())
                .userId(response.getUserId())
                .sink(streamRecoveryContext.getSink())
                .isRecover(true)
                .build();
            taskInfoMap.put(response.getTaskId(), streamingTaskHandle);
        }
        return streamRecoveryContext;
    }

    /**
     * Handles task completion logic when end flag is set in response
     * Completes futures, emits completion signals, and cleans up task maps.
     *
     * @param response response message from RocketMQ.
     * @param streamRecoveryContext recovery context if this is a recovered task.
     * @param streamingTaskHandle task handle being processed.
     * @param sink reactive sink for emitting messages.
     * @param taskInfoMap map of current task handles.
     * @param taskId ID of the task being completed.
     */
    private void handleTaskCompletion(RocketMQResponse response, StreamRecoveryContext streamRecoveryContext,
                                    StreamingTaskHandle streamingTaskHandle, Many<String> sink,
                                    Map<String, StreamingTaskHandle> taskInfoMap, String taskId) {
        if (!response.isEnd()) {
            return;
        }
        if (null != streamRecoveryContext && null != streamRecoveryContext.getCompletableFuture()) {
            streamRecoveryContext.getCompletableFuture().complete(true);
        }
        if (!streamingTaskHandle.isRecover()) {
            log.debug("handleTaskCompletion sink try complete");
            sink.tryEmitComplete();
        }
        streamingTaskHandle.setComplete(true);
        taskInfoMap.remove(taskId);
        log.debug("handleTaskCompletion compete task taskId: [{}]", taskId);
    }

}
