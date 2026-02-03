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
package org.apache.rocketmq.a2a.transport.metadata;

import io.a2a.server.TransportMetadata;
import static org.apache.rocketmq.a2a.common.constant.RocketMQA2AConstant.ROCKETMQ_PROTOCOL;

/**
 * Metadata implementation for the RocketMQTransport.
 * <p>
 * This class provides protocol identification by returning the {@link #ROCKETMQ_PROTOCOL}
 * constant as the transport protocol name, enabling the A2A framework to recognize
 * and route messages through the RocketMQ transport layer.
 * <p>
 * It is used internally by the A2A protocol stack to associate message exchanges
 * with the RocketMQ transport mechanism.
 */
public class RocketMQTransportMetadata implements TransportMetadata {
    @Override
    public String getTransportProtocol() {
        return ROCKETMQ_PROTOCOL;
    }
}
