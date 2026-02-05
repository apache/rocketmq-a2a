
# Quick Start

本案例展示了一个利用 [Apache RocketMQ](http://rocketmq.apache.org/) 的LiteTopic能力，为分布式Web应用中的长连接会话管理提供了高效解决方案。

# 整体架构图
<img src="docs/img.png" alt="Architecture Diagram" width="1200" />

# 基本的前期准备工作

## 1. 部署 Apache RocketMQ

部署 [Apache RocketMQ](http://rocketmq.apache.org/) 的 LiteTopic 版本(关于开源版本，预计在2月发布)，或购买支持 LiteTopic 的 RocketMQ 商业版实例，并创建以下资源：

- **1.1** 创建 接收响应请求的 LiteTopic：`WorkerAgentResponse`(SupervisorAgent用于接收响应结果)
- **1.2** 创建 与`WorkerAgentResponse` 绑定的Lite消费者ID：`CID_HOST_AGENT_LITE`(SupervisorAgent用于接收响应结果)
- **1.3** 创建 普通Topic：`LLM_TOPIC`(LLMAgent用于接收请求)
- **1.4** 创建 普通消费者ID：`LLM_CID`(LLMAgent用于接收请求)

## 2. 以下示例以阿里云百炼平台提供的Qwen大模型调用服务为例。欢迎社区开发者贡献更多来自其他厂商的集成案例。

1. 进入阿里云百炼平台

2. 创建对应的模型调用服务与Agent调用服务的apiKey

# 运行环境

- JDK 17 及以上
- [Maven](http://maven.apache.org/) 3.9 及以上

# 代码打包与示例运行

## 1. 编译打包

```shell
mvn clean package -Dmaven.test.skip=true -Dcheckstyle.skip=true
```
以下4个进程建议在分别在不同的窗口中运行

## 2. 基本参数介绍

| 参数名称  | 基本介绍                                          | 是否必填 |
|-------|-----------------------------------------------|------|
| rocketMQEndpoint | rocketmq服务接入点                                 | 是    |
| rocketMQNamespace | rocketmq命名空间                                  | 否    |
| bizTopic | 普通Topic，LLMAgent用于接收请求的Topic                  | 是    |
| bizConsumerGroup | 普通消费者CID，LLMAgent用于订阅普通Topic                  | 是    |
| rocketMQAK | rocketmq账号                                    | 否    |
| rocketMQSK | rocketmq密码                                    | 否    |
| apiKey | 百炼平台调用apiKey                                  | 是    |
| workAgentResponseTopic | LiteTopic，SupervisorAgent用于接收响应结果的Topic       | 是    |
| workAgentResponseGroupID | LiteConsumerCID，SupervisortAgent用于订阅LiteTopic | 是    |
| agentTopic | 普通Topic，LLMAgent接收请求的Topic                    | 是    |



## 3.运行LLMAagent
```shell
cd LLMAgent/target
```

```shell
java -DrocketMQEndpoint= -DrocketMQNamespace= -DrocketMQAK= -DrocketMQSK= -DbizTopic=LLM_TOPIC -DbizConsumerGroup=LLM_CID -DapiKey= -jar LLMAgent-2.1.1-SNAPSHOT.jar
```

## 4.运行SupervisorAgent-Web (进程1，监听9090端口)
```shell
cd SupervisorAgent-Web/target
```
```shell
java  -DrocketMQEndpoint= -DrocketMQNamespace= -DworkAgentResponseTopic=WorkerAgentResponse -DworkAgentResponseGroupID=CID_HOST_AGENT_LITE -DrocketMQAK= -DrocketMQSK= -DagentTopic=LLM_TOPIC -jar SupervisorAgent-Web-2.1.1-SNAPSHOT.jar --server.port=9090
```

## 5.运行SupervisorAgent-Web (进程2，监听9191端口)
```shell
cd SupervisorAgent-Web/target
```
```shell
java  -DrocketMQEndpoint= -DrocketMQNamespace= -DworkAgentResponseTopic=WorkerAgentResponse -DworkAgentResponseGroupID=CID_HOST_AGENT_LITE -DrocketMQAK= -DrocketMQSK= -DagentTopic=LLM_TOPIC -jar SupervisorAgent-Web-2.1.1-SNAPSHOT.jar --server.port=9191
```

## 6.运行SupervisorAgent-Web (进程3，监听9292端口)
```shell
cd SupervisorAgent-Web/target
```
```shell
java  -DrocketMQEndpoint= -DrocketMQNamespace= -DworkAgentResponseTopic=WorkerAgentResponse -DworkAgentResponseGroupID=CID_HOST_AGENT_LITE -DrocketMQAK= -DrocketMQSK= -DagentTopic=LLM_TOPIC -jar SupervisorAgent-Web-2.1.1-SNAPSHOT.jar --server.port=9292
```
- 打开浏览器，访问 localhost:9090
