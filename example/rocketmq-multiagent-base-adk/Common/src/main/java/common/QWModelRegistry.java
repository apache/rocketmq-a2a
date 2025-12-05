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

public class QWModelRegistry {
    private static final Logger log = LoggerFactory.getLogger(QWModel.class);
    private static boolean initialized = false;
    private static QWModel qwModel;

    public static synchronized void registerQWenModel(String apiKey) {
        if (StringUtils.isEmpty(apiKey)) {
            System.out.println("QWen 模型初始化过程中的apiKey为空，请进行配置 QWen 模型的apiKey");
            return;
        }
        if (initialized) {
            log.debug("QWen 模型已经初始化过了");
            return;
        }
        try {
            qwModel = new QWModel(apiKey);
            initialized = true;
            log.info("✅ QWen 模型初始化成功");
        } catch (Exception e) {
            log.error("❌ QWen 模型初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize QWen model", e);
        }
    }

    public static QWModel getModel(String apiKey) {
        if (!initialized) {
            registerQWenModel(apiKey);
        }
        return qwModel;
    }
}
