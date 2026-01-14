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

import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Used to handle the response results of pending outgoing requests,
 * where the responses are asynchronously obtained and deserialized by a message listener
 */
public class A2AResponseFuture {

    /**
     * Asynchronously passing results across threads
     */
    private CompletableFuture<String> completableFuture;

    /**
     * Generic type reference used for deserializing A2A protocol response results
     */
    private TypeReference typeReference;

    /**
     * Creates an AsyncTypedResult with the given future and type reference
     * @param completableFuture Asynchronously passing results across threads
     * @param typeReference TypeReference used to deserialize the response into the correct generic type
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
