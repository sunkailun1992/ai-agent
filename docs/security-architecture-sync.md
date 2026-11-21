# 通用安全与 Prompt 恢复

第5阶段起执行层全部 `/api/agents/**` 由 utils 的 Keycloak 资源服务器保护：只收 ai 经令牌交换取得的令牌（受众 `github-ai-agent`、`azp=github-ai`、RS256），用户取 `user_id`，活动租户经 user 服务的业务上下文核实，正文的用户与租户必须一致。旧的 HMAC 验签、`ai.agent.security.*` 与空 public-base-url 的旧内网免认证模式已删除；AgentCard 的公开地址仍是网关入口，网关的 Bearer 受众名单含 `github-ai-agent`。

Actuator 由 utils 的 `ActuatorSecurityConfig` 统一保护：health 匿名可读，其余端点要求 `actuatorReq` 请求头匹配 `security.actuator.token`，缺少 token 配置时拒绝，运行期环境刷新可轮换；独立管理端口由 Spring Boot 把同一条 Spring Security 过滤链挂进管理子容器。

Nacos AI Prompt 空订阅或 SDK 错误采用 30 秒退避重试；同一连接故障恢复期间可继续使用已成功读取的正文。namespace、地址或凭据变化属于新的订阅身份，禁止返回旧环境正文。启动后异步预读本制品现有 Prompt，仅记录可读状态，不发布新增业务 Prompt。

本批保留已有 Agent / Provider / Prompt 资源，不引入其他项目的医疗、医院、设备工具、语音或报告 Agent。AgentSpec 自动发布基础设施依赖对应发布资源，本批不启用。

验证：`./gradlew test --no-daemon`、`./gradlew bootJar --no-daemon`、`bash scripts/check-secrets.sh`。令牌（`AgentTokenAuthenticationTest` 用本机回环签发方真签 RS256）与 Actuator 测试走真实本地 HTTP 端口，user 服务与模型使用合成桩；真实 Keycloak/Nacos 联调另行验收。

GitHub 独立环境固定 `spring.ai.model.chat=google-genai`，三个环境 profile 均导入模型配置；业务演示 Prompt 同步、Prompt Registry 读取和 AgentCard 自动发布保持关闭。该环境已完成真实配置启动、health 返回 UP、匿名 Agent model 接口返回 401 的验证，临时进程已回收；未发模型推理或付费调用，不代表 AI 对话和完整业务链已验收。

交付验证执行完整 `./gradlew clean build --rerun-tasks --no-daemon`、凭据扫描与差异检查。公共库固定为仓库声明的精确版本；GitHub CI 从相应公共库的 main 构建 Maven Local。Git 提交、远端 CI、制品发布与实际部署分别以各自回执为准。
