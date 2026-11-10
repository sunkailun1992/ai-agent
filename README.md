# ai-agent

`ai-agent` 是 AI 患者分析助手的模型执行层服务。它承载可拆分的 Spring AI Agent 能力，不保存用户会话、聊天记忆、审计数据或业务文件元数据。

主 `ai` 服务仍负责入口、鉴权、会话、审计、文件 token；`ai-agent` 只负责把输入交给模型并返回结果。

代码分支使用 `main`（正式主干）、`test`（整合测试）、`preprod`（候选预发）与短功能分支。分支不自动改变运行 profile；完整功能进入远端 main 并核对后，按本任务授权清理本地与远程功能分支，保护未合、其他 worktree 占用及长期分支。细则见 [分支管理规范](docs/ai-coding/BRANCHING_SPEC.md)。

## 本地 dev 启动

本地默认使用 `dev` profile。IDE 的 working directory、`./gradlew bootRun` 和 `java -jar build/libs/<服务名>.jar` 的当前目录必须是本服务仓库根目录，Spring 才会自动读取 `config/application-dev.properties`。该私有文件由本机入口生成、权限为 `0600`，仅保存 Nacos 连接的五个引导键；不放模型或数据库密钥，不提交 Git，也不修改 `application.yml`。从其它目录启动时，应显式设置 `spring.config.additional-location`，不能假设自动搜索会定位到服务仓库。

同名 OS 环境变量优先于私有文件；dev 地址按 `GITHUB_NACOS_DEV_SERVER_ADDR`、`GITHUB_NACOS_SERVER_ADDR` 依次读取，两者都未提供时拒绝缺失地址，不再回落到 `127.0.0.1:8848`。控制台使用 `18080`，客户端/SDK 使用 `8848`，SDK gRPC 连接使用 `9848`，不能把控制台端口当客户端地址。个人 namespace ID 保持 `github-dev`；远端业务配置仍从 Nacos 读取。单独激活 `test` 或 `prod` 不会读取这个 dev 私有文件，需提供其各自的连接配置。

## 当前接口

- `POST /api/agents/patient-analysis/stream`
- `GET /api/agents/model?provider=google|deepseek|qwen`

### SSE 执行结果与版本配对

患者分析流的成功 chunk 仍是纯文本 `data`。执行失败在流式响应中发送 `event:error`，
`data` 维持友好提示 `\n[AI Agent 调用失败] 请稍后重试`；不把原始供应商错误写入响应，
也不使用 `id` 承载错误码。业务 Service 保留失败信号，只有 HTTP Controller 把它转成错误事件；
模型／Agent 的 failure 观测不会被友好文本覆盖。输出部分文本后失败仍发送错误事件，
取消保持取消，不生成失败提示。

主 `ai` 请求携带 `X-AI-Stream-Protocol: typed-sse-v1`，本服务以相同响应头确认。
不支持的请求版本在模型执行前返回 HTTP 406。无该请求头的旧调用方仍可消费文本 `data`；
本服务同样返回协议确认头。服务 HMAC、终端 User JWT 和 Memory 租户校验保持原边界。

| AI 消费方 | Agent 执行方 | 行为与交付边界 |
|---|---|---|
| 新版 | 新版 | 读取错误事件；失败不写 success 审计或 assistant 记忆 |
| 新版 | 旧版 | 响应缺少协议确认即失败关闭，不把旧 fallback 文本判作成功 |
| 旧版 | 新版 | 仍能显示友好 `data`，旧 AI 无法读取错误类型，仍可能错误记录 success |

必须配套升级 AI 与 Agent 后再验收。新版 AI 先上线可防止旧协议被当成成功，但旧 Agent
仍可能在响应被拒绝前执行模型；协议拒绝不等于供应商从未执行。回滚也应按配对版本执行。

`PatientAnalysisErrorHttpTest` 使用真实 Spring HTTP、Controller、Service、ChatClient 与模型替身，
覆盖正常、执行失败、部分输出后失败及协议拒绝；`PatientAnalysisCancellationHttpTest`
继续验证活动模型 HTTP 取消。默认测试不调用真实模型。

## 当前依赖边界

保留：

- Spring Web / WebFlux
- Spring AI 2
- Spring Cloud Alibaba Nacos discovery/config
- Actuator、Micrometer Prometheus registry、OpenTelemetry API（SDK 由统一 Java agent 提供）

不保留：

- MyBatis-Plus / JDBC / MySQL
- Redis
- RabbitMQ
- Seata
- XXL-JOB
- Elasticsearch
- Qdrant

后续如果某个 Agent 真的需要 RAG、离线任务、消息队列或独立持久化，再在对应 Agent 服务中单独引入，不把所有基础设施预先塞进纯执行层。

## 生成参数来源

`0.3.1` 为 DeepSeek 和 Qwen 增加独立的显式配置：

```yaml
ai:
  agent:
    deepseek:
      generation-defaults-source: SERVICE
    qwen:
      generation-defaults-source: SERVICE
```

