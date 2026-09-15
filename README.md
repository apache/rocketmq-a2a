# RocketMQ-A2A

## This Repository Has Been Migrated to [apache/rocketmq-ai](https://github.com/apache/rocketmq-ai)

**rocketmq-a2a is now maintained in [apache/rocketmq-ai](https://github.com/apache/rocketmq-ai).**

**Please open issues, submit pull requests, and follow releases in the new repository.**

**This repository is no longer actively maintained.**

---

This project aims to help developers quickly integrate [Apache RocketMQ](http://rocketmq.apache.org/) with [A2A](https://github.com/a2aproject/a2a-java).

The choice of communication middleware is very important when building a distributed Agent architecture with high availability and scalability.

## Repository Layout

This repository hosts two peer modules:

- [rocketmq-a2a/](rocketmq-a2a/) — the Java A2A transport (`RocketMQTransport`, `RocketMQA2AServerRoutes`) with its Maven build and samples under `example/`. Build with `mvn -B package --file rocketmq-a2a/pom.xml` from the repository root.
- [mcp-tasks-rocketmq/](mcp-tasks-rocketmq/) — the Python MCP Tasks extension on a RocketMQ LiteTopic backend, published to PyPI as `mcp-tasks-rocketmq`.

## Features

- Enable Asynchronous Communication and Logical Decoupling

With [Apache RocketMQ](http://rocketmq.apache.org/) as the underlying transport, Agent-to-Agent interactions shift from synchronous RPC calls to asynchronous messaging, decoupling producer and consumer logic. Senders can proceed immediately without blocking on responses, significantly improving system throughput and responsiveness.

- Enhance Fault Tolerance and Resilience to Network Fluctuations

[Apache RocketMQ](http://rocketmq.apache.org/) ensures message persistence and supports configurable retry policies, preventing message loss during transient network outages. This mitigates cascading failures and guarantees eventual delivery, strengthening overall communication reliability.

- Improve System Stability and Availability

As a production-proven messaging infrastructure, [Apache RocketMQ](http://rocketmq.apache.org/) enhances the robustness and SLA compliance of the entire A2A Agent network, ensuring continuous operation under failure conditions.

- Smooth Traffic Spikes with Load Buffering

In high-concurrency scenarios, [Apache RocketMQ](http://rocketmq.apache.org/) acts as a buffer to absorb message bursts, smoothing peak loads and protecting downstream services from overload—enabling elastic scaling and balanced resource utilization.

- Standardize Integration to Simplify Development and Operations

The RocketMQTransport component provides a unified messaging abstraction, hiding transport complexity and allowing developers to focus on business logic.

The RocketMQA2AServerRoutes enables streamlined server-side routing and message dispatching, reducing integration effort and operational overhead.

## Prerequisites

- JDK 17 and above
- [Maven](http://maven.apache.org/) 3.9 and above

## Usage
Add a dependency using maven:

```xml
<!--add dependency in pom.xml-->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-a2a</artifactId>
    <version>${RELEASE.VERSION}</version>
</dependency>
```
Create an A2A Client Using RocketMQTransport and RocketMQTransportConfig

```java
   // build client with RocketMQTransport and RocketMQTransportConfig
   RocketMQTransportConfig rocketMQTransportConfig = new RocketMQTransportConfig();
       rocketMQTransportConfig.setAccessKey(accessKey);
       rocketMQTransportConfig.setSecretKey(secretKey);
       rocketMQTransportConfig.setWorkAgentResponseGroupID(WorkAgentResponseGroupID);
       rocketMQTransportConfig.setWorkAgentResponseTopic(WorkAgentResponseTopic);
       rocketMQTransportConfig.setRocketMQNamespace(RocketMQNamespace);
       Client client = Client.builder(finalAgentCard)
       .addConsumers(consumers)
       .streamingErrorHandler(streamingErrorHandler)
       .withTransport(RocketMQTransport.class, rocketMQTransportConfig)
    .build();
```

Add RocketMQA2AServerRoutes to enable server-side request forwarding over the RocketMQ protocol, specifically for server implementations based on [Quarkus](https://quarkus.io)

```xml
<!--add this in application.properties-->
quarkus.index-dependency.rocketmq-a2a.group-id=org.apache.rocketmq
quarkus.index-dependency.rocketmq-a2a.artifact-id=rocketmq-a2a
```
## Samples
### 1.[Apache RocketMQ](http://rocketmq.apache.org/) + [A2A](https://github.com/a2aproject/a2a-java) + [Google ADK(Agent Development Kit)](https://github.com/google/adk-java) sample

Please see the [rocketmq-multiagent-base-adk](rocketmq-a2a/example/java/rocketmq-multiagent-base-adk).

### 2.[Apache RocketMQ](http://rocketmq.apache.org/) + [A2A](https://github.com/a2aproject/a2a-java) + [AgentScope](https://github.com/agentscope-ai) sample

Please see the [rocketmq-multiagent-base-agentscope](rocketmq-a2a/example/java/rocketmq-multiagent-base-agentscope).

### 3.[Apache RocketMQ](http://rocketmq.apache.org/) + Session state consistency sample

Please see the [rocketmq-multiagent-session-consistency](rocketmq-a2a/example/java/rocketmq-multiagent-session-consistency).

### 4.[Apache RocketMQ](http://rocketmq.apache.org/) + [LangGraph](https://github.com/langchain-ai/langgraph) sample

Please see the [rocketmq-multiagent-base-langgraph](rocketmq-a2a/example/python/rocketmq-multiagent-base-langgraph).

## MCP Tasks Extension

[mcp-tasks-rocketmq](mcp-tasks-rocketmq/) implements the [MCP Tasks extension](https://github.com/modelcontextprotocol/modelcontextprotocol) (SEP-2663) on top of Apache RocketMQ LiteTopic: a RocketMQ worker pool executes agent tasks, and each task maps to one lite channel that serves as its ledger. It is a peer of the Java A2A transport in this repository and is published to PyPI as `mcp-tasks-rocketmq`.

See [mcp-tasks-rocketmq/README.md](mcp-tasks-rocketmq/README.md) for usage, and [mcp-tasks-rocketmq/examples/](mcp-tasks-rocketmq/examples/) for runnable examples.

## Contributing

We are always very happy to have contributions, whether for trivial cleanups or big new features. Please see the RocketMQ main website to read the [details](http://rocketmq.apache.org/docs/how-to-contribute/).

## License

[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html) Copyright (C) Apache Software Foundation 
