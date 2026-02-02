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
import com.alibaba.dashscope.app.ApplicationResult;
import common.util.LLMUtil;
import io.a2a.server.agentexecution.AgentExecutor;
import io.a2a.server.agentexecution.RequestContext;
import io.a2a.server.events.EventQueue;
import io.a2a.server.tasks.TaskUpdater;
import io.a2a.spec.JSONRPCError;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskNotCancelableError;
import io.a2a.spec.TaskState;
import io.a2a.spec.TextPart;
import io.reactivex.Flowable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Producer for the {@link AgentExecutor} that handles incoming agent execution requests.
 * <p>
 * This executor:
 * - Extracts user input from the request message.
 * - Manages task lifecycle (create, update, stream, complete, cancel).
 * - Streams responses from the external application (e.g., LLM) via {@link #appCallStream(String)}.
 * - Updates the task in real-time using {@link TaskUpdater}.
 *
 * <p>
 * Built as an anonymous inner class to encapsulate stateless execution logic.
 * Requires system properties 'apiKey' and 'appId' to be set at startup.
 *
 */
@ApplicationScoped
public class AgentExecutorProducer {
    private static final Logger log = LoggerFactory.getLogger(AgentExecutorProducer.class);
    private static final String API_KEY = System.getProperty("apiKey");
    private static final String APP_ID = System.getProperty("appId");

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
             * @param context the execution context containing message and task info.
             * @param eventQueue the queue used to emit task events (e.g., updates, completion).
             * @throws JSONRPCError if an invalid request or state is encountered.
             */
            @Override
            public void execute(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                String userMessage = LLMUtil.extractTextFromMessage(context.getMessage());
                log.info("received user message for execution. userMessage: [{}]", userMessage);
                Task task = context.getTask();
                if (task == null) {
                    task = LLMUtil.createTask(context.getMessage());
                    eventQueue.enqueueEvent(task);
                }
                TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
                try {
                    Flowable<ApplicationResult> applicationResultFlowable = LLMUtil.appCallStream(userMessage, API_KEY, APP_ID);
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
                    log.error("error processing streaming output", e);
                    taskUpdater.startWork(taskUpdater.newAgentMessage(List.of(new TextPart("error processing streaming output: " + e.getMessage())), Map.of()));
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
                    log.warn("can't cancel task, already in terminal state. taskId: [{}], state: [{}]", task.getId(), state);
                    throw new TaskNotCancelableError();
                }
                // cancel the task
                TaskUpdater updater = new TaskUpdater(context, eventQueue);
                updater.cancel();
                log.info("task canceled by user. taskId: [{}]", task.getId());
            }
        };
    }
}
