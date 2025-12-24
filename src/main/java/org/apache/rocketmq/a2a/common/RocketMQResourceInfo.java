package org.apache.rocketmq.a2a.common;

import java.util.List;

import com.alibaba.fastjson.JSON;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.HTTPS_URL_PREFIX;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.HTTP_URL_PREFIX;

public class RocketMQResourceInfo {
    private static final Logger log = LoggerFactory.getLogger(RocketMQResourceInfo.class);

    private String endpoint;
    private String topic;
    private String namespace;

    public RocketMQResourceInfo(String endpoint, String topic) {
        this.endpoint = endpoint;
        this.topic = topic;
    }

    public RocketMQResourceInfo() {}

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public static RocketMQResourceInfo parseAgentCardAddition(AgentCard agentCard) {
        if (null == agentCard || StringUtils.isEmpty(agentCard.preferredTransport()) || StringUtils.isEmpty(agentCard.url()) || null == agentCard.additionalInterfaces() || agentCard.additionalInterfaces().isEmpty()) {
            log.error("parseAgentCardAddition param error, agentCard: {}", JSON.toJSONString(agentCard));
            return null;
        }
        RocketMQResourceInfo rocketMQResourceInfo = null;
        String preferredTransport = agentCard.preferredTransport();
        if (RocketMQA2AConstant.ROCKETMQ_PROTOCOL.equals(preferredTransport)) {
            String url = agentCard.url();
            rocketMQResourceInfo = pareAgentCardUrl(url);
            if (null != rocketMQResourceInfo && !StringUtils.isEmpty(rocketMQResourceInfo.getEndpoint()) && !StringUtils.isEmpty(rocketMQResourceInfo.getTopic())) {
                log.info("RocketMQTransport get rocketMQResourceInfo from preferredTransport");
                return rocketMQResourceInfo;
            }
        }
        List<AgentInterface> agentInterfaces = agentCard.additionalInterfaces();
        for (AgentInterface agentInterface : agentInterfaces) {
            String transport = agentInterface.transport();
            if (!StringUtils.isEmpty(transport) && RocketMQA2AConstant.ROCKETMQ_PROTOCOL.equals(transport)) {
                String url = agentInterface.url();
                rocketMQResourceInfo = pareAgentCardUrl(url);
                if (null != rocketMQResourceInfo && !StringUtils.isEmpty(rocketMQResourceInfo.getEndpoint()) && !StringUtils.isEmpty(rocketMQResourceInfo.getTopic())) {
                    log.error("RocketMQTransport get rocketMQResourceInfo from additionalInterfaces");
                    return rocketMQResourceInfo;
                }
            }
        }
        return null;
    }

    public static RocketMQResourceInfo pareAgentCardUrl(String agentCardUrl) {
        if (StringUtils.isEmpty(agentCardUrl)) {
            return null;
        }
        String agentUrl = agentCardUrl.replace(HTTP_URL_PREFIX, "");
        String replaceFinal = agentUrl.replace(HTTPS_URL_PREFIX, "");
        String[] split = replaceFinal.split("/");
        if (split.length != 3) {
            return null;
        }
        RocketMQResourceInfo rocketMQResourceInfo = new RocketMQResourceInfo();
        rocketMQResourceInfo.setEndpoint(split[0].trim());
        rocketMQResourceInfo.setNamespace(split[1].trim());
        rocketMQResourceInfo.setTopic(split[2].trim());
        return rocketMQResourceInfo;
    }
}
