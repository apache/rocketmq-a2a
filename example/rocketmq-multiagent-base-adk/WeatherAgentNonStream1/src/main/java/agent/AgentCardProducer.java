package agent;

import java.util.Collections;
import java.util.List;
import io.a2a.server.PublicAgentCard;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentSkill;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.apache.commons.lang3.StringUtils;

import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.ROCKETMQ_PROTOCOL;

@ApplicationScoped
public class AgentCardProducer {
    private static final String ROCKETMQ_ENDPOINT = System.getProperty("rocketMQEndpoint", "");
    private static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace", "");
    private static final String BIZ_TOPIC = System.getProperty("bizTopic", "");

    @Produces
    @PublicAgentCard
    public AgentCard agentCard() {

        return new AgentCard.Builder()
            .name("天气查询助手Agent")
            .description("对未来一段时间内的天气情况进行查询")
            .url(buildRocketMQUrl())
            .version("1.0.0")
            .documentationUrl("http://example.com/docs")
            .capabilities(new AgentCapabilities.Builder()
                .streaming(false)
                .pushNotifications(true)
                .stateTransitionHistory(true)
                .build())
            .defaultInputModes(Collections.singletonList("text"))
            .defaultOutputModes(Collections.singletonList("text"))
            .skills(Collections.singletonList(new AgentSkill.Builder()
                .id("天气查询助手Agent")
                .name("天气查询助手Agent")
                .description("对未来一段时间内的天气情况进行查询")
                .tags(Collections.singletonList("天气查询"))
                .examples(List.of("下周的天气怎么样"))
                .build()))
            .preferredTransport(ROCKETMQ_PROTOCOL)
            .protocolVersion("0.3.0")
            .build();
    }

    private static String buildRocketMQUrl() {
        if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT) || StringUtils.isEmpty(BIZ_TOPIC)) {
            throw new RuntimeException("buildRocketMQUrl param error, please check rocketmq config");
        }
        return "http://" + ROCKETMQ_ENDPOINT + "/" + ROCKETMQ_NAMESPACE + "/" + BIZ_TOPIC;
    }

}
