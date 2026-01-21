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
package org.apache.rocketmq.a2a.common;
import java.util.List;
import com.alibaba.fastjson.JSON;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentInterface;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.HTTPS_URL_PREFIX;
import static org.apache.rocketmq.a2a.common.RocketMQA2AConstant.HTTP_URL_PREFIX;

/**
 * Encapsulates RocketMQ resource information, including endpoint, namespace, and topic.
 * Used for routing and connecting to specific RocketMQ clusters.
 */
public class RocketMQResourceInfo {
    private static final Logger log = LoggerFactory.getLogger(RocketMQResourceInfo.class);

    /**
     * The namespace of RocketMQ service.
     */
    private String namespace;

    /**
     * The network address of the RocketMQ service, used by clients to connect to a specific RocketMQ cluster.
     */
    private String endpoint;

    /**
     * RocketMQ topic resource.
     */
    private String topic;

    /**
     * Constructs a new RocketMQResourceInfo
     */
    public RocketMQResourceInfo() {}

    /**
     * Parses RocketMQ-related information from an AgentCard.
     *
     * <p>Tries to extract connection details in the following order:
     * <ol>
     *   <li>From the preferred transport if it's RocketMQ.</li>
     *   <li>From additional interfaces if any use RocketMQ transport.</li>
     * </ol>
     *
     * @param agentCard the AgentCard containing transport endpoints.
     * @return RocketMQResourceInfo, or {@code null} if parsing fails or no RocketMQ interface found
     */
    public static RocketMQResourceInfo parseAgentCardAddition(AgentCard agentCard) {
        if (null == agentCard || StringUtils.isEmpty(agentCard.preferredTransport()) || StringUtils.isEmpty(agentCard.url()) || CollectionUtils.isEmpty(agentCard.additionalInterfaces())) {
            log.error("RocketMQTransport parseAgentCardAddition param error, agentCard: {}", JSON.toJSONString(agentCard));
            return null;
        }
        RocketMQResourceInfo rocketMQResourceInfo = null;
        String preferredTransport = agentCard.preferredTransport();
        // If the preferredTransport is RocketMQ
        if (RocketMQA2AConstant.ROCKETMQ_PROTOCOL.equals(preferredTransport)) {
            rocketMQResourceInfo = pareAgentCardUrl(agentCard.url());
            if (null != rocketMQResourceInfo && !StringUtils.isEmpty(rocketMQResourceInfo.getEndpoint()) && !StringUtils.isEmpty(rocketMQResourceInfo.getTopic())) {
                log.info("RocketMQTransport get rocketMQResourceInfo from preferredTransport");
                return rocketMQResourceInfo;
            }
        }
        // If the preferredTransport is not RocketMQ, then try to get rocketmq info from additionalInterfaces
        List<AgentInterface> agentInterfaces = agentCard.additionalInterfaces();
        for (AgentInterface agentInterface : agentInterfaces) {
            String transport = agentInterface.transport();
            if (!StringUtils.isEmpty(transport) && RocketMQA2AConstant.ROCKETMQ_PROTOCOL.equals(transport)) {
                rocketMQResourceInfo = pareAgentCardUrl(agentInterface.url());
                if (null != rocketMQResourceInfo && !StringUtils.isEmpty(rocketMQResourceInfo.getEndpoint()) && !StringUtils.isEmpty(rocketMQResourceInfo.getTopic())) {
                    log.info("RocketMQTransport get rocketMQResourceInfo from additionalInterfaces");
                    return rocketMQResourceInfo;
                }
            }
        }
        return null;
    }

    /**
     * Parses RocketMQ resource info from a URL in format:
     * {@code http://endpoint/namespace/topic} or {@code https://endpoint/namespace/topic}
     *
     * @param agentCardUrl the full URL string
     * @return a new RocketMQResourceInfo instance, or {@code null} if parsing fails
     */
    public static RocketMQResourceInfo pareAgentCardUrl(String agentCardUrl) {
        if (StringUtils.isEmpty(agentCardUrl) || (!agentCardUrl.startsWith(HTTPS_URL_PREFIX) && !agentCardUrl.startsWith(HTTP_URL_PREFIX))) {
            return null;
        }
        if (agentCardUrl.startsWith(HTTPS_URL_PREFIX)) {
            agentCardUrl = agentCardUrl.substring(HTTPS_URL_PREFIX.length());
        } else if (agentCardUrl.startsWith(HTTP_URL_PREFIX)) {
            agentCardUrl = agentCardUrl.substring(HTTP_URL_PREFIX.length());
        }
        String[] split = agentCardUrl.split("/");
        if (split.length != 3) {
            return null;
        }
        RocketMQResourceInfo rocketMQResourceInfo = new RocketMQResourceInfo();
        rocketMQResourceInfo.setEndpoint(split[0].trim());
        rocketMQResourceInfo.setNamespace(split[1].trim());
        rocketMQResourceInfo.setTopic(split[2].trim());
        return rocketMQResourceInfo;
    }

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

}
