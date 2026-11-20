# AI Agent 设计模式规范

## 总原则

- 升级、选型和架构设计先查证主流做法：对照 GitHub 上多个同类主流项目的源码与文档，以多数做法为准，不以单个项目为准；同时查官方文档、RFC 与标准、NIST / OWASP 等权威指南和相关论文，在设计或说明里写明依据与来源。不为缩小改动范围或省事采用非主流的简化做法；确需缩小范围时，写清主流做法与简化做法的差别和代价，由维护者决定。

## 推荐模式

- Application Service：每个 Agent Service 承担一个清晰执行用例。
- Provider：`AiChatClientProvider` 统一模型选择和 ChatClient 构建。
- Adapter：Prompt/Nacos 读取封装在独立服务中，业务 Agent 不直接处理 Nacos SDK 细节。
- DTO：Agent 入参和响应结构稳定、可测试。

## 禁止事项

- 不在 Controller 里直接调用模型。
- 不为当前少量 Agent 过早引入复杂路由、工作流或多智能体框架。
- 不把业务状态机、审计或持久化逻辑放入执行层。
