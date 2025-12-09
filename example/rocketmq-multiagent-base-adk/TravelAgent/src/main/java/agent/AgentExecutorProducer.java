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
package agent;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.alibaba.dashscope.app.Application;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import io.reactivex.Flowable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.commons.lang3.StringUtils;

@ApplicationScoped
public class AgentExecutorProducer {
    private static final String ApiKey = System.getProperty("apiKey");
    private static final String AppId = System.getProperty("appId");

    @Produces
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {
            @Override
            public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                String userMessage = extractTextFromMessage(context.getMessage());
                System.out.println("receive userMessage: " + userMessage);
                Task task = context.getTask();
                if (task == null) {
                    task = createTask(context.getMessage());
                    eventQueue.enqueueEvent(task);
                }
                TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
                try {
                    Flowable<ApplicationResult> applicationResultFlowable = appCallStream(userMessage);
                    String lastOutput = "";
                    for (ApplicationResult msg : applicationResultFlowable.blockingIterable()) {
                        String currentText = msg.getOutput().getText();
                        if (currentText.length() > lastOutput.length()) {
                            List<Part<?>> parts = List.of(new TextPart(currentText.substring(lastOutput.length()), null));
                            taskUpdater.addArtifact(parts);
                        }
                        lastOutput = currentText;
                    }
                    taskUpdater.complete();
                } catch (Exception e) {
                    taskUpdater.startWork(taskUpdater.newAgentMessage(List.of(new TextPart("Error processing streaming output: " + e.getMessage())), Map.of()));
                    taskUpdater.fail();
                }
            }

            @Override
            public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                Task task = context.getTask();
                if (null == task || null == task.getStatus()) {
                    return;
                }
                if (task.getStatus().state() == TaskState.CANCELED) {
                    throw new TaskNotCancelableError();
                }
                if (task.getStatus().state() == TaskState.COMPLETED) {
                    throw new TaskNotCancelableError();
                }
                // cancel the task
                TaskUpdater updater = new TaskUpdater(context, eventQueue);
                updater.cancel();
            }
        };
    }

    private String extractTextFromMessage(Message message) {
        if (null == message) {
            return "";
        }
        StringBuilder textBuilder = new StringBuilder();
        if (message.getParts() != null) {
            for (Part part : message.getParts()) {
                if (part instanceof TextPart textPart) {
                    textBuilder.append(textPart.getText());
                }
            }
        }
        return textBuilder.toString();
    }

    public static String appCall(String prompt) throws ApiException, NoApiKeyException, InputRequiredException {
        ApplicationParam param = ApplicationParam.builder()
            .apiKey(ApiKey)
            .appId(AppId)
            .prompt(prompt)
            .build();
        Application application = new Application();
        ApplicationResult result = application.call(param);
        return result.getOutput().getText();
    }

    public static Flowable<ApplicationResult> appCallStream(String prompt) throws ApiException, NoApiKeyException, InputRequiredException {
        ApplicationParam param = ApplicationParam.builder()
            .apiKey(ApiKey)
            .appId(AppId)
            .prompt(prompt)
            .build();
        Application application = new Application();
        Flowable<ApplicationResult> applicationResultFlowable = application.streamCall(param);
        return applicationResultFlowable;
    }

    private Task createTask(io.a2a.spec.Message request) {
        String id = !StringUtils.isEmpty(request.getTaskId()) ? request.getTaskId() : UUID.randomUUID().toString();
        String contextId = !StringUtils.isEmpty(request.getContextId()) ? request.getContextId() : UUID.randomUUID().toString();
        return new Task(id, contextId, new TaskStatus(TaskState.SUBMITTED), null, List.of(request), null);
    }
}
