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
package org.apache.rocketmq.a2a.common.future;

import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Represents a future result of an Agent-to-Agent (A2A) communication.
 * <p>
 * This class wraps a {@link CompletableFuture} that holds the raw JSON response string
 * from a remote agent, along with a {@link TypeReference} that specifies how to
 * deserialize the response into a strongly-typed generic object.
 */
public class A2AResponseFuture {

    /**
     * A {@link CompletableFuture} that asynchronously delivers the raw JSON response
     * string from a remote agent. The result is completed when the agent's reply is received.
     */
    private CompletableFuture<String> completableFuture;

    /**
     * A {@link TypeReference} that captures the generic type information of the expected deserialized response.
     * This is necessary for correct JSON deserialization of parameterized types.
     */
    private TypeReference typeReference;

    /**
     * Constructs a new {@code A2AResponseFuture} instance.
     *
     * @param completableFuture a {@link CompletableFuture} containing the raw JSON response
     *                          from a remote agent.
     * @param typeReference a {@link TypeReference} specifying the target type for
     *                          deserializing the JSON response.
     */
    public A2AResponseFuture(CompletableFuture<String> completableFuture, TypeReference typeReference) {
        this.completableFuture = completableFuture;
        this.typeReference = typeReference;
    }

    public CompletableFuture<String> getCompletableFuture() {
        return completableFuture;
    }

    public TypeReference getTypeReference() {
        return typeReference;
    }
}
