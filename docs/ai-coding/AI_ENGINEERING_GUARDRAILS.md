# AI Agent 工程门禁规范

通用交付按 `AGENTS.md` / `BRANCHING_SPEC.md`：完整功能与必需跨仓配套进入 main、远端核对后按授权清理源分支；test/preprod 验证、CI、制品与部署分开报告。Review 是可按需补充的检查，不作为已授权提交、合并或推送的人工批准门禁；安全关键改动仍须有独立风险验证。

## 必查项

- 是否仍是纯执行层，没有新增 DB/MQ/Seata/Redis/Qdrant 依赖？
- 是否通过 `AiChatClientProvider` 执行模型调用？
- 是否通过 Nacos 官方 `AiService` SDK 读取运行期 Prompt，而不是手写客户端 AI REST API？
- Prompt 启动自动发布是否保留版本、checksum、审计日志、覆盖策略和 `force-publish` 开关？
- 是否没有在 `ai-agent.yaml` 里写裸 IP，基础设施地址是否引用 `custom.infra-nacos-addr`？
- 是否没有把主 `ai` 服务的会话、记忆或审计职责搬进来？
- 是否没有把真实模型 Key 写入代码、测试资源或脚本默认值？
- Prompt/AgentCard 等资产内容、接口或边界变化时，是否独立维护版本、checksum、标签、失败策略和回滚证据，且没有默认开启发布？版本边界按 `VERSIONING_SPEC.md`，不把公司发布器或业务资产当作本仓已实现能力。
- 是否按风险执行测试；纯文档只做 diff、路径和凭据检查？

## 高风险变更

- 新增模型 Provider。
- 新增 Agent 类型。
- 引入 RAG、DB、MQ、异步任务或外部工具调用。
- 修改患者分析 Prompt 发布定义。
- 修改流式响应协议。
