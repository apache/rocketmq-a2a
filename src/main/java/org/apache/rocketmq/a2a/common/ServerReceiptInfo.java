package org.apache.rocketmq.a2a.common;

/**
 * Server receipt information
 */
public class ServerReceiptInfo {
    /**
     * The lite topic used by the server to receive response results
     */
    private String serverWorkAgentResponseTopic;

    /**
     * todo
     * The lite topic used by the server to receive response results(sessionId)
     */
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
