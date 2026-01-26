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
package org.example.controller;

import org.apache.commons.lang3.StringUtils;
import org.example.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * REST controller for agent-to-agent (A2A) streaming chat interactions.
 * <p>
 * Provides endpoints for initiating streaming responses, resubscribing to ongoing streams,
 * and gracefully closing active streams.
 */
@RestController
@RequestMapping("/")
public class A2AChatController {
    private static final Logger log = LoggerFactory.getLogger(A2AChatController.class);

    @Autowired
    AgentService agentService;

    /**
     * Initiates a streaming chat session with the agent system.
     * <p>
     * Returns a Server-Sent Events (SSE) stream of response chunks.
     *
     * @param question the user's input question.
     * @param userId the unique identifier of the user.
     * @param sessionId the unique session identifier.
     * @return a {@code Flux<String>} emitting response chunks via SSE.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam String question, @RequestParam String userId, @RequestParam String sessionId) {
        if (StringUtils.isEmpty(question) || StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.error("streamChat param error, question: [{}], userId: [{}], sessionId: [{}]", question, userId, sessionId);
            return Flux.error(new IllegalArgumentException("streamChat param error"));
        }
        log.debug("starting stream chat, userId: [{}], sessionId: [{}], question: [{}]", userId, sessionId, question);
        try {
            return agentService.startStreamChat(userId, sessionId, question);
        } catch (Exception e) {
            log.error("streamChat error during stream chat, userId: [{}], sessionId: [{}], question: [{}]", userId, sessionId, question, e);
            return Flux.error(e);
        }
    }

    /**
     * Gracefully closes an active streaming chat session.
     * <p>
     * This endpoint signals the backend to release resources associated with the given session.
     *
     * @param userId the user identifier.
     * @param sessionId the session identifier.
     * @return a message about this operation.
     */
    @GetMapping("/closeStream")
    public ResponseEntity<String> closeStreamChat(@RequestParam String userId, @RequestParam String sessionId) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.error("closeStreamChat param error, userId: [{}], sessionId: [{}]", userId, sessionId);
            return ResponseEntity.status(400).body("closeStreamChat param is error");
        }
        try {
            agentService.endStreamChat(userId, sessionId);
            log.debug("closeStreamChat success, userId: [{}], sessionId: [{}]", userId, sessionId);
            return ResponseEntity.ok("stream closed successfully");
        } catch (Exception e) {
            log.error("failed to close stream, userId: [{}], sessionId: [{}]", userId, sessionId, e);
            return ResponseEntity.status(500).body("failed to close stream");
        }
    }

    /**
     * Resubscribes to an existing streaming chat session.
     * <p>
     * This endpoint allows a client to reconnect (e.g., after a network drop) and resume
     * receiving pending or new response messages from the agent system via Server-Sent Events (SSE).
     *
     * @param userId the user identifier.
     * @param sessionId the session identifier.
     * @return a {@code Flux<String>} emitting response chunks via SSE.
     */
    @GetMapping(value = "/resubscribeStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> resubscribeStreamChat(@RequestParam String userId, @RequestParam String sessionId) {
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
            log.error("resubscribeStreamChat param error, userId: [{}], sessionId: [{}]", userId, sessionId);
            return Flux.error(new IllegalArgumentException("userId and sessionId must not be empty"));
        }
        try {
            Flux<String> flux = agentService.resubscribeStream(userId, sessionId);
            log.debug("resubscribeStreamChat success, userId: [{}], sessionId: [{}]", userId, sessionId);
            return flux;
        } catch (Exception e) {
            log.error("failed to resubscribe to stream, userId: [{}], sessionId: [{}]", userId, sessionId, e);
            return Flux.error(e);
        }
    }
}
