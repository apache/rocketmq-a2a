
# Quick Start

本案例展示了一个基于 [Apache RocketMQ](http://rocketmq.apache.org/) Lite 版本与 [Google ADK（Agent Development Kit）](https://github.com/google/adk-java) 集成的多 Agent 异步协同架构，通过消息中间件实现跨 Agent 的解耦通信与事件驱动交互，支持高并发、低延迟的分布式智能体协作场景。

## 基本的前期准备工作

### 1. 部署 Apache RocketMQ

部署 [Apache RocketMQ](http://rocketmq.apache.org/) 的 LiteTopic 版本，或购买支持 LiteTopic 的 RocketMQ 实例，并创建以下资源：

- **1.1** 创建 LiteTopic：`WorkerAgentResponse`
- **1.2** 为 `WorkerAgentResponse` 创建绑定的 Lite 消费者 ID：`CID_HOST_AGENT_LITE`
- **1.3** 创建天气助手普通 Topic：`WeatherAgentTask`
- **1.4** 创建天气助手普通消费者 ID：`WeatherAgentTaskConsumerGroup`
- **1.5** 创建行程规划助手普通 Topic：`TravelAgentTask`
- **1.6** 创建行程规划助手普通消费者 ID：`TravelAgentTaskConsumerGroup`

### 2. 部署大模型与 Agent 服务

部署大模型服务（或使用云平台提供的大模型与 Agent 调用服务），并实现以下功能的 Agent 服务：

- **2.1** 购买或实现一个大模型语义理解调用服务
- **2.2** 购买或实现一个具备天气查询功能的 Agent 服务
- **2.3** 购买或实现一个能根据天气信息制定行程规划的 Agent 服务

## 运行环境

- JDK 17 及以上
- [Maven](http://maven.apache.org/) 3.9 及以上

## 代码打包与示例运行

#### 1. 编译打包

```shell
mvn clean package -Dmaven.test.skip=true -Dcheckstyle.skip=true
```
以下三个Agent进程建议在分别在不同的窗口中运行

#### 2.运行WeatherAgent
```shell
cd WeatherAgent
```

```shell
MAVEN_OPTS="-DrocketMQEndpoint= -DrocketMQInstanceID= -DbizTopic=WeatherAgentTask -DbizConsumerGroup=WeatherAgentTaskConsumerGroup -DrocketMQAk= -DrocketMQSk= -DapiKey= -DappId= " mvn quarkus:dev
```
![img.png](img.png)

#### 3.运行TravelAgent
```shell
cd TravelAgent
```

```shell
 MAVEN_OPTS="-DrocketMQEndpoint= -DrocketMQInstanceID= -DbizTopic=TravelAgentTask -DbizConsumerGroup=TravelAgentTaskConsumerGroup -DrocketMQAk= -DrocketMQSk= -DapiKey= -DappId= " mvn quarkus:dev
```
![img_1.png](img_1.png)

#### 4.运行SupervisorAgent
```shell
cd SupervisorAgent/target
```
```shell
java -DrocketMQInstanceID= -DworkAgentResponseTopic=WorkerAgentResponse -DworkAgentResponseGroupID=CID_HOST_AGENT_LITE -DapiKey= -DweatherAgentTaskTopic=WeatherAgentTask -DtravelAgentTaskTopic=TravelAgentTask -DrocketMQAK= -DrocketMQSK= -jar SupervisorAgent-2.1.1-SNAPSHOT-jar-with-dependencies.jar 
```
![img_5.png](img_5.png)
