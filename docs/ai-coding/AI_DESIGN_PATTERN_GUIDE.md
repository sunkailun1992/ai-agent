# AI Agent 设计模式规范

## 推荐模式

- Application Service：每个 Agent Service 承担一个清晰执行用例。
- Provider：`AiChatClientProvider` 统一模型选择和 ChatClient 构建。
- Adapter：Prompt/Nacos 读取封装在独立服务中，业务 Agent 不直接处理 Nacos SDK 细节。
- DTO：Agent 入参和响应结构稳定、可测试。

## 禁止事项

- 不在 Controller 里直接调用模型。
- 不为当前少量 Agent 过早引入复杂路由、工作流或多智能体框架。
- 不把业务状态机、审计或持久化逻辑放入执行层。
