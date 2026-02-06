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
package util;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import com.alibaba.fastjson.JSON;
import model.RocketMQResponse;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.SessionCredentialsProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.LitePushConsumer;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.ProducerBuilder;
import org.apache.rocketmq.shaded.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for building and managing RocketMQ clients (Producer, Consumer) and messages.
 * <p>
 * This class loads configuration from system properties and provides factory methods to create
 * RocketMQ producers and consumers with consistent settings.
 *
 */
public class RocketMQUtil {
    private static final Logger log = LoggerFactory.getLogger(RocketMQUtil.class);
    private static final ClientServiceProvider PROVIDER = ClientServiceProvider.loadService();

    /**
     * The dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     */
    public static final String WORK_AGENT_RESPONSE_TOPIC = System.getProperty("workAgentResponseTopic");

    /**
     * The consumer group ID used when subscribing to the {@link #WORK_AGENT_RESPONSE_TOPIC}.
     */
    public static final String WORK_AGENT_RESPONSE_GROUP_ID = System.getProperty("workAgentResponseGroupID");

    /**
     * the namespace used for logical isolation of RocketMQ resources.
     */
    public static final String ROCKETMQ_NAMESPACE = System.getProperty("rocketMQNamespace");

    /**
     * The network endpoint of the RocketMQ service.
     */
    public static final String ROCKETMQ_ENDPOINT = System.getProperty("rocketMQEndpoint");

    /**
     * The access key for authenticating with the RocketMQ service.
     */
    public static final String ACCESS_KEY = System.getProperty("rocketMQAK");

    /**
     * The secret key for authenticating with the RocketMQ service.
     */
    public static final String SECRET_KEY = System.getProperty("rocketMQSK");

    /**
     * The RocketMQ topic associated with the target agent, used as the destination for client requests.
     */
    public static final String AGENT_TOPIC = System.getProperty("agentTopic");

    /**
     * The standard RocketMQ business topic bound to the Agent, used for receiving task requests and other information.
     */
    public static final String BIZ_TOPIC = System.getProperty("bizTopic", "");

    /**
     * The CID used to subscribe to the standard business topic bound to the Agent.
     */
    public static final String BIZ_CONSUMER_GROUP = System.getProperty("bizConsumerGroup", "");

    /**
     * Builds a RocketMQ producer with the given configuration.
     *
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param topics Topics to publish to.
     * @return a configured {@link Producer} instance, or {@code null} if validation fails.
     * @throws ClientException if RocketMQ client fails to initialize.
     */
    public static Producer buildProducer(String endpoint, String namespace, String accessKey, String secretKey, String... topics) throws ClientException {
        if (StringUtils.isEmpty(endpoint)) {
            log.warn("RocketMQUtil buildProducer param error, endpoint: [{}]", endpoint);
            throw new IllegalArgumentException("RocketMQUtil buildProducer param error");
        }
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(endpoint)
            .setNamespace(namespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .setRequestTimeout(Duration.ofSeconds(15))
            .build();
        final ProducerBuilder builder = PROVIDER.newProducerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setTopics(topics);
        return builder.build();
    }

    /**
     * Builds a LitePushConsumer for lightweight message consumption.
     *
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param workAgentResponseGroupID the consumer group ID used when subscribing to the {@code #workAgentResponseTopic}.
     * @param workAgentResponseTopic the dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     * @param messageListener message processing callback.
     * @return a configured {@link LitePushConsumer}, or {@code null} if validation fails.
     * @throws ClientException if RocketMQ client fails to initialize.
     */
    public static LitePushConsumer buildLitePushConsumer(String endpoint, String namespace, String accessKey, String secretKey, String workAgentResponseGroupID, String workAgentResponseTopic, MessageListener messageListener) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(workAgentResponseGroupID) || StringUtils.isEmpty(workAgentResponseTopic) || null == messageListener) {
            log.warn("RocketMQUtil buildLiteConsumer param error, endpoint: [{}], workAgentResponseGroupID: [{}], workAgentResponseTopic: [{}], messageListener: [{}]", endpoint, workAgentResponseGroupID, workAgentResponseTopic, messageListener);
            throw new IllegalArgumentException("RocketMQUtil buildLitePushConsumer param error");
        }
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(endpoint)
            .setNamespace(namespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .build();
        return PROVIDER.newLitePushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(workAgentResponseGroupID)
            .bindTopic(workAgentResponseTopic)
            .setMessageListener(messageListener).build();
    }

