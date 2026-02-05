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
package org.example.stream.event;

/**
 * Represents an event in a data stream that carries both content and a position identifier (offset).
 * This allows for ordered processing, deduplication, and resumable streaming in case of disconnections.
 */
public class StreamEvent {
    /**
     * The position of this event in the stream. Used for recovery, ordering, and deduplication.
     * Clients can store this value and send it back when reconnecting to resume from the last received event.
     */
    private final long offset;

    /**
     * The actual data or message content of this event.
     * typically a plain string or serialized JSON object.
     */
    private final String content;

    public StreamEvent(long offset, String content) {
        this.offset = offset;
        this.content = content;
    }

    public static Builder builder() {
        return new Builder();
    }

    public long getOffset() {
        return offset;
    }

    public String getContent() {
        return content;
    }

    public static class Builder {
        private long offset;
        private String content;

        public Builder offset(long offset) {
            this.offset = offset;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public StreamEvent build() {
            return new StreamEvent(offset, content);
        }
    }
}
