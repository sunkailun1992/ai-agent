# AI Agent 目录管理规范

`ai-agent` 按轻量 Spring Boot 服务组织目录。

## 标准目录

```text
src/main/java/com/kellen/agent
├── AiAgentApplication.java
├── config
├── controller
├── dto
├── memory
├── security
└── service
```

## 规则

- 新增 Agent 能力放在 `service`，接口结构放在 `dto`。
- 不新增 `entity`、`repository`、`mapper`，除非该 Agent 已明确需要独立持久化。
- 不新增 Redis、MQ、Seata、Qdrant 等配置目录，除非代码已经真实依赖。
- Nacos 真实配置只维护在配置中心，不在仓库保存密钥模板。
- 测试放 `src/test/java/com/kellen/agent`，包名与被测类一致；`src/test/java/com/ai/agent` 下是早期测试，可原地维护。
