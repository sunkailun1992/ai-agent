# AI Agent 工程门禁规范

## 必查项

- 是否仍是纯执行层，没有新增 DB/MQ/Seata/Redis/Qdrant 依赖？
- 是否通过 `AiChatClientProvider` 执行模型调用？
- 是否通过 Nacos 官方 `AiService.subscribePrompt` 订阅运行期 Prompt，而不是手写客户端 AI REST API？
- Prompt 启动自动发布是否保留版本、checksum、审计日志、覆盖策略和 `force-publish` 开关？
- 是否没有在 `ai-agent.yaml` 里写裸 IP，基础设施地址是否引用 `custom.infra-nacos-addr`？
- 是否没有把主 `ai` 服务的会话、记忆、问卷状态或审计职责搬进来？
- 是否没有把真实模型 Key 写入代码、测试资源或脚本默认值？
- 是否执行 `./gradlew test --no-daemon`？

## 高风险变更

- 新增模型 Provider。
- 新增 Agent 类型。
- 引入 RAG、DB、MQ、异步任务或外部工具调用。
- 修改患者分析或问卷解释 Prompt 发布定义。
- 修改流式响应协议。
