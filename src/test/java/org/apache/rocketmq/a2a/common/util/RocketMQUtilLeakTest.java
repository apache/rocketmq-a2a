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
package org.apache.rocketmq.a2a.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.a2a.common.future.A2AResponseFuture;
import org.junit.Assert;
import org.junit.Test;

public class RocketMQUtilLeakTest {

    /**
     * getResult() registers a pending future in MESSAGE_RESPONSE_MAP and then blocks on
     * completableFuture.get(120s). The map entry is only removed on the success path (right after
     * get() returns). If get() throws (a failed/exceptional response, a 120s timeout, or an
     * interrupt), the remove() is skipped and the entry leaks in the static map forever.
     *
     * This test triggers the exceptional-completion path (fast, no 120s wait) and asserts the
     * pending entry is cleaned up. It fails before the fix and passes after.
     */
    @Test(timeout = 20000)
    public void getResultShouldNotLeakPendingEntryWhenResponseFails() throws Exception {
        final String namespace = "leak-test-" + System.nanoTime();
        final String msgId = "req-1";
        final TypeReference<String> typeRef = new TypeReference<String>() {
        };

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> caller = pool.submit(() -> {
                try {
                    RocketMQUtil.getResult(msgId, namespace, typeRef);
                } catch (Exception expected) {
                    // ExecutionException (failed response) is expected here.
                }
            });

            // Wait until getResult has registered the pending future.
            A2AResponseFuture pending = null;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                Map<String, A2AResponseFuture> nsMap = RocketMQUtil.MESSAGE_RESPONSE_MAP.get(namespace);
                if (nsMap != null && (pending = nsMap.get(msgId)) != null) {
                    break;
                }
                Thread.sleep(5);
            }
            Assert.assertNotNull("getResult should have registered a pending future", pending);

            // Simulate a failed remote response so get() throws ExecutionException.
            pending.getCompletableFuture().completeExceptionally(new RuntimeException("simulated remote failure"));

            // Wait for getResult to return/throw.
            caller.get(5, TimeUnit.SECONDS);

            Map<String, A2AResponseFuture> after = RocketMQUtil.MESSAGE_RESPONSE_MAP.get(namespace);
            boolean leaked = after != null && after.containsKey(msgId);
            Assert.assertFalse("pending future entry leaked in MESSAGE_RESPONSE_MAP after a failed response", leaked);
        } finally {
            pool.shutdownNow();
        }
    }
}
