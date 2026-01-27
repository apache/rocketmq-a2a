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
package org.apache.rocketmq.a2a.common.constant;

import com.fasterxml.jackson.core.type.TypeReference;
import io.a2a.spec.CancelTaskRequest;
import io.a2a.spec.CancelTaskResponse;
import io.a2a.spec.DeleteTaskPushNotificationConfigRequest;
import io.a2a.spec.DeleteTaskPushNotificationConfigResponse;
import io.a2a.spec.GetAuthenticatedExtendedCardRequest;
import io.a2a.spec.GetAuthenticatedExtendedCardResponse;
import io.a2a.spec.GetTaskPushNotificationConfigRequest;
import io.a2a.spec.GetTaskPushNotificationConfigResponse;
import io.a2a.spec.GetTaskRequest;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.ListTaskPushNotificationConfigRequest;
import io.a2a.spec.ListTaskPushNotificationConfigResponse;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.SetTaskPushNotificationConfigRequest;
import io.a2a.spec.SetTaskPushNotificationConfigResponse;

/**
 * Constants used in the RocketMQ A2A (Agent-to-Agent) communication protocol.
 * Includes type references for JSON deserialization, protocol identifiers, and common headers.
 */
public class RocketMQA2AConstant {
    /**
     * TypeReference for deserializing the response of {@link SendMessageRequest}.
     */
    public static final TypeReference<SendMessageResponse> SEND_MESSAGE_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * TypeReference for deserializing the response of {@link GetTaskRequest}.
     */
    public static final TypeReference<GetTaskResponse> GET_TASK_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * TypeReference for deserializing the response of {@link CancelTaskRequest}.
     */
    public static final TypeReference<CancelTaskResponse> CANCEL_TASK_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * TypeReference for deserializing the response of {@link GetTaskPushNotificationConfigRequest}.
     */
    public static final TypeReference<GetTaskPushNotificationConfigResponse> GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * TypeReference for deserializing the response of {@link SetTaskPushNotificationConfigRequest}.
     */
    public static final TypeReference<SetTaskPushNotificationConfigResponse> SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * TypeReference for deserializing the response of {@link ListTaskPushNotificationConfigRequest}.
     */
    public static final TypeReference<ListTaskPushNotificationConfigResponse> LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * TypeReference for deserializing the response of {@link GetAuthenticatedExtendedCardRequest}.
     */
    public static final TypeReference<GetAuthenticatedExtendedCardResponse> GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * TypeReference for deserializing the response of {@link DeleteTaskPushNotificationConfigRequest}.
     */
    public static final TypeReference<DeleteTaskPushNotificationConfigResponse> DELETE_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };

    /**
     * Prefix for HTTP URLs.
     */
    public static final String HTTP_URL_PREFIX = "http://";

    /**
     * Prefix for HTTPS URLs.
     */
    public static final String HTTPS_URL_PREFIX = "https://";

    public static final String RESOURCE_SPLIT = "/";

    /**
     * Protocol identifier used in A2A communication.
     */
    public static final String ROCKETMQ_PROTOCOL = "RocketMQ";

    /**
     * Field name for message response ID in A2A responses.
     */
    public static final String MESSAGE_RESPONSE_ID = "messageResponseId";

    /**
     * The marker for subscribing to LiteTopic.
     */
    public static final String SUB_LITE_TOPIC = "SUB_LITE_TOPIC";

    /**
     * The marker for unsubscribing from LiteTopic.
     */
    public static final String UNSUB_LITE_TOPIC = "UNSUB_LITE_TOPIC";

    public static final String DEFAULT_STREAM_RECOVER = "default";

    /**
     * Data prefix used in Server-Sent Events (SSE) protocol.
     * Format: {@code data: <payload>\n}
     *
     * @see <a href="https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events">SSE Specification</a>
     */
    public static final String DATA_PREFIX = "data:";

    /**
     * Header or parameter name indicating the target method in A2A requests.
     * Used by the server to route incoming requests to the appropriate handler.
     */
    public static final String METHOD = "method";
}