| 来源 | 实际请求行为 |
| --- | --- |
| `SERVICE`（未配置时的默认值） | 保留旧行为：下发服务绑定的 temperature、top-p 和输出 token 上限；DeepSeek 同时下发 thinking 与 reasoning effort。原输出上限最小 1024 的处理保持。 |
| `DEPLOYMENT` | 不下发上述生成参数，也不下发 provider thinking extra body；由 LiteLLM 原生 deployment 维护生成默认值。保留模型 alias、超时、SDK/options 的零重试与流式 usage 协议。 |

来源不由 URL 内容推断，未知枚举值会在配置绑定时拒绝。两个 provider 可分别迁移；Google 现有原生模型、输出与 thinking 选项不受此开关影响。应用请求 DTO 不新增任意模型 kwargs，不提供第二套模型管理 CRUD。

切到 `DEPLOYMENT` 前，必须先把当前有效的采样、输出上限与思考默认值按原语义配置到对应 LiteLLM deployment，并独立读回；该模式没有 Agent 本地输出硬上限。不能只切开关就假定网关已经保留原 8192 或其它运行值，也不能用源码默认值覆盖实际旧配置。

这些 ChatClient/ChatModel 在启动时构造，当前没有 `RefreshScope` 或模型客户端热重建入口。修改来源或 `SERVICE` 生成参数后需要受控重启并验证实际 wire；Nacos 的 `refreshEnabled` 不代表现有客户端已采用新选项。回滚时显式恢复 `SERVICE` 与原服务参数，再受控重启，不自动探测或回退。

`GenerationDefaultsWireHttpTest` 使用真实 Spring Boot 配置绑定、生产 Provider、Spring AI/OpenAI SDK 和自有回环 HTTP，验证字段缺席/旧值保留、流式 usage、未知枚举拒绝，以及同步/异步 503 只发送一次请求。SDK/options 的 `maxRetries=0` 不扩大为所有底层传输故障都不会连接恢复；现有 SDK 的连接行为保持。

## Nacos 配置

`ai-agent` 启动时从 `DEFAULT_GROUP` 加载：

- `logging.yml`
- `reuse-configuration.yaml`
- `traffic-governance.yaml`
- `actuator.yaml`
- `security-jwt.yaml`
- `swagger.yaml`
- `dubbo.yaml`
- `ai-agent-spring.yaml`
- `a2a.yaml`

当前 fleet 的服务业务配置和 `{svc}-spring.yaml` 已统一放在 `DEFAULT_GROUP`。

`0.3.0` 起 dev/test/prod 不再导入 `ai-model-secrets.yaml`。Google 的完整 `spring.ai.google.genai.api-key` 和 `chat.options.model` 由 `ai-agent-spring.yaml` 显式提供，代理模式使用 scoped virtual key，并在同一文件配置可选 `ai.agent.google.base-url`；不能只靠高优先级覆盖共享原始密钥。切换前须先完成服务配置的私有发布和读回，源码发布不代表运行配置已经迁移。

`ai-agent-spring.yaml` 的 Agent 参数片段：

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

`custom.infra-nacos-addr` 来自 `reuse-configuration.yaml`，不要在服务配置里重复写 NAS IP。

同一 `ai-agent-spring.yaml` 的 Google 配置片段：

```yaml
server:
  port: 7310

spring:
  ai:
    model:
      chat: google-genai
    google:
      genai:
        # 显式提供本服务凭据；代理使用 scoped virtual key，不使用管理 master key。
        api-key: ${GOOGLE_GENAI_API_KEY:}
        chat:
          options:
            # 必须匹配 key 授权的 native 物理模型，不依赖默认模型。
            model: ${GOOGLE_GENAI_CHAT_MODEL:}
            temperature: 0.3
ai:
  agent:
    google:
      # 空值使用 SDK 官方入口；网关前缀止于 /gemini，修改后需受控重启。
      base-url: ${GOOGLE_GENAI_BASE_URL:}
```

## Nacos AI Registry

Nacos AI Registry 是 Agent 能力目录和接口发现中心。主 `ai` 服务会通过 Nacos 官方 AI SDK 读取 AgentCard，再按 AgentCard 的 `supportedInterfaces` 调用本服务。

当前只登记患者分析 Agent：

- `patient-analysis-agent`
  - 服务地址：`http://<AI_AGENT_HOST>:7310/api/agents/patient-analysis/stream`
  - 传输协议：`HTTP+JSON`
  - 流式传输：开启
  - 技能：`patient-analysis`、`report-analysis`

`<AI_AGENT_HOST>` 必须从主 `ai` 服务运行环境可达。Docker 同网络可以使用
`ai-agent` 主机名；本地 Mac 联调时通常使用 `127.0.0.1` 或本机局域网 IP。

租户可沿用 `orb-ai`。不要把主服务入口 `/api/ai/chat` 填到 Agent 执行层。

## 本地验证

```bash
./gradlew test --no-daemon
```

通用安全、隔离与本批验证边界见 [安全与架构说明](docs/security-architecture-sync.md)。

## 指标与链路观测

