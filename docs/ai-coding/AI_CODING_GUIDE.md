# AI Agent 编码规范

## 通用修改入口

先按根 `AGENTS.md`、`BRANCHING_SPEC.md` 核对仓库、WIP、最新 main 与任务授权；版本按 `VERSIONING_SPEC.md` 做进位和占用检查。main/test/preprod 的集成证据、远端核对、短分支清理与 worktree 保护分别记录；纯文档不重复跑 Java 测试或额外提升制品版本。

`ai-agent` 是 AI 患者分析助手的模型执行层服务。它只负责模型调用、Prompt 选择和结构化输出转换；业务状态仍由主 `ai` 服务负责。

## 1. 项目定位

当前能力：

- `patient-analysis-agent`：患者分析和报告解读流式输出。
- `questionnaire-agent`：问卷自然提问和答案解释。
- 使用 Spring AI 2 接入模型。
- 使用 Nacos 配置中心加载模型参数；Prompt 正文由 Nacos AI Prompt Registry 管理。
- 使用 Nacos 服务发现注册 `ai-agent` 实例。

当前不做：

- 不保存用户会话、聊天记忆、问卷状态或审计数据。
- 不直接写业务库。
- 不新增业务表、DDL、Entity、Repository 或 Mapper；确需持久化时先拆到主 `ai`、`rag` 或独立业务服务，并按目标服务表设计规范执行。
- 不引入 DB、ORM、MQ、Seata、Redis、ES、Qdrant 等基础设施。
- 不替代 Nacos AI Registry；Registry 由 Nacos 管理，主 `ai` 服务消费 AgentCard。

## 2. 配置

本地 `application.yml` 只导入：

- `logging.yml`
- `reuse-configuration.yaml`
- `ai-agent.yaml`
- `ai-agent-spring.yaml`

当前 fleet 的服务业务配置和 `{svc}-spring.yaml` 统一使用 `group=DEFAULT_GROUP`。

真实模型 Key 必须来自 Nacos 或环境变量，不得提交到仓库。
当前 Provider 允许值为 `google`、`deepseek`、`qwen`。`qwen` 通过 DashScope
OpenAI-compatible 接口接入，密钥放 `ai.agent.qwen.api-key` 或对应环境变量。

`ai-agent.yaml` 中的 Nacos AI Prompt Registry 地址必须引用
`reuse-configuration.yaml` 的 `${custom.infra-nacos-addr}`，不得重复写
NAS IP。Prompt 运行期读取必须使用 Nacos 官方 `AiService.subscribePrompt`
订阅，不能手写 `/v3/client/ai/prompt` 这类客户端 REST 路径，也不能回退到
本地 YAML Prompt 正文。启动自动发布使用 Nacos Prompt Admin API，必须保留版本、
checksum、审计日志、覆盖策略和 `force-publish` 开关。

## 3. 分层

- `controller`：Agent HTTP 接口。
- `service`：模型 Provider、Prompt 读取、患者分析、问卷执行。
- `dto`：Agent 请求/响应结构。
- `config`：Agent 和 Prompt 配置绑定。

新增 Agent 时优先新增清晰的 Service 和 DTO；当某个 Agent 变大或依赖独立基础设施，再拆成独立服务。

## 4. 模型调用

- 模型调用统一经过 `AiChatClientProvider`。
- Controller 不直接注入 `ChatClient` 或厂商 SDK。
- `provider` 请求参数只能作为受控覆盖；默认使用 `ai.agent.provider`。
- Agent 不保留 Prompt 正文本地兜底；注册中心不可读时按业务降级或失败处理，但不能悄悄使用过期本地 Prompt。

## 5. 安全

- 患者分析输出不能替代医生诊断、治疗、用药或急救判断。
- 输入附件 URL 默认不可信，Agent 只接受主服务已授权、已校验过的 URL。
- 日志不得输出模型 Key、Secret、完整用户隐私或完整媒体 URL。
- 异常响应不得泄露内部堆栈、密钥或底层服务地址。

## 6. 验证

```bash
./gradlew test --no-daemon
```
