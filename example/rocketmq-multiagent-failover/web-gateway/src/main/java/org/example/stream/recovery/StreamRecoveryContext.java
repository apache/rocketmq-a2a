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
package org.example.stream.recovery;

import java.util.concurrent.CompletableFuture;

import reactor.core.publisher.Sinks;
import reactor.core.publisher.Sinks.Many;

/**
 * Represents a session context used during the recovery of a disconnected or interrupted communication stream.
 * It is typically used in reactive streaming scenarios such as Server-Sent Events (SSE).
 */
public class StreamRecoveryContext {
    /**
     * A multi-emitter sink that allows sending multiple string events to subscribers.
     * Used during session recovery to push incremental updates, status messages, or data replays.
     */
    private Sinks.Many<String> sink;

    /**
     * A CompletableFuture that represents the asynchronous result of the recovery operation.
     */
    private CompletableFuture<Boolean> completableFuture;

    public StreamRecoveryContext(Many<String> sink, CompletableFuture<Boolean> completableFuture) {
        this.sink = sink;
        this.completableFuture = completableFuture;
    }

    public StreamRecoveryContext() {
    }

    public static Builder builder() {
        return new Builder();
    }

    public Many<String> getSink() {
        return sink;
    }

    public void setSink(Many<String> sink) {
        this.sink = sink;
    }

    public CompletableFuture<Boolean> getCompletableFuture() {
        return completableFuture;
    }

    public void setCompletableFuture(CompletableFuture<Boolean> completableFuture) {
        this.completableFuture = completableFuture;
    }

    public static class Builder {
        private Sinks.Many<String> sink;
        private CompletableFuture<Boolean> completableFuture;

        public Builder sink(Sinks.Many<String> sink) {
            this.sink = sink;
            return this;
        }

        public Builder completableFuture(CompletableFuture<Boolean> completableFuture) {
            this.completableFuture = completableFuture;
            return this;
        }

        public StreamRecoveryContext build() {
            return new StreamRecoveryContext(sink, completableFuture);
        }
    }
}