管理端点使用既有运维令牌，模型 registry 与 SSE 终态观测、内部采集要求和回滚说明见 [监控采集与回滚](docs/fleet-observability.md)。

当前版本 `0.3.1` 精确消费 `com:fleet-observability:0.1.2`。共享 ECS 配置配套 `logging.structured.json.context.include=false`，固定成员 SPI 合并同名 MDC/KVP 后只写一次身份字段，保留实际 AI 用量和安全摘要，不输出自由 MDC、模型正文或原始异常。固定 `ai-agent.jar` 的 `bootJar` 把 `applicationVersion` 作为增量输入，构建后需读回真实 Manifest；IDEA 仍使用原生 Spring Boot Run/Debug，Gradle用于测试和打包。

本次精确对齐 `com:fleet-observability:0.1.2`，接入 Servlet ERROR 分派再次拒绝时的唯一 HTTP 终态修复；认证、响应和日志安全字段合同保持。源码与测试通过后，目标进程和追踪/日志联结仍须单独读回验收。

### 终端身份与 Memory 租户

网关模式的患者分析入口先要求允许的服务客户端通过原始请求 HMAC 验签，再通过 `com:rpc-api:1.0.8` 的 `AuthSessionRpcService` 独立验证 Authorization 中的终端 User access JWT。已验证 user/tenant 必须与签名正文一致，否则在模型/Memory 调用前拒绝。服务签名、`caller`、正文租户和 `sourceTenantId` 均不能独自建立用户授权；token 不放入正文、返回 DTO、日志或异常。

Servlet 入口捕获的不可变身份独立于线程状态传入 Memory adapter；每次 RPC 在实际执行线程绑定 canonical `tenantId` attachment，并在成功、失败时恢复旧值。`sourceTenantId` 只是业务来源分区，不覆盖授权租户。旧内网模式或没有可信身份的直接调用可执行不带 scoped Memory 的模型路径，不从 DTO 默认值恢复租户；候选记忆写入当前没有可信授权入口，因此失败关闭。

### SSE 取消与厂商 HTTP 释放

Spring AI 2.0.0 的 Google 同步 `ResponseStream` 会占用订阅线程，默认流适配未将取消绑定到 SDK HTTP 资源；OpenAI-compatible 的异步流也不能仅靠下游 Flux 取消来证明 HTTP 连接已关闭。`StreamingCancellationAdvisor` 在受限工作池订阅，并恢复本轮模型 trace；后续背压请求不排回阻塞的订阅线程，保证生成中的 SSE 可以持续写出。

每次订阅持有独立 HTTP Call 句柄。Google 使用官方 `ClientOptions.customHttpClient` 监听本轮同步 Call，认证模式、credentials-uri 和默认零超时保持。`0.2.9` 有意将 Google SDK 总尝试数改为 1、关闭其 OkHttp 连接失败重连，并只为 Google 模型设置上层零重试；不注册全局 RetryTemplate。OpenAI-compatible 通过请求级临时随机标记关联异步 Call，传输拦截器在出网前移除标记；原 options 和自定义头不被改写。正常完成、失败、取消和订阅装配失败均释放本轮句柄，不调用共享客户端的 `cancelAll`，也不关闭其他请求的客户端或连接池。成功读完或关闭的响应先解除 Call 绑定。

`PatientAnalysisCancellationHttpTest` 用真实 Spring Boot HTTP、Agent Controller/Service、ChatClient 和 Google/OpenAI SDK 连接回环持续 SSE 替身：读取首帧时上游仍在生成，关闭客户端后核对模型取消、厂商 HTTP 断开、唯一终态和父子 trace。取消的用量不标成最终值。另有共享客户端并发隔离、先取消后创建 Call、句柄清理、默认认证模式与超时回归。真实厂商链路仍须单独验收；Servlet 断连的识别发生在后续写出或容器通知时。

Google Developer API 可选 `ai.agent.google.base-url` 接入 native gateway；为空时使用 SDK 既有官方入口。代理 URI 应止于 `/gemini`，不带 `/v1beta` 或模型方法路径；现有 `spring.ai.google.genai.api-key` 可注入 scoped virtual key。参数归属、启动生效、版本与回滚边界见 [Google native gateway 接入](docs/google-native-gateway.md)。该客户端改造的自动化验证使用回环合成上游；实际配置发布、服务重启与真实 provider 验收必须另行取证，不能由源码或测试结果推定。

模型部署、上游凭据、virtual key 与调用治理统一由 LiteLLM 原生控制台维护，Agent 不复制一套模型管理 CRUD。应用只从私有 OS 环境读取本服务的最小权限 key；Nacos 保留服务连接与 `${GITHUB_*_API_KEY}` 占位符。尚未通过目标部署网络及 native 协议验收的 provider 可按私有迁移回执暂保直连，不得自动回退或将其写成已切流。配置归属与不同模型能力边界见 [fleet 模型控制面说明](https://github.com/sunkailun1992/utils/blob/main/ops/github-dev/ai-gateway/README.md)。
