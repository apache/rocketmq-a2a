package common.util;

import java.util.List;
import java.util.UUID;
import com.alibaba.dashscope.app.Application;
import com.alibaba.dashscope.app.ApplicationParam;
import com.alibaba.dashscope.app.ApplicationResult;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import io.a2a.spec.Message;
import io.a2a.spec.Part;
import io.a2a.spec.Task;
import io.a2a.spec.TaskState;
import io.a2a.spec.TaskStatus;
import io.a2a.spec.TextPart;
import io.reactivex.Flowable;
import org.apache.commons.lang3.StringUtils;

/**
 * Utility class for managing core interactions between agents in an A2A (Agent-to-Agent) system.
 * <p>
 * This class provides static methods to:
 * <ul>
 *   <li>Extract text content from incoming messages</li>
 *   <li>Create tasks with unique identifiers and initial state</li>
 *   <li>Initiate streaming calls to LLM-powered applications</li>
 * </ul>
 * It serves as a central helper for initiating and structuring agent conversations.
 */
public class AgentInteraction {

    /**
     * Extracts plain text content from a message by concatenating all {@link TextPart} instances.
     *
     * @param message the input message.
     * @return concatenated text, or empty string if null or no text parts.
     */
    public static String extractTextFromMessage(Message message) {
        if (null == message) {
            return "";
        }
        StringBuilder textBuilder = new StringBuilder();
        if (message.getParts() != null) {
            for (Part part : message.getParts()) {
                if (part instanceof TextPart) {
                    textBuilder.append(((TextPart)part).getText());
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
    public static Flowable<ApplicationResult> appCallStream(String prompt, String apiKey, String appId) throws NoApiKeyException, InputRequiredException {
        ApplicationParam param = ApplicationParam.builder()
            .apiKey(apiKey)
            .appId(appId)
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
    public static Task createTask(io.a2a.spec.Message request) {
        String id = StringUtils.isNotEmpty(request.getTaskId()) ? request.getTaskId() : UUID.randomUUID().toString();
        String contextId = StringUtils.isNotEmpty(request.getContextId()) ? request.getContextId() : UUID.randomUUID().toString();
        return new Task(id, contextId, new TaskStatus(TaskState.SUBMITTED), null, List.of(request), null);
    }
}
