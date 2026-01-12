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

import com.fasterxml.jackson.core.type.TypeReference;
import io.a2a.spec.CancelTaskResponse;
import io.a2a.spec.DeleteTaskPushNotificationConfigResponse;
import io.a2a.spec.GetAuthenticatedExtendedCardResponse;
import io.a2a.spec.GetTaskPushNotificationConfigResponse;
import io.a2a.spec.GetTaskResponse;
import io.a2a.spec.ListTaskPushNotificationConfigResponse;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.SetTaskPushNotificationConfigResponse;

/**
 * The constant of rocketmq-a2a component
 */
public class RocketMQA2AConstant {
    //The TypeReference used for deserializing the response result of SendMessageRequest in the A2A protocol.
    public static final TypeReference<SendMessageResponse> SEND_MESSAGE_RESPONSE_REFERENCE = new TypeReference<>() { };
    //The TypeReference used for deserializing the response result of GetTaskRequest in the A2A protocol.
    public static final TypeReference<GetTaskResponse> GET_TASK_RESPONSE_REFERENCE = new TypeReference<>() { };
    //The TypeReference used for deserializing the response result of CancelTaskRequest in the A2A protocol.
    public static final TypeReference<CancelTaskResponse> CANCEL_TASK_RESPONSE_REFERENCE = new TypeReference<>() { };
    //The TypeReference used for deserializing the response result of GetTaskPushNotificationConfigRequest in the A2A protocol.
    public static final TypeReference<GetTaskPushNotificationConfigResponse> GET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };
    //The TypeReference used for deserializing the response result of SetTaskPushNotificationConfigRequest the A2A protocol.
    public static final TypeReference<SetTaskPushNotificationConfigResponse> SET_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };
    //The TypeReference used for deserializing the response result of ListTaskPushNotificationConfigRequest in the A2A protocol.
    public static final TypeReference<ListTaskPushNotificationConfigResponse> LIST_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() { };
    //The TypeReference used for deserializing the response result of the GetAuthenticatedExtendedCardRequest method in the A2A protocol.
    public static final TypeReference<GetAuthenticatedExtendedCardResponse> GET_AUTHENTICATED_EXTENDED_CARD_RESPONSE_REFERENCE = new TypeReference<>() { };
    public static final TypeReference<DeleteTaskPushNotificationConfigResponse> DELETE_TASK_PUSH_NOTIFICATION_CONFIG_RESPONSE_REFERENCE = new TypeReference<>() {};
    public static final String HTTP_URL_PREFIX = "http://";
    public static final String HTTPS_URL_PREFIX = "https://";
    public static final String ROCKETMQ_PROTOCOL = "RocketMQ";
    public static final String MESSAGE_RESPONSE_ID = "messageResponseId";
    public static final String LITE_TOPIC = "LITE_TOPIC";
    public static final String CLOSE_LITE_TOPIC = "CLOSE_LITE_TOPIC";
    public static final String DEFAULT_STREAM_RECOVER = "default";
    //sse data prefix
    public static final String DATA_PREFIX = "data:";
    public static final String METHOD = "method";
}
