package org.apache.rocketmq.a2a.common;

public class ServerReceiptInfo {

    private String serverWorkAgentResponseTopic;
    private String serverLiteTopic;

    public ServerReceiptInfo(String serverWorkAgentResponseTopic, String serverLiteTopic) {
        this.serverWorkAgentResponseTopic = serverWorkAgentResponseTopic;
        this.serverLiteTopic = serverLiteTopic;
    }

    public String getServerWorkAgentResponseTopic() {
        return serverWorkAgentResponseTopic;
    }

    public String getServerLiteTopic() {
        return serverLiteTopic;
    }
}
