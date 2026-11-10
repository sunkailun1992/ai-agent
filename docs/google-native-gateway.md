# Google native gateway 客户端接入合同

版本：`ai-agent 0.2.9`；Google GenAI Java SDK `1.58.0`、Spring AI `2.0.0`。

本次只增加可选传输入口并收紧 Google 重试，不改 Provider 白名单、业务 API、提示词、角色、附件权限或现有 typed SSE 协议。不切流、不读取真实 keys、不修改或发布 Nacos。没有把 Google 改为 OpenAI-compatible，因此 native thinking、parts、function call 与 usage 结构仍由同一 Google SDK 处理。

## 参数与归属

配置归属为服务框架/Google native 传输，拟由 `ai-agent-spring.yaml` 管理；环境 `github-dev`、group `DEFAULT_GROUP`，仅 `ai-agent` 消费。共享模型密钥已有 dataId 的来源保持，不移动、不复制凭据或更改 import。以下仅说明占位合同，不能直接覆盖已有远端文件：

```yaml
# dataId: ai-agent-spring.yaml；分类：服务框架/Google native 传输。
# 环境：github-dev / DEFAULT_GROUP；消费者：ai-agent。
# 保留原有文件其它字段和 imports，实际发布须主会话 fresh readback/CAS。
ai:
  agent:
    google:
      # 可选绝对 HTTP(S) URI，无单位；不允许 user-info、query、fragment。
      # 空值保留 SDK 默认入口；代理前缀应止于 /gemini，不带 /v1beta 或模型方法。
      # 仅 Developer API/API key 模式可用，不能把 Vertex OAuth credentials 发给代理。
      # Client 是启动时单例；修改该值后需受控重启 ai-agent，不宣称 Nacos 刷新自动重建客户端。
      base-url: ${GOOGLE_GENAI_BASE_URL:}
spring:
  ai:
    google:
      genai:
        # 现有字段；敏感值由已有私有配置注入，不增加新的密钥槽位。
        # 代理模式填本工作负载、环境、模型和路由限定的虚拟密钥；不使用管理 master key。
        api-key: ${GOOGLE_GENAI_API_KEY:}
```

Google SDK 拼接规则为 `baseUrl + / + apiVersion + / + models/{model}:方法`。因此代理前缀 `.../gemini` 会得到 `.../gemini/v1beta/models/{model}:generateContent` 或 `:streamGenerateContent?alt=sse`。末尾 `/` 被规范化；不自动删除 URI 中已经误填的 `/v1beta`。未配置 base URL 时，SDK 的原有官方入口/版本和认证选择保留；Vertex 模式仍支持原 project/location/credentials-uri，但配置代理前缀时在读取 credentials 之前拒绝。

`spring.ai.google.genai.chat.options.model` 保持既有模型字段；native pass-through 使用物理模型路径，不能假定管理台 alias 会重写它。现有 `ai.agent.google.thinking.*` 的来源、含义与默认值不变，业务提示词/角色继续由 Nacos Prompt Registry 管理。

## 一次尝试与取消

这是有意的行为变化：SDK 1.58.0 未指定 retry options 时默认最多 5 次总尝试，本版本显式 `HttpRetryOptions.attempts(1)`，即首轮一次、自动重试零次；Google 专用 OkHttp 关闭连接失败重连。Spring AI 同步 Google 模型的 `RetryTemplate` 也设置 `maxRetries(0)`，不注册全局重试模板，不改变其他 Provider 的模型或业务重试策略。请求级任意 native HttpOptions 仍不是业务 API 支持的输入。

Google 模型 factory 保留官方 options、ToolCallingManager、ObservationRegistry、observation convention 和 cached-content 开关；使用独立 bean 名避免官方 cached-content 条件在属性绑定前强行创建模型。业务仍按类型注入 GoogleGenAiChatModel/ChatClient。没有新增全局 BeanPostProcessor、反射改写或重试拦截框架。

连接/读/写超时仍为当前生产 custom HTTP transport 的零值（毫秒，沿用 SDK 默认无单项超时）。既有业务 deadline 与每轮 `StreamingCallCancellation` 继续负责取消；没有新增无限后台重跑。取消只关闭本轮 Call，不取消共享客户端或其它请求。

## 回环验收与边界

`GoogleNativeTransportHttpTest` 使用真实生产自动配置、Google SDK 与本机动态端口 HTTP 上游；在任何请求前先断言 SDK 实际 baseUrl 为该回环前缀，避免未生效的配置误打外部。覆盖：

- Spring 配置绑定和带/不带末尾斜线的前缀；generate/stream 的真实路径与 `x-goog-api-key` 合成虚拟密钥头。
- 请求的 native thinkingConfig、tools/functionDeclarations，响应 thought parts、functionCall、usageMetadata；不把 native 换成 OpenAI JSON。
- SDK generate 遇 503 一次上游；Spring Google 模型同步调用遇 503 也只有一次上游。
- 默认入口、默认超时、非法/带凭据 URI 拒绝，Vertex 代理请求在 credentials 读取前失败。

`PatientAnalysisCancellationHttpTest` 的 Google 用例改用本次生产 Client factory 和 `/proxy/gemini` 前缀，仍走真实 Spring Boot HTTP→Controller/Service→ChatClient→Google SDK；读到持续 SSE 后关闭客户端，验证上游断开、唯一取消、trace/用量状态与一次上游请求。OpenAI-compatible 用例保留。

这些测试只用合成数据和 loopback HTTP，不调用外部厂商、不读取 Nacos、真实媒体或凭据。实际 LiteLLM 容器、虚拟密钥权限、日志/OTLP 隐私、供应商协议与真实模型语义仍由主会话按阶段独立验收，Java 构建通过不代替切流验收。

## 迁移与回滚

先发布确定 Java 制品，再由主会话对目标 Nacos 做 fresh readback/CAS，将 base URL、匹配 native 路径的模型和 scoped key 作为同一受控配置变更，受控重启目标服务后核版本、健康、一次请求、取消与日志。当前未做该步骤。

需要回到官方入口时，恢复原先授权的 provider 配置/模型/key，再将 base URL 置空并重启；不能只删除代理 URL 却保留网关虚拟密钥，然后声称官方调用可用。原凭据与快照仅由负责人在私有配置中持有，本次不读取、搬运或写入文档。若回退 Java 制品，`0.2.8` 会恢复旧的 Google SDK/上层重试默认，需一并记录行为差异。
