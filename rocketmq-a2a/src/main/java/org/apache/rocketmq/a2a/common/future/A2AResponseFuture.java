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
    private final CompletableFuture<String> completableFuture;

    /**
     * A {@link TypeReference} that captures the generic type information of the expected deserialized response.
     * This is necessary for correct JSON deserialization of parameterized types.
     */
    private final TypeReference typeReference;

    /**
     * Constructs a new {@code A2AResponseFuture} instance.
     *
     * @param completableFuture a {@link CompletableFuture} containing the raw JSON response from a remote agent.
     * @param typeReference a {@link TypeReference} specifying the target type for deserializing the JSON response.
     */
    public A2AResponseFuture(CompletableFuture<String> completableFuture, TypeReference typeReference) {
        this.completableFuture = completableFuture;
        this.typeReference = typeReference;
    }

    /**
     * Returns the {@link CompletableFuture} that holds the raw JSON response string.
     *
     * @return the {@link CompletableFuture} containing the raw JSON response.
     */
    public CompletableFuture<String> getCompletableFuture() {
        return completableFuture;
    }

    /**
     * Returns the {@link TypeReference} used for deserializing the JSON response.
     *
     * @return the {@link TypeReference} specifying the target type for deserialization.
     */
    public TypeReference getTypeReference() {
        return typeReference;
    }

    /**
     * Creates and returns a new {@link Builder} instance for constructing {@code A2AResponseFuture} objects.
     *
     * @return a new {@link Builder} instance.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A builder class for constructing {@code A2AResponseFuture} instances.
     * Provides a fluent API for setting the required components.
     */
    public static class Builder {
        private CompletableFuture<String> completableFuture;
        private TypeReference<?> typeReference;

        /**
         * Sets the {@link CompletableFuture} that will hold the raw JSON response.
         *
         * @param completableFuture the {@link CompletableFuture} to set.
         * @return this builder instance for method chaining.
         */
        public Builder completableFuture(CompletableFuture<String> completableFuture) {
            this.completableFuture = completableFuture;
            return this;
        }

        /**
         * Sets the {@link TypeReference} that specifies the target type for deserializing the JSON response.
         *
         * @param typeReference the {@link TypeReference} to set.
         * @return this builder instance for method chaining.
         */
        public Builder typeReference(TypeReference<?> typeReference) {
            this.typeReference = typeReference;
            return this;
        }

        /**
         * Builds and returns a new {@code A2AResponseFuture} instance.
         * Validates that both {@code completableFuture} and {@code typeReference} are not null.
         *
         * @return a new {@code A2AResponseFuture} instance.
         */
        public A2AResponseFuture build() {
            return new A2AResponseFuture(completableFuture, typeReference);
        }
    }
}
