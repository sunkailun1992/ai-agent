# Google native gateway 客户端接入合同

版本：`ai-agent 0.3.0`；Google GenAI Java SDK `1.58.0`、Spring AI `2.0.0`。

`0.2.9` 增加可选传输入口并收紧 Google 重试；`0.3.0` 将 Google 凭据和模型选择统一归属服务配置，不再导入共享模型密钥文件。不改 Provider 白名单、业务 API、提示词、角色、附件权限或现有 typed SSE 协议。源码变更不代表已切流、读取真实 keys 或修改、发布 Nacos。Google 仍使用 native 协议，thinking、parts、function call 与 usage 结构由同一 Google SDK 处理。

## 参数与归属

配置归属为服务框架/Google native 传输，由 `ai-agent-spring.yaml` 完整提供；环境 `github-dev`、group `DEFAULT_GROUP`，仅 `ai-agent` 消费。dev/test/prod 已移除 `ai-model-secrets.yaml` import，其余 import 的顺序、optional 和 refreshEnabled 保持。这样通过网关调用时，本服务不会同时导入共享文件中的原始供应商密钥。以下仅说明占位合同，不能直接覆盖已有远端文件：

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
        # 必须在本服务配置中显式提供；空值不构成有效的 Developer API 连接。
        # 代理模式填本工作负载、环境、模型和路由限定的虚拟密钥；不使用管理 master key。
        api-key: ${GOOGLE_GENAI_API_KEY:}
        chat:
          options:
            # 显式填写与该 key 权限相符的 native 物理模型；不依赖 SDK 默认模型。
            model: ${GOOGLE_GENAI_CHAT_MODEL:}
```

Google SDK 拼接规则为 `baseUrl + / + apiVersion + / + models/{model}:方法`。因此代理前缀 `.../gemini` 会得到 `.../gemini/v1beta/models/{model}:generateContent` 或 `:streamGenerateContent?alt=sse`。末尾 `/` 被规范化；不自动删除 URI 中已经误填的 `/v1beta`。未配置 base URL 时，SDK 的原有官方入口/版本和认证选择保留；Vertex 模式仍支持原 project/location/credentials-uri，但配置代理前缀时在读取 credentials 之前拒绝。

`spring.ai.google.genai.chat.options.model` 保持既有模型字段，须与 `api-key` 一并在服务配置中明确提供；native pass-through 使用物理模型路径，不能假定管理台 alias 会重写它。Google 自动配置直接绑定 `spring.ai.google.genai.*`，不依赖共享 dataId 名称。Developer API 缺少 key 且未配置有效 Vertex project/location 时，生产 Client factory 启动失败；不能借测试 application.yml 或操作系统环境中的其它 key 证明迁移完成。现有 `ai.agent.google.thinking.*` 的来源、含义与默认值不变，业务提示词/角色继续由 Nacos Prompt Registry 管理。

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

`NacosModelConfigurationTest` 另外解析三个生产 profile，逐项核对移除共享密钥后的九项 import 顺序；仅加载合成 `ai-agent-spring.yaml` 到真实 Google 自动配置，排除系统属性、环境变量及测试 application.yml，断言实际 Client key、模型和代理/默认官方 base URL。服务 key 为空的启动负例失败关闭，不发送模型请求。这是本地配置与自动配置合同，不冒充真实 Nacos 加载或运行态切流验收。

## 迁移与回滚

启动 `0.3.0` 制品前，先由负责人对目标 Nacos 做 fresh readback/CAS，确认 `ai-agent-spring.yaml` 已完整提供 key、匹配 native 路径的模型和可选 base URL；不能依赖待移除的共享文件或只覆盖其中一个字段。之后受控重启目标服务，核版本、有效配置归属、健康、一次请求、取消与日志。仓库构建不执行这些运行态步骤，也不删除其它消费者可能仍需的共享 dataId。

需要回到官方入口时，在同一 `ai-agent-spring.yaml` 中恢复原先授权的 provider 配置/模型/官方 key，再将 base URL 置空并重启；不需要恢复共享文件 import。不能只删除代理 URL 却保留网关虚拟密钥，然后声称官方调用可用。原凭据与快照仅由负责人在私有配置中持有，本次不读取、搬运或写入文档。若回退至 `0.2.9` 或更早制品，会重新导入共享模型密钥文件，须重新核对配置来源；`0.2.8` 还会恢复旧的 Google SDK/上层重试默认，需一并记录行为差异。
