# ai-agent

`ai-agent` 是 AI 患者分析助手的模型执行层服务。它承载可拆分的 Spring AI Agent 能力，不保存用户会话、聊天记忆、问卷状态、审计数据或业务文件元数据。

主 `ai` 服务仍负责入口、鉴权、会话、审计、文件 token、问卷模板和问卷状态机；`ai-agent` 只负责把输入交给模型并返回结果。

## 当前接口

- `POST /api/agents/patient-analysis/stream`
- `POST /api/agents/questionnaire/reply`
- `POST /api/agents/questionnaire/interpret`
- `GET /api/agents/model?provider=google|deepseek|qwen`

## 当前依赖边界

保留：

- Spring Web / WebFlux
- Spring AI 2
- Spring Cloud Alibaba Nacos discovery/config
- Actuator

不保留：

- MyBatis-Plus / JDBC / MySQL
- Redis
- RabbitMQ
- Seata
- XXL-JOB
- Elasticsearch
- Qdrant

后续如果某个 Agent 真的需要 RAG、离线任务、消息队列或独立持久化，再在对应 Agent 服务中单独引入，不把所有基础设施预先塞进纯执行层。

## Nacos 配置

`ai-agent` 启动时从 `DEFAULT_GROUP` 加载：

- `logging.yml`
- `reuse-configuration.yaml`
- `ai-agent.yaml`
- `ai-agent-spring.yaml`

当前 fleet 的服务业务配置和 `{svc}-spring.yaml` 已统一放在 `DEFAULT_GROUP`。

`ai-agent.yaml` 示例：

```yaml
ai:
  agent:
    provider: ${AI_PROVIDER:google}
    qwen:
      base-url: ${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      api-key: ${QWEN_API_KEY:${DASHSCOPE_API_KEY:}}
      model: ${QWEN_CHAT_MODEL:qwen-plus}
      temperature: 0.3
      top-p: 0.8
      max-tokens: 8192
      timeout-seconds: 180
  prompts:
    nacos:
      # Prompt 运行期只从 Nacos AI Prompt Registry 读取；Prompt 正文不再走本地兜底。
      enabled: true
      server-addr: ${custom.infra-nacos-addr}
      namespace: ${custom.namespace}
      username: ${custom.nacos-username:}
      password: ${custom.nacos-password:}
      context-path: ${custom.nacos-context-path:/nacos}
      label: stable
      refresh-interval-millis: 10000
    sync:
      # 启动时按 Nacos Prompt Admin API 字段把 classpath:/prompts/*.prompt.json 发布到 Nacos。
      enabled: true
      dry-run: false
      fail-on-error: false
      publish-new-version: true
      # Nacos 开启 Prompt Pipeline 时，启动自动发布必须使用官方 force-publish；如需人工审批可改为 false。
      force-publish: true
```

`custom.infra-nacos-addr` 来自 `reuse-configuration.yaml`，不要在 `ai-agent.yaml` 里重复写 NAS IP。

`ai-agent-spring.yaml` 示例：

```yaml
server:
  port: 7310

spring:
  ai:
    model:
      chat: google-genai
    google:
      genai:
        api-key: ${GOOGLE_GENAI_API_KEY:}
        chat:
          options:
            model: ${GOOGLE_GENAI_CHAT_MODEL:gemini-2.5-flash}
            temperature: 0.3
```

## Nacos AI Registry

Nacos AI Registry 是 Agent 能力目录和接口发现中心。主 `ai` 服务会通过 Nacos 官方 AI SDK 读取 AgentCard，再按 AgentCard 的 `supportedInterfaces` 调用本服务。

当前需要登记两个 Agent：

- `patient-analysis-agent`
  - 服务地址：`http://<AI_AGENT_HOST>:7310/api/agents/patient-analysis/stream`
  - 传输协议：`HTTP+JSON`
  - 流式传输：开启
  - 技能：`patient-analysis`、`report-analysis`
- `questionnaire-agent`
  - 服务地址 1：`http://<AI_AGENT_HOST>:7310/api/agents/questionnaire/reply`
  - 服务地址 2：`http://<AI_AGENT_HOST>:7310/api/agents/questionnaire/interpret`
  - 传输协议：`HTTP+JSON`
  - 流式传输：关闭
  - 技能：`conversational-questionnaire`、`answer-interpretation`

`<AI_AGENT_HOST>` 必须从主 `ai` 服务运行环境可达。Docker 同网络可以使用
`ai-agent` 主机名；本地 Mac 联调时通常使用 `127.0.0.1` 或本机局域网 IP。

租户可沿用 `orb-ai`。不要把主服务入口 `/api/ai/chat`、`/api/ai/questionnaire/chat` 填到 Agent 执行层。

## 本地验证

```bash
./gradlew test --no-daemon
```

通用安全、隔离与本批验证边界见 [安全与架构说明](docs/security-architecture-sync.md)。