    /**
     * Builds a standard Push Consumer with tag-based filtering.
     *
     * @param endpoint the network endpoint of the RocketMQ service.
     * @param namespace the namespace used for logical isolation of RocketMQ resources.
     * @param accessKey the access key for authenticating with the RocketMQ service.
     * @param secretKey the secret key for authenticating with the RocketMQ service.
     * @param consumerGroup the CID used to subscribe to the standard business topic bound to the Agent.
     * @param topic the standard RocketMQ business topic bound to the Agent, used for receiving task requests and other information.
     * @param messageListener Message processing callback.
     * @return a configured {@link PushConsumer}, or {@code null} if validation fails
     * @throws ClientException if RocketMQ client fails to initialize
     */
    public static PushConsumer buildPushConsumer(String endpoint, String namespace, String accessKey, String secretKey, String consumerGroup, String topic, MessageListener messageListener) throws ClientException {
        if (StringUtils.isEmpty(endpoint) || StringUtils.isEmpty(consumerGroup) || StringUtils.isEmpty(topic) || null == messageListener) {
            log.warn("RocketMQUtil buildPushConsumer param error, endpoint: [{}], bizGroup: [{}], bizTopic: [{}], messageListener: [{}]", endpoint, consumerGroup, topic, messageListener);
            throw new IllegalArgumentException("RocketMQUtil buildPushConsumer param error");
        }
        SessionCredentialsProvider sessionCredentialsProvider = new StaticSessionCredentialsProvider(accessKey, secretKey);
        ClientConfiguration clientConfiguration = ClientConfiguration.newBuilder()
            .setEndpoints(endpoint)
            .setNamespace(namespace)
            .setCredentialProvider(sessionCredentialsProvider)
            .build();
        return PROVIDER.newPushConsumerBuilder()
            .setClientConfiguration(clientConfiguration)
            .setConsumerGroup(consumerGroup)
            .setSubscriptionExpressions(Collections.singletonMap(topic, new FilterExpression("*", FilterExpressionType.TAG)))
            .setMessageListener(messageListener).build();
    }

    /**
     * Builds a RocketMQ message from a {@link RocketMQResponse}.
     *
     * @param topic the dedicated topic for receiving reply messages from the target agent(Typically, a lightweight Topic).
     * @param liteTopic LiteTopic is a lightweight session identifier, similar to a SessionId, dynamically created at runtime for data storage and isolation.
     * @param response Response payload.
     * @return a {@link Message} instance, or {@code null} if validation fails.
     */
    public static Message buildMessage(String topic, String liteTopic, RocketMQResponse response) {
        if (StringUtils.isEmpty(topic) || StringUtils.isEmpty(liteTopic)) {
            log.warn("RocketMQUtil buildMessage param error, topic: [{}], liteTopic: [{}], response: [{}]", topic, liteTopic, JSON.toJSONString(response));
            throw new IllegalArgumentException("RocketMQUtil buildMessage param error");
        }
        return PROVIDER.newMessageBuilder()
            .setTopic(topic)
            .setBody(JSON.toJSONString(response).getBytes(StandardCharsets.UTF_8))
            .setLiteTopic(liteTopic)
            .build();
    }

    /**
     * Checks if the required RocketMQ client configuration parameters are set.
     * Throws an exception if any of the required parameters is missing.
     */
    public static void checkRocketMQConfigParamClient() {
        if (StringUtils.isEmpty(WORK_AGENT_RESPONSE_TOPIC) || StringUtils.isEmpty(WORK_AGENT_RESPONSE_GROUP_ID) || StringUtils.isEmpty(ROCKETMQ_ENDPOINT) || StringUtils.isEmpty(AGENT_TOPIC)) {
            log.warn("checkRocketMQConfigParamClient param error, workAgentResponseTopic: [{}], workAgentResponseGroupID: [{}], rocketMQEndpoint: [{}], agentTopic: [{}]", WORK_AGENT_RESPONSE_TOPIC, WORK_AGENT_RESPONSE_GROUP_ID, ROCKETMQ_ENDPOINT, AGENT_TOPIC);
            throw new IllegalArgumentException("checkRocketMQConfigParamClient param error");
        }
    }

    /**
     * Checks if the required RocketMQ server configuration parameters are set.
     * Throws an exception if any of the required parameters is missing.
     */
    public static void checkRocketMQConfigParamServer() {
        if (StringUtils.isEmpty(ROCKETMQ_ENDPOINT) || StringUtils.isEmpty(BIZ_TOPIC) || StringUtils.isEmpty(BIZ_CONSUMER_GROUP)) {
            log.warn("checkRocketMQConfigParamServer error, rocketMQEndpoint: [{}], bizTopic: [{}], bizConsumerGroup: [{}]", ROCKETMQ_ENDPOINT, BIZ_TOPIC, BIZ_CONSUMER_GROUP);
            throw new IllegalArgumentException("checkRocketMQConfigParamServer param error");
        }
    }
}
