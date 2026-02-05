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
package org.apache.rocketmq.a2a.common.uitl;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility class for generating unique request IDs.
 */
public class RequestIdGenerator {

    /**
     * Characters used for hexadecimal representation.
     */
    private static final char[] CHARS = "0123456789abcdef".toCharArray();

    /**
     * Thread-local buffer to avoid allocation overhead.
     */
    private static final ThreadLocal<char[]> TL_BUFFER = ThreadLocal.withInitial(() -> new char[32]);

    /**
     * Generates a unique request ID as a hexadecimal string.
     *
     * @return A 32-character hexadecimal string representing the request ID.
     */
    public static String nextId() {
        char[] buffer = TL_BUFFER.get();
        long mostSig = ThreadLocalRandom.current().nextLong();
        long leastSig = ThreadLocalRandom.current().nextLong();

        // Format the most significant bits
        formatUnsignedLong(mostSig >>> 32, buffer, 0);
        formatUnsignedLong(mostSig, buffer, 8);

        // Format the least significant bits
        formatUnsignedLong(leastSig >>> 32, buffer, 16);
        formatUnsignedLong(leastSig, buffer, 24);

        return new String(buffer);
    }

    /**
     * Formats a long value into a hexadecimal string and stores it in the buffer.
     *
     * @param value  The long value to format.
     * @param buffer The character array to store the formatted result.
     * @param offset The starting position in the buffer.
     */
    private static void formatUnsignedLong(long value, char[] buffer, int offset) {
        for (int i = 7; i >= 0; i--) {
            buffer[offset + i] = CHARS[(int)(value & 0xF)];
            value >>>= 4;
        }
    }
}
