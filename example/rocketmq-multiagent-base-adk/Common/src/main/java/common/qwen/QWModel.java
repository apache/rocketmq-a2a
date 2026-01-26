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
package common.qwen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import autovalue.shaded.com.google.common.collect.ImmutableList;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.utils.CollectionUtils;
import com.google.genai.types.Blob;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of {@link BaseLlm} for the Qwen large language model.
 * <p>
 * This class provides non-streaming text generation capabilities by integrating with
 * the Qwen API. It supports system instructions, multi-turn conversations, and
 * converts responses to a standardized {@link LlmResponse} format.
 */
public class QWModel extends BaseLlm {
    private static final Logger log = LoggerFactory.getLogger(QWModel.class);
    /**
     * The default Qwen model name used for inference.
     */
    private final static String MODEL_NAME = "qwen-plus";

    /**
     * The role identifier used for model-generated content in the response.
     */
    private final static String MODEL_ROLE = "model";

    /**
     * The API key used to authenticate requests to the Qwen service.
     */
    private final String apiKey;

    /**
     * Constructs a new Qwen model instance with the provided API key.
     *
     * @param apiKey the API key for accessing the Qwen service
     */
    public QWModel(String apiKey) {
        super(MODEL_NAME);
        if (StringUtils.isEmpty(apiKey)) {
            throw new IllegalArgumentException("API key must not be null");
        }
        this.apiKey = apiKey;
    }

    /**
     * Invokes the Qwen model with a system message and a list of user messages.
     *
     * @param systemMsg   the system instruction message
     * @param userMsgList the list of user messages
     * @return the generation result from the model, or {@code null} if input is invalid
     * @throws ApiException           if the Qwen API returns an error
     * @throws NoApiKeyException      if no API key is configured (not thrown in this implementation since key is required at construction)
     * @throws InputRequiredException if required input is missing
     */
    public GenerationResult callWithMessage(Message systemMsg, List<Message> userMsgList) throws ApiException, NoApiKeyException, InputRequiredException {
        if (null == systemMsg || CollectionUtils.isNullOrEmpty(userMsgList)) {
            log.warn("Invalid input: systemMsg or userMsgList is null/empty");
            return null;
        }
        Generation gen = new Generation();
        List<Message> messages = new ArrayList<>();
        messages.add(systemMsg);
        messages.addAll(userMsgList);
        GenerationParam param = GenerationParam.builder()
            .apiKey(this.apiKey)
            .model(MODEL_NAME)
            .messages(messages)
            .enableThinking(false)
            .resultFormat(GenerationParam.ResultFormat.MESSAGE)
            .build();
        return gen.call(param);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        if (null == llmRequest) {
            log.error("QWModel generateContent llmRequest is null");
            throw new IllegalArgumentException("QWModel generateContent llmRequest is null");
        }
        try {
            List<Message> userMsgList = new ArrayList<>();
            // Extract system instruction
            Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(extractSystemInstruction(llmRequest))
                .build();
            // Build user messages
            for (Content content : llmRequest.contents()) {
                Message message = Message.builder()
                    .role(Role.USER.getValue())
                    .content(content.text())
                    .build();
                if (message != null) {
                    userMsgList.add(message);
                }
            }
            // Call the model
            GenerationResult generationResult = callWithMessage(systemMsg, userMsgList);
            if (null == generationResult) {
                log.error("QWModel generationResult is null");
                return Flowable.error(new RuntimeException("QWModel callWithMessage is error"));
            }
            LlmResponse llmResponse = convertToLlmResponse(generationResult);
            if (llmResponse == null) {
                log.error("QWModel llmResponse is null");
                return Flowable.error(new RuntimeException("QWModel convertToLlmResponse error"));
            }
            return Flowable.just(llmResponse);
        } catch (Exception e) {
            log.error("QWen generateContent error", e);
            return Flowable.error(e);
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        if (null == llmRequest) {
            log.error("QWModel connect param error, llmRequest is null");
            throw new IllegalArgumentException("QWModel connect param error, llmRequest is null");
        }
        return new BaseLlmConnection() {
            private boolean connected = true;
            private final List<Content> conversationHistory = new CopyOnWriteArrayList<>();
            @Override
            public Completable sendHistory(List<Content> history) {
                return Completable.fromAction(() -> {
                    if (!connected) {
                        throw new IllegalStateException("QWModel connection is closed");
                    }
                    conversationHistory.clear();
                    conversationHistory.addAll(history);
                    log.debug("QWModel connect history updated with [{}] messages", history.size());
                });
            }
            @Override
            public Completable sendContent(Content content) {
                return Completable.fromAction(() -> {
                    if (!connected) {
                        throw new IllegalStateException("QWModel connection is closed");
                    }
                    conversationHistory.add(content);
                    log.debug("QWModel sendContent content: [{}]", content);
                });
            }
            @Override
            public Completable sendRealtime(Blob blob) {
                return Completable.fromAction(() -> {
                    log.warn("QWModel does not support real-time audio/video input; ignoring blob");
                });
            }
            @Override
            public Flowable<LlmResponse> receive() {
                return Flowable.defer(() -> {
                    if (!connected) {
                        return Flowable.error(new IllegalStateException("QWModel connection is closed"));
                    }
                    if (conversationHistory.isEmpty()) {
                        log.warn("QWModel receive called with empty conversation history");
                        return Flowable.empty();
                    }
                    LlmRequest request = LlmRequest.builder().contents(new ArrayList<>(conversationHistory)).build();
                    return QWModel.this.generateContent(request, false);
                });
            }
            @Override
            public void close() {
                this.connected = false;
                this.conversationHistory.clear();
                log.debug("QWModel connection closed gracefully");
            }
            @Override
            public void close(Throwable throwable) {
                connected = false;
                conversationHistory.clear();
                log.error("QWModel connection closed due to error", throwable);
            }
        };
    }

    /**
     * Extracts the system instruction from the LLM request configuration.
     *
     * @param llmRequest the incoming LLM request
     * @return the concatenated system instruction text, or an empty string if none is provided
     */
    private String extractSystemInstruction(LlmRequest llmRequest) {
        Optional<GenerateContentConfig> configOpt = llmRequest.config();
        if (configOpt.isPresent()) {
            Optional<Content> systemInstructionOpt = configOpt.get().systemInstruction();
            if (systemInstructionOpt.isPresent()) {
                return systemInstructionOpt.get().parts().orElse(ImmutableList.of()).stream()
                    .filter(p -> p.text().isPresent())
                    .map(p -> p.text().get())
                    .collect(Collectors.joining("\n"));
            }
        }
        return "";
    }

    /**
     * Converts a Qwen {@link GenerationResult} into a standardized {@link LlmResponse}.
     *
     * @param chatResponse the raw response from the Qwen API
     * @return a normalized LLM response object
     */
    private LlmResponse convertToLlmResponse(GenerationResult chatResponse) {
        LlmResponse.Builder responseBuilder = LlmResponse.builder();
        Part part = null;
        try {
            String content = chatResponse.getOutput().getChoices().get(0).getMessage().getContent();
            if (!StringUtils.isEmpty(content)) {
                part = Part.builder().text(content).build();
            } else {
                part = Part.builder().text("Sorry, no valid response content was received").build();
            }
        } catch (Exception e) {
            log.error("convertToLlmResponse error", e);
            part = Part.builder().text("Sorry, an error occurred while processing the response, error: " + e.getMessage()).build();
        }
       return responseBuilder.content(Content.builder().role(MODEL_ROLE).parts(ImmutableList.of(part)).build()).build();
    }

}
