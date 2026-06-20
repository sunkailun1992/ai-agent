# AI Agent 编码规范入口

本目录维护 `ai-agent` 执行层服务的 AI 编程规范。`archive/` 和 `examples/` 是通用规范模板参考；当前服务是否采用其中数据库、缓存或 MQ 示例，必须以本项目真实依赖为准。

## 阅读顺序

1. 根目录 `AGENTS.md`
2. 根目录 `README.md`
3. `AI_CODING_GUIDE.md`
4. `AI_DIRECTORY_STRUCTURE_GUIDE.md`
5. `AI_COMMENT_STYLE_GUIDE.md`
6. `AI_DESIGN_PATTERN_GUIDE.md`
7. `AI_ENGINEERING_GUARDRAILS.md`
8. `SECURITY_CODING_SPEC.md`
9. 目标 Controller、Service、DTO、配置或测试

## 必读结论

- `ai-agent` 是模型执行层，不保存业务会话、聊天记忆、问卷状态、审计数据或文件元数据。
- 当前保留 Spring AI、Nacos discovery/config、Web/WebFlux 和 Actuator。
- 当前不引入 MySQL、MyBatis-Plus、Redis、RabbitMQ、Seata、XXL-JOB、Elasticsearch 或 Qdrant。
- 主调用链保持 `AgentController -> Agent Service -> AiChatClientProvider`。
- 模型 Key 和 Provider 配置只放 Nacos 配置中心或环境变量。
