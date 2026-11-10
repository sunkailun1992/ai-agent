# AI Agent 编码规范入口

涉及 Nacos 配置、模板、引导或渲染/发布工具时，必须同时阅读 [Nacos 配置注释与归类规范](NACOS_COMMENT_SPEC.md)。

本目录维护 `ai-agent` 执行层服务的 AI 编程规范。`archive/` 和 `examples/` 是通用规范模板参考；当前服务是否采用其中数据库、缓存或 MQ 示例，必须以本项目真实依赖为准。

## 通用规则入口

先核对 `BRANCHING_SPEC.md` 的 main/test/preprod、完整功能交付与清理保护，再按 `VERSIONING_SPEC.md` 做版本进位和已用制品核对；安全关键改动须有独立风险验证，Review 是补充。纯规范变更不提升业务版本、不机械重跑 Java 测试。

## 阅读顺序

1. 根目录 `AGENTS.md`
2. 根目录 `README.md`
3. `AI_CODING_GUIDE.md`
4. `AI_DIRECTORY_STRUCTURE_GUIDE.md`
5. `AI_COMMENT_STYLE_GUIDE.md`
6. `AI_DESIGN_PATTERN_GUIDE.md`
7. `AI_ENGINEERING_GUARDRAILS.md`
8. `BRANCHING_SPEC.md`
9. `ENVIRONMENT_CONFIG_SPEC.md`
10. `VERSIONING_SPEC.md`
11. `RPC_API_CODING_SPEC.md`
12. `TESTING_SPEC.md`
13. `SECURITY_CODING_SPEC.md`
14. 目标 Controller、Service、DTO、配置或测试

## 必读结论

- `ai-agent` 是模型执行层，不保存业务会话、聊天记忆、审计数据或文件元数据。
- 当前保留 Spring AI、Nacos discovery/config、Web/WebFlux 和 Actuator。
- 当前不引入 MySQL、MyBatis-Plus、Redis、RabbitMQ、Seata、XXL-JOB、Elasticsearch 或 Qdrant。
- 主调用链保持 `AgentController -> Agent Service -> AiChatClientProvider`。
- 模型 Key 和 Provider 配置只放 Nacos 配置中心或环境变量。
- 分支命名、短分支生命周期、release/hotfix、tag 和分支清理按 `BRANCHING_SPEC.md` 处理。
- 环境、Nacos namespace、Java profile 和前端客户端边界按 `ENVIRONMENT_CONFIG_SPEC.md` 处理。
- 项目版本、公共包依赖和后端 Java 新项目基础坐标按 `VERSIONING_SPEC.md` 处理；纯 AI 规范或 README 改动不提升制品版本。
- Dubbo RPC 契约统一维护在同级 `../rpc-api`；`ai-agent` 通过契约暴露或消费 Agent 能力，不在服务内复制接口/DTO。
- 测试按 `TESTING_SPEC.md` 分层；核心接口用真实 Spring Boot HTTP 集成测试，核心 Agent 编排用真实 Spring Bean 测试，模型供应商默认 fake/stub。
