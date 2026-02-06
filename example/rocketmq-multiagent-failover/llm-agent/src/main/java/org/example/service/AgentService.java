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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.fastjson.JSON;
import io.reactivex.Flowable;
import jakarta.annotation.PostConstruct;
import model.RocketMQRequest;
import model.RocketMQResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import util.RocketMQUtil;
import static qwen.QwenUtil.checkQwenConfigParam;
import static qwen.QwenUtil.streamCallWithMessage;
import static util.RocketMQUtil.ACCESS_KEY;
import static util.RocketMQUtil.BIZ_CONSUMER_GROUP;
import static util.RocketMQUtil.BIZ_TOPIC;
import static util.RocketMQUtil.ROCKETMQ_ENDPOINT;
import static util.RocketMQUtil.ROCKETMQ_NAMESPACE;
import static util.RocketMQUtil.SECRET_KEY;
import static util.RocketMQUtil.buildMessage;
import static util.RocketMQUtil.checkRocketMQConfigParamServer;

/**
 * AI Agent service that consumes RocketMQ messages, invokes QWen LLM, and streams responses back via RocketMQ.
 * <p>
 * This service uses a dedicated thread pool for streaming tasks and ensures proper resource cleanup.
 * and error handling for both streaming and non-streaming scenarios.
 */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);
    private Producer producer;
    private PushConsumer pushConsumer;
    /**
     * Dedicated thread pool for handling streaming LLM response tasks.
     * Uses CallerRunsPolicy to avoid task loss under high load.
     */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(6, 6, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(10_0000), new CallerRunsPolicy());
    private MultiSSeSupport multiSSeSupport;

    @PostConstruct
    public void init() throws ClientException {
        checkRocketMQConfigParamServer();
        checkQwenConfigParam();
        this.producer = RocketMQUtil.buildProducer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY);
        this.pushConsumer = RocketMQUtil.buildPushConsumer(ROCKETMQ_ENDPOINT, ROCKETMQ_NAMESPACE, ACCESS_KEY, SECRET_KEY, BIZ_CONSUMER_GROUP, BIZ_TOPIC, buildMessageListener());
        this.multiSSeSupport = new MultiSSeSupport(producer);
        log.info("AgentService initialized successfully");
    }

    /**
     * build a message listener for processing incoming RocketMQ requests.
     * @return a configured {@link MessageListener}.
     */
    private MessageListener buildMessageListener() {
        return messageView -> {
            try {
                byte[] result = new byte[messageView.getBody().remaining()];
                messageView.getBody().get(result);
                RocketMQRequest request = JSON.parseObject(new String(result, StandardCharsets.UTF_8), RocketMQRequest.class);
                if (null == request) {
                    log.warn("AgentService failed to deserialize message to RocketMQRequest, result is null, skip it, messageId: [{}]", messageView.getMessageId());
                    return ConsumeResult.SUCCESS;
                }
                return processRequestAndEmitStream(request);
            } catch (Exception e) {
                log.error("AgentService consume error", e);
                return ConsumeResult.FAILURE;
            }
        };
    }

    /**
     * Processes a RocketMQ request and emits the streaming response via RocketMQ.
     *
     * <p><strong>Note:</strong>
     * This method blocks the current thread to await result confirmation. It is used for message consumption acknowledgment only.
     *
     * @param request the input request containing user question, userId, sessionId, LiteTopic, etc.
     * @return {@code ConsumeResult.SUCCESS} if the async processing completes successfully; {@code ConsumeResult.FAILURE} otherwise.
     * @throws NoApiKeyException if the API key is missing or invalid.
     * @throws InputRequiredException if the required input (e.g., question) is null or empty.
     * @throws ExecutionException if an exception occurs during the execution of the async task.
     * @throws InterruptedException if the waiting thread is interrupted while blocked.
     * @throws TimeoutException if the async processing does not complete within 15 minutes.
     */
    private ConsumeResult processRequestAndEmitStream(RocketMQRequest request) throws NoApiKeyException, InputRequiredException, ExecutionException, InterruptedException, TimeoutException {
        Flowable<GenerationResult> flowable = streamCallWithMessage(request.getQuestion());
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        this.executor.execute(() -> {
            multiSSeSupport.writeRocketmq(flowable, request.getWorkAgentResponseTopic(), request.getLiteTopic(), completableFuture, request.getTaskId(), request.getUserId(), request.getQuestion());
        });
        return Boolean.TRUE.equals(completableFuture.get(15, TimeUnit.MINUTES)) ? ConsumeResult.SUCCESS : ConsumeResult.FAILURE;
    }


    /**
     * Inner class to handle streaming response writing to RocketMQ.
     */
    private static class MultiSSeSupport {
        private final Producer producer;

        private MultiSSeSupport(Producer producer) {
            this.producer = producer;
        }

        /**
         * Subscribes to a streaming LLM response and sends tokens incrementally via RocketMQ.
         *
         * @param result the streaming response from Qwen.
         * @param workAgentResponseTopic destination topic for responses.
         * @param liteTopic lightweight topic for SSE.
         * @param completableFuture future to signal task completion.
         * @param taskId task identifier.
         * @param userId user identifier.
         * @param question original user question (for context).
         */
        public void writeRocketmq(Flowable<GenerationResult> result, String workAgentResponseTopic, String liteTopic, CompletableFuture<Boolean> completableFuture, String taskId, String userId, String question) {
            if (null == result || StringUtils.isEmpty(workAgentResponseTopic) || StringUtils.isEmpty(liteTopic) || StringUtils.isEmpty(taskId) || StringUtils.isEmpty(userId)) {
                log.warn("MultiSSeSupport writeRocketmq param is invalid, result: [{}], workAgentResponseTopic: [{}], liteTopic: [{}], taskId: [{}], userId: [{}]", result, workAgentResponseTopic, liteTopic, taskId, userId);
                completableFuture.complete(true);
                return;
            }
            result.subscribe(
                // next
                message -> {
                    try {
                        String content = message.getOutput().getChoices().get(0).getMessage().getContent();
                        RocketMQResponse response = RocketMQResponse.builder().liteTopic(liteTopic).responseBody(content).stream(true).end(false).userId(userId).taskId(taskId).question(question).build();
                        SendReceipt send = producer.send(buildMessage(workAgentResponseTopic, liteTopic, response));
                        log.debug("MultiSSeSupport send response success, msgId: [{}], time: [{}]", send.getMessageId(), System.currentTimeMillis());
                    } catch (Exception e) {
                        log.error("MultiSSeSupport send stream error", e);
                    }
                },
                // error
                error -> {
                    log.warn("MultiSSeSupport send occur error", error);
                    completableFuture.complete(false);
                },
                // complete
                () -> {
                    RocketMQResponse response = RocketMQResponse.builder().liteTopic(liteTopic).responseBody("").stream(true).end(true).userId(userId).taskId(taskId).question(question).build();
                    SendReceipt send = producer.send(buildMessage(workAgentResponseTopic, liteTopic, response));
                    completableFuture.complete(true);
                    log.debug("MultiSSeSupport send complete msgId: [{}]", send.getMessageId());
                });
        }
    }
}

