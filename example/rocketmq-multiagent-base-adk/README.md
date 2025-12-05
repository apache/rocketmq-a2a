
## Quick Start
本案例展示了一个基于[Apache RocketMQ](http://rocketmq.apache.org/) Lite版本 与 [Google ADK（Agent Development Kit）](https://github.com/google/adk-java) 集成的多 Agent 异步协同架构，通过消息中间件实现跨 Agent 的解耦通信与事件驱动交互，支持高并发、低延迟的分布式智能体协作场景。

## 基本的前期准备工作
##
1.部署[Apache RocketMQ](http://rocketmq.apache.org/)的LiteTopic版本或者购买支持LiteTopic的RocketMQ实例 并创建如下资源
<br>
<br>
&nbsp;&nbsp;&nbsp;&nbsp;1.1创建 LiteTopic: WorkerAgentResponse
<br>
&nbsp;&nbsp;&nbsp;&nbsp;1.2创建 LiteTopic WorkerAgentResponse 绑定的Lite消费者ID: CID_HOST_AGENT_LITE
<br>
&nbsp;&nbsp;&nbsp;&nbsp;1.3创建 天气助手普通Topic: WeatherAgentTask
<br>
&nbsp;&nbsp;&nbsp;&nbsp;1.4创建 天气助手普通消费者ID: WeatherAgentTaskConsumerGroup
<br>
&nbsp;&nbsp;&nbsp;&nbsp;1.5创建 行程规划助手普通Topic: TravelAgentTask
<br>
&nbsp;&nbsp;&nbsp;&nbsp;1.6创建 行程规划助手普通消费者ID: TravelAgentTaskConsumerGroup
<br>
<br>
2.部署大模型服务或者购买云平台提供的大模型与Agent调用服务 并实现如下功能的Agent服务
<br>
<br>
&nbsp;&nbsp;&nbsp;&nbsp;2.1 购买或者实现 一个大模型语意理解调用服务
<br>
&nbsp;&nbsp;&nbsp;&nbsp;2.2 购买或者实现 一个具有查询天气查询功能的Agent服务
<br>
&nbsp;&nbsp;&nbsp;&nbsp;2.3 购买或者实现 一个具有按照天气信息指定行程规划功能的Agent的服务

## 运行环境
JDK 17 and above
<br>
[Maven](http://maven.apache.org/) 3.9 and above

## 代码打包与示例运行
1.编译打包
```shell
mvn clean package -Dmaven.test.skip=true -Dcheckstyle.skip=true
```
以下三个Agent进程建议在分别在不同的窗口中运行

2.运行WeatherAgent
```shell
cd WeatherAgent
```

```shell
MAVEN_OPTS="-DrocketMQEndpoint= -DrocketMQInstanceID= -DbizTopic=WeatherAgentTask -DbizConsumerGroup=WeatherAgentTaskConsumerGroup -DrocketMQAk= -DrocketMQSk= -DapiKey= -DappId= " mvn quarkus:dev
```
![img.png](img.png)

3.运行TravelAgent
```shell
cd TravelAgent
```

```shell
 MAVEN_OPTS="-DrocketMQEndpoint= -DrocketMQInstanceID= -DbizTopic=TravelAgentTask -DbizConsumerGroup=TravelAgentTaskConsumerGroup -DrocketMQAk= -DrocketMQSk= -DapiKey= -DappId= " mvn quarkus:dev
```
![img_1.png](img_1.png)

4.运行SupervisorAgent
```shell
cd SupervisorAgent/target
```
```shell
java -DrocketMQInstanceID= -DworkAgentResponseTopic=WorkerAgentResponse -DworkAgentResponseGroupID=CID_HOST_AGENT_LITE -DapiKey= -DweatherAgentTaskTopic=WeatherAgentTask -DtravelAgentTaskTopic=TravelAgentTask -DrocketMQAK= -DrocketMQSK= -jar SupervisorAgent-2.1.1-SNAPSHOT-jar-with-dependencies.jar 
```
![img_5.png](img_5.png)
