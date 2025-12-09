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

@RestController
@RequestMapping("/")
public class A2AChatController {
    private static final Logger log = LoggerFactory.getLogger(A2AChatController.class);

    @Autowired
    AgentService agentService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(@RequestParam() String question, @RequestParam() String userId, @RequestParam() String sessionId) {
        Flux<String> fluxResult = null;
        try {
            if (StringUtils.isEmpty(question) || StringUtils.isEmpty(userId) || StringUtils.isEmpty(sessionId)) {
                log.error("streamChat param error, question: {}, userId: {}, sessionId: {}", question, userId, sessionId);
                return null;
            }
            log.info("streamChat question: {}, userId: {}, sessionId: {}", question, userId, sessionId);
            fluxResult = agentService.streamChat(userId, sessionId, question);
        } catch (Exception e) {
            log.error("streamChat error, question: {}, userId: {}, sessionId: {}, error: {}", question, userId, sessionId, e.getMessage());
        }
        return fluxResult;
    }

    @GetMapping("/closeStream")
    public ResponseEntity<String> closeStreamChat(@RequestParam String userId, @RequestParam String sessionId) {
        log.info("closeStreamChat userId: {}, sessionId: {}", userId, sessionId);
        agentService.closeStreamChat(userId, sessionId);
        return ResponseEntity.ok("Stream closed successfully");
    }

    @GetMapping(value = "/resubscribeStream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> resubscribeStreamChat(@RequestParam String userId, @RequestParam String sessionId) {
        Flux<String> fluxResult = null;
        try {
            if (StringUtils.isEmpty(sessionId) || StringUtils.isEmpty(userId)) {
                log.error("resubscribeStreamChat param error, userId: {}, sessionId: {}", userId, sessionId);
                return null;
            }
            log.info("resubscribeStreamChat userId: {}, sessionId: {}", userId, sessionId);
            fluxResult = agentService.resubscribeStream(userId, sessionId);
        } catch (Exception e) {
           log.error("resubscribeStreamChat error, userId: {}, sessionId: {}", userId, sessionId);
        }
       return fluxResult;
    }

}
