# 通用安全与 Prompt 恢复

当 `ai.agent.registry.public-base-url` 设置为完整 HTTP(S) origin，AgentCard 地址使用该 origin，执行层全部 `/api/agents/**` 请求启用服务身份验证。`ai.agent.security.provider-code/allowed-client-ids/max-body-bytes` 由服务端配置；验签交给 user `AuthOpenApiRpcService.verifyRequiredOpenApiSignature` 强制签名契约（必须返回 true），由其维护密钥、时间戳和 nonce 重放策略。无凭据、篡改、非白名单或认证不可用均失败关闭。不信任正文 caller 或透传用户头。

主 `ai` 需配套启用 `ai.agent-client.auth.*`。空 public-base-url 继续兼容旧内网模式；这不代表匿名接口适合暴露到公网。

Actuator 仅 health 和探针子路径匿名可读，其余端点要求 `actuatorReq` 请求头匹配 `security.actuator.token`。缺少 token 配置时拒绝访问；运行期环境刷新可轮换 token。独立管理端口通过 `META-INF/spring/...ManagementContextConfiguration.imports` 注册相同保护。

Nacos AI Prompt 空订阅或 SDK 错误采用 30 秒退避重试；同一连接故障恢复期间可继续使用已成功读取的正文。namespace、地址或凭据变化属于新的订阅身份，禁止返回旧环境正文。启动后异步预读本制品现有 Prompt，仅记录可读状态，不发布新增业务 Prompt。

本批保留已有 Agent / Provider / Prompt 资源，不引入其他项目的医疗、医院、设备工具、问卷扩展、语音或报告 Agent。AgentSpec 自动发布基础设施依赖对应发布资源，本批不启用。

验证：`./gradlew test --no-daemon`、`./gradlew bootJar --no-daemon`、`bash scripts/check-secrets.sh`。签名、篡改与 Actuator 测试走真实本地 HTTP 端口，外部认证和模型使用合成桩；真实认证/Nacos联调另行验收。

GitHub 独立环境固定 `spring.ai.model.chat=google-genai`，三个环境 profile 均导入模型配置；业务演示 Prompt 同步、Prompt Registry 读取和 AgentCard 自动发布保持关闭。该环境已完成真实配置启动、health 返回 UP、匿名 Agent model 接口返回 401 的验证，临时进程已回收；未发模型推理或付费调用，不代表 AI 对话和完整业务链已验收。

交付验证执行完整 `./gradlew clean build --rerun-tasks --no-daemon`、凭据扫描与差异检查。公共库固定为仓库声明的精确版本；GitHub CI 从相应公共库的 main 构建 Maven Local。Git 提交、远端 CI、制品发布与实际部署分别以各自回执为准。
