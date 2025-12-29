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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

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
                    task = new_task(context.getMessage());
                    eventQueue.enqueueEvent(task);
                }
                TaskUpdater taskUpdater = new TaskUpdater(context, eventQueue);
                try {
                    String response = appCall(userMessage);
                    taskUpdater.addArtifact(List.of(new TextPart(response)));
                    taskUpdater.complete();
                } catch (Exception e) {
                    taskUpdater.startWork(taskUpdater.newAgentMessage(List.of(new TextPart("Error processing streaming output: " + e.getMessage())), Map.of()));
                    taskUpdater.fail();
                }
            }

            @Override
            public void cancel(RequestContext context, EventQueue eventQueue) throws JSONRPCError {
                Task task = context.getTask();
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
            // 若没有配置环境变量，可用百炼API Key将下行替换为：.apiKey("sk-xxx")。但不建议在生产环境中直接将API Key硬编码到代码中，以减少API Key泄露风险。
            .apiKey(ApiKey)
            .appId(AppId)
            .prompt(prompt)
            .build();
        Application application = new Application();
        ApplicationResult result = application.call(param);
        return result.getOutput().getText();
    }

    private Task new_task(Message request) {
        String context_id_str = request.getContextId();
        if (context_id_str == null || context_id_str.isEmpty()) {
            context_id_str = UUID.randomUUID().toString();
        }
        String id = UUID.randomUUID().toString();
        if (request.getTaskId() != null && !request.getTaskId().isEmpty()) {
            id = request.getTaskId();
        }
        return new Task(id, context_id_str, new TaskStatus(TaskState.SUBMITTED), null, List.of(request), null);
    }

}
