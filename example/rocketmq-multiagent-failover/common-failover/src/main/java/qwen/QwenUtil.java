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
package qwen;

import java.util.Collections;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;

import io.reactivex.Flowable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import util.RocketMQUtil;

/**
 * Utility class for interacting with Alibaba Cloud's Qwen large language model.
 * <p>
 * This class provides a streamlined interface for streaming LLM inference using the DashScope SDK.
 * It supports configurable model selection (e.g., qwen-turbo, qwen-plus) and validates required parameters
 * such as the API key before making calls.
 */
public class QwenUtil {
    private static final Logger log = LoggerFactory.getLogger(RocketMQUtil.class);

    /**
     * The API key used to authenticate requests to the DashScope service.
     */
    private static final String API_KEY = System.getProperty("apiKey");

    /**
     * The Qwen model to use for inference. Defaults to "qwen-turbo" for low latency and cost-efficiency.
     * Other options include "qwen-plus" (balanced) and "qwen-max" (high capability).
     */
    private static final String LLM_MODE = "qwen-turbo";

    /**
     * Initiates a streaming inference request to the Qwen large language model with the given user message.
     * <p>
     * This method constructs a single-turn conversation where the user provides the input message,
     * and the LLM generates a response in streaming mode (token by token). The result is returned as
     * a reactive {@link Flowable} of {@link GenerationResult}, suitable for real-time output rendering
     * (e.g., chat interfaces or Server-Sent Events).
     *
     * @param userMsg the user's input text; must not be null or empty.
     * @return a {@link Flowable} emitting generation results incrementally as tokens are produced.
     * @throws IllegalArgumentException if {@code userMsg} is null or empty.
     * @throws NoApiKeyException if the API key is not configured.
     * @throws InputRequiredException if required request fields are missing.
     */
    public static Flowable<GenerationResult> streamCallWithMessage(String userMsg) throws NoApiKeyException, InputRequiredException {
        if (StringUtils.isEmpty(userMsg)) {
            throw new IllegalArgumentException("streamCallWithMessage userMsg must not be null");
        }
        return new Generation().streamCall(buildGenerationParam(Message.builder().role(Role.USER.getValue()).content(userMsg).build()));
    }

    /**
     * Builds a {@link GenerationParam} object configured for streaming inference with Qwen.
     *
     * @param userMsg the user's input message.
     * @return a fully configured {@link GenerationParam} instance.
     */
    private static GenerationParam buildGenerationParam(Message userMsg) {
        return GenerationParam.builder()
            .apiKey(API_KEY)
            .model(LLM_MODE)
            .messages(Collections.singletonList(userMsg))
            .resultFormat(GenerationParam.ResultFormat.MESSAGE)
            .enableThinking(false)
            .incrementalOutput(true)
            .build();
    }

    /**
     * Validates whether the QWen configuration is complete and valid.
     * @return {@code true} if all required parameters are present; {@code false} otherwise.
     */
    public static boolean checkQwenConfigParam() {
        if (StringUtils.isEmpty(API_KEY)) {
            log.warn("QwenUtil checkQwenConfigParam param error, apikey is empty");
            return false;
        }
        return true;
    }
}
