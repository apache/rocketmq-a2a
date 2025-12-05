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
package common;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QWModel extends BaseLlm {
    private static final Logger log = LoggerFactory.getLogger(QWModel.class);
    private final static String MODEL_NAME = "qwen-plus";
    private final static String MODEL_ROLE = "model";
    private String API_KEY = null;

    public QWModel(String apiKey) {
        super(MODEL_NAME);
        API_KEY = apiKey;
    }

    public GenerationResult callWithMessage(Message systemMsg, List<Message> userMsgList) throws ApiException, NoApiKeyException, InputRequiredException {
        if (null == systemMsg || CollectionUtils.isNullOrEmpty(userMsgList)) {
            return null;
        }
        Generation gen = new Generation();
        List<Message> messages = new ArrayList<>();
        messages.add(systemMsg);
        messages.addAll(userMsgList);
        GenerationParam param = GenerationParam.builder()
            .apiKey(API_KEY)
            .model(MODEL_NAME)
            .messages(messages)
            .resultFormat(GenerationParam.ResultFormat.MESSAGE)
            .build();
        return gen.call(param);
    }

    @Override
    public Flowable<LlmResponse> generateContent(LlmRequest llmRequest, boolean stream) {
        try {
            List<Message> userMsgList = new ArrayList<>();
            String systemText = extractSystemInstruction(llmRequest);
            Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content(systemText)
                .build();
            for (Content content : llmRequest.contents()) {
                Message message = Message.builder()
                    .role(Role.USER.getValue())
                    .content(content.text())
                    .build();
                if (message != null) {
                    userMsgList.add(message);
                }
            }
            GenerationResult generationResult = callWithMessage(systemMsg, userMsgList);
            if (null == generationResult) {
                log.error("generationResult is null");
                return Flowable.error(new RuntimeException("callWithMessage is error"));
            }
            LlmResponse llmResponse = convertToLlmResponse(generationResult);
            if (llmResponse == null) {
                log.error("llmResponse is null");
                return Flowable.error(new RuntimeException("convertToLlmResponse is error"));
            }
            return Flowable.just(llmResponse);
        } catch (Exception e) {
            log.error("Error in QWen generateContent: {}", e.getMessage());
            return Flowable.error(e);
        }
    }

    @Override
    public BaseLlmConnection connect(LlmRequest llmRequest) {
        return new BaseLlmConnection() {
            private boolean connected = true;
            private final List<Content> conversationHistory = new ArrayList<>();
            @Override
            public Completable sendHistory(List<Content> history) {
                return Completable.fromAction(() -> {
                    log.debug("QWen sendHistory count: {}", history.size());
                    conversationHistory.clear();
                    conversationHistory.addAll(history);
                });
            }
            @Override
            public Completable sendContent(Content content) {
                return Completable.fromAction(() -> {
                    log.debug("QWen sendContent content: {}", content);
                    conversationHistory.add(content);
                });
            }
            @Override
            public Completable sendRealtime(Blob blob) {
                return Completable.fromAction(() -> {
                    log.warn("QWen not support sendRealtime blob, ignore it");
                });
            }
            @Override
            public Flowable<LlmResponse> receive() {
                return Flowable.defer(() -> {
                    if (!connected) {
                        return Flowable.error(new IllegalStateException("connected is closed"));
                    }
                    if (conversationHistory.isEmpty()) {
                        log.warn("conversationHistory is empty");
                        return Flowable.empty();
                    }
                    LlmRequest request = LlmRequest.builder()
                        .contents(new ArrayList<>(conversationHistory))
                        .build();
                    return QWModel.this.generateContent(request, false);
                });
            }
            @Override
            public void close() {
                connected = false;
                conversationHistory.clear();
                log.debug("QWen connection is closed");
            }
            @Override
            public void close(Throwable throwable) {
                connected = false;
                conversationHistory.clear();
                log.error("QWen connection closed due to error : {}", throwable.getMessage(), throwable);
            }
        };
    }

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

    private LlmResponse convertToLlmResponse(GenerationResult chatResponse) {
        LlmResponse.Builder responseBuilder = LlmResponse.builder();
        try {
            String content = chatResponse.getOutput().getChoices().get(0).getMessage().getContent();
            if (content != null && !content.trim().isEmpty()) {
                Part part = Part.builder()
                    .text(content)
                    .build();
                Content responseContent = Content.builder()
                    .role(MODEL_ROLE)
                    .parts(ImmutableList.of(part))
                    .build();
                responseBuilder.content(responseContent);
            } else {
                Part errorPart = Part.builder()
                    .text("抱歉，没有收到有效的响应内容。")
                    .build();
                Content errorContent = Content.builder()
                    .role(MODEL_ROLE)
                    .parts(ImmutableList.of(errorPart))
                    .build();
                responseBuilder.content(errorContent);
            }
        } catch (Exception e) {
            Part errorPart = Part.builder()
                .text("抱歉，处理响应时出现错误：" + e.getMessage())
                .build();
            Content errorContent = Content.builder()
                .role(MODEL_ROLE)
                .parts(ImmutableList.of(errorPart))
                .build();
            responseBuilder.content(errorContent);
        }
        return responseBuilder.build();
    }

}
