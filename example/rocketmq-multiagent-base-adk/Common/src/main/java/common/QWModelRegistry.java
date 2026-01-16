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
package common;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A registry for managing the singleton instance of {@link QWModel}.
 * <p>
 * This class ensures that the Qwen (QWen) large language model is initialized only once,
 * using the provided API key. It supports lazy initialization and thread-safe access.
 */
public class QWModelRegistry {
    private static final Logger log = LoggerFactory.getLogger(QWModelRegistry.class);

    /**
     * Flag indicating whether the QWen model has been successfully initialized.
     */
    private static boolean initialized = false;

    /**
     * The singleton instance of the QWen model.
     */
    private static QWModel qwModel;

    /**
     * Registers and initializes the QWen model with the given API key.
     * <p>
     * This method is idempotent: subsequent calls after successful initialization are ignored.
     * If the API key is empty or null, initialization is skipped and a warning is logged.
     *
     * @param apiKey the API key for authenticating with the Qwen service
     * @throws IllegalArgumentException if the API key is blank and initialization is attempted
     * @throws RuntimeException         if model initialization fails due to configuration or network issues
     */
    public static synchronized void registerQWenModel(String apiKey) {
        // Skip if already initialized
        if (initialized) {
            log.debug("QWen model is already initialized.");
            return;
        }
        // Validate API key
        if (StringUtils.isEmpty(apiKey)) {
            log.warn("QWen model initialization skipped: API key is empty or not configured.");
            throw new IllegalArgumentException("QWen API key must not be empty");
        }
        try {
            qwModel = new QWModel(apiKey);
            initialized = true;
            log.info("✅ QWen model initialized successfully.");
        } catch (Exception e) {
            log.error("❌ Failed to initialize QWen model", e);
            throw new RuntimeException("Failed to initialize QWen model", e);
        }
    }

    /**
     * Retrieves the singleton instance of the QWen model.
     * <p>
     * If the model has not been initialized yet, this method will attempt to initialize it
     * using the provided API key. This method is thread-safe.
     *
     * @param apiKey the API key for the Qwen service
     * @return the initialized {@link QWModel} instance
     * @throws IllegalArgumentException if the API key is blank
     * @throws RuntimeException         if initialization fails
     */
    public static QWModel getModel(String apiKey) {
        if (!initialized) {
            // Delegate to the synchronized registration method
            registerQWenModel(apiKey);
        }
        return qwModel;
    }
}
