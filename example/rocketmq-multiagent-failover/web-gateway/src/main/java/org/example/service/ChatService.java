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
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Service class for handling chat requests, including streaming chat, closing connections, and resubscribing streams.
 * This service interacts with RocketMQ through [RocketMQService](file:///Users/zhaoke/cloudCode/RocketMQMultiAgent/rocketmq-multiagent-release/SupervisorAgent-Web/src/main/java/org/example/service/RocketMQService.java#L22-L160).
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    RocketMQService rocketMQService;

    /**
     * Initiates a streaming chat session.
     *
     * @param userId Unique identifier for the user.
     * @param sessionId Unique identifier for the session.
     * @param question The user's question content.
     * @return A [Flux] object containing the chat response stream.
     * @throws IllegalArgumentException Thrown when input parameters are empty.
     * @throws RuntimeException Thrown when message sending fails.
     */
    public Flux<String> streamChat(String userId, String sessionId, String question) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId) || StringUtils.isEmpty(question)) {
            log.warn("ChatService streamChat param is invalid, userId: [{}], sessionId: [{}], question: [{}]", userId, sessionId, question);
            throw new IllegalArgumentException("ChatService streamChat param is invalid");
        }
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        String msgId = rocketMQService.sendMessage(userId, sessionId, question, sink);
        log.debug("ChatService streamChat send message success, userId: [{}], sessionId: [{}], question: [{}]", userId, sessionId, question);
        if (StringUtils.isEmpty(msgId)) {
            log.warn("ChatService streamChat send message error, msgId is empty");
            throw new RuntimeException("ChatService streamChat send message error");
        }
        return Flux.from(sink.asFlux());
    }

    /**
     * Closes the specified user's chat session.
     *
     * @param userId Unique identifier for the user.
     * @param sessionId Unique identifier for the session.
     * @throws IllegalArgumentException Thrown when input parameters are empty.
     * @throws RuntimeException Thrown when closing session fails.
     */
    public void closeStreamChat(String userId, String sessionId) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.warn("ChatService closeStreamChat param is invalid, userId: [{}], sessionId: [{}]", userId, sessionId);
            throw new IllegalArgumentException("ChatService close stream chat error");
        }
        try {
            rocketMQService.ubSubLiteTopic(userId, sessionId);
            log.debug("ChatService closeStreamChat success, userId: [{}], sessionId: [{}]", userId, sessionId);
        } catch (Exception e) {
            log.error("ChatService closeStreamChat failed, userId: [{}], sessionId: [{}]", userId, sessionId, e);
            throw new RuntimeException("ChatService closeStreamChat failed", e);
        }
    }

    /**
     * Resubscribes to streaming chat for resuming sessions after disconnection.
     *
     * @param userId Unique identifier for the user.
     * @param sessionId Unique identifier for the session.
     * @param lastOffset The offset of the last received message, used to continue from the breakpoint.
     * @return A [Flux] object containing the chat response stream.
     * @throws IllegalArgumentException Thrown when input parameters are empty.
     * @throws RuntimeException Thrown when re-subscription fails.
     */
    public Flux<String> resubscribeStream(String userId, String sessionId, Long lastOffset) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.warn("ChatService resubscribeStream param is invalid, userId: [{}], sessionId: [{}], lastOffset: [{}]", userId, sessionId, lastOffset);
            throw new IllegalArgumentException("ChatService resubscribeStream param is invalid");
        }
        try {
            Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
            rocketMQService.reSubLiteTopic(userId, sessionId, sink, lastOffset);
            log.debug("ChatService resubscribeStream success, userId: [{}], sessionId: [{}]", userId, sessionId);
            return Flux.from(sink.asFlux());
        } catch (Exception e) {
            log.error("ChatService resubscribeStream failed, userId: [{}], sessionId: [{}]", userId, sessionId, e);
            throw new RuntimeException("ChatService resubscribeStream failed", e);
        }
    }
}
