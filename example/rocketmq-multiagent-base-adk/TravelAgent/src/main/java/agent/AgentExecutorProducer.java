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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Producer for the {@link AgentExecutor} that handles incoming agent execution requests.
 * <p>
 * This executor:
 * - Extracts user input from the request message
 * - Manages task lifecycle (create, update, stream, complete, cancel)
 * - Streams responses from the external application (e.g., LLM) via {@link #appCallStream(String)}
 * - Updates the task in real-time using {@link TaskUpdater}
 * </p>
 * <p>
 * Built as an anonymous inner class to encapsulate stateless execution logic.
 * Requires system properties 'apiKey' and 'appId' to be set at startup.
 * </p>
 */
@ApplicationScoped
public class AgentExecutorProducer {
    private static final Logger log = LoggerFactory.getLogger(AgentExecutorProducer.class);
    private static final String ApiKey = System.getProperty("apiKey");
    private static final String AppId = System.getProperty("appId");

    /**
     * Produces a custom {@link AgentExecutor} implementation that integrates with an external streaming API.
     *
     * @return a configured agent executor
     */
    @Produces
    public AgentExecutor agentExecutor() {
        return new AgentExecutor() {
            /**
             * Executes a new task based on the incoming request context.
             *
             * @param context     the execution context containing message and task info
             * @param eventQueue  the queue used to emit task events (e.g., updates, completion)
             * @throws JSONRPCError if an invalid request or state is encountered
             */
            @Override
            public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                String userMessage = extractTextFromMessage(context.getMessage());
                log.info("Received user message for execution. userMessage: [{}]", userMessage);
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
                    log.error("Error processing streaming output", e);
                    taskUpdater.startWork(taskUpdater.newAgentMessage(List.of(new TextPart("Error processing streaming output: " + e.getMessage())), Map.of()));
                    taskUpdater.fail();
                }
            }

            /**
             * Handles cancellation of an ongoing task.
             * <p>
             * A task can only be canceled if it is currently running or submitted.
             * Completed or already canceled tasks cannot be canceled again.
             *
             * @param context the request context.
             * @param eventQueue the event queue for emitting cancellation events.
             * @throws JSONRPCError if the task is not in a cancelable state.
             */
            @Override
            public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                Task task = context.getTask();
                if (null == task || null == task.getStatus()) {
                    return;
                }
                TaskState state = task.getStatus().state();
                if (state == TaskState.CANCELED || state == TaskState.COMPLETED) {
                    log.warn("Cannot cancel task, already in terminal state. taskId: [{}], state: [{}]", task.getId(), state);
                    throw new TaskNotCancelableError();
                }
                // cancel the task
                TaskUpdater updater = new TaskUpdater(context, eventQueue);
                updater.cancel();
                log.info("Task canceled by user. taskId: [{}]", task.getId());
            }
        };
    }

    /**
     * Extracts plain text content from a message by concatenating all {@link TextPart} instances.
     *
     * @param message the input message.
     * @return concatenated text, or empty string if null or no text parts.
     */
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

    /**
     * Initiates a streaming call to the external application (e.g., LLM backend).
     * <p>
     * Uses the configured API key and app ID to authenticate and send the prompt.
     * Returns a {@link Flowable} that emits incremental results as they arrive.
     *
     * @param prompt the user's input prompt.
     * @return a reactive stream of application results.
     * @throws NoApiKeyException if authentication fails.
     * @throws InputRequiredException if prompt is missing.
     */
    private static Flowable<ApplicationResult> appCallStream(String prompt) throws NoApiKeyException, InputRequiredException {
        ApplicationParam param = ApplicationParam.builder()
            .apiKey(ApiKey)
            .appId(AppId)
            .prompt(prompt)
            .build();
        Application application = new Application();
        return application.streamCall(param);
    }

    /**
     * Creates a new task with a generated ID and initial submitted status.
     *
     * @param request the incoming message containing optional task/context IDs.
     * @return a newly created task.
     */
    private Task createTask(io.a2a.spec.Message request) {
        String id = !StringUtils.isEmpty(request.getTaskId()) ? request.getTaskId() : UUID.randomUUID().toString();
        String contextId = !StringUtils.isEmpty(request.getContextId()) ? request.getContextId() : UUID.randomUUID().toString();
        return new Task(id, contextId, new TaskStatus(TaskState.SUBMITTED), null, List.of(request), null);
    }
}
