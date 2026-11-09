# AGENTS.md

本文件是 `ai-agent` 项目的 AI 编码入口。AI 修改本项目代码前，必须先阅读本文件，再按任务风险阅读对应规范和真实代码。

## Nacos 配置注释要求

- 新增或修改 Nacos 配置、模板、引导配置及渲染/发布工具前，必须阅读 [Nacos 注释与归类规范](docs/ai-coding/NACOS_COMMENT_SPEC.md)。
- 每个 dataId 写明职责、环境归属、消费者；每个可调参数写明用途、必要单位、格式、空值及开关影响。YAML 使用 `#` 注释，渲染结果必须保留说明。
- 注释整理保持键、类型、值、占位符和列表/import 顺序不变；发布前读回权威配置并备份，使用旧版本比对/CAS，发布后逐项读回。真实凭据不得进入 Git 或注释。
- 只处理明确授权的仓库及环境；Yudao 项目排除，不随本规范同步修改。

## 通用工程与 Git 交付约束

- 当前长期分支为 `main`、`test`、`preprod`，分别承担正式主干、整合测试和候选预发；它们不自动决定 profile/namespace。个人 dev 入口、`github-dev` 与公共库 `com` 坐标保持，不能复制公司环境或业务分支规则覆盖。
- 修改前先核仓库、HEAD、dirty、worktree 和其他会话，再 fetch；普通新目标从最新 origin/main 建短分支，同一任务续作和用户明确基线按现有授权处理。完整功能与必需跨仓配套一起验证交付。
- 本任务已授权合 main 并清理时，完整功能进入远端 main 且包含关系读回通过后清理本地和远程功能分支；只进 test/preprod、未合、被其他 worktree 使用或仍有唯一工作时保留。保护三个长期分支，不强推，不用强删绕过检查。细则见 `docs/ai-coding/BRANCHING_SPEC.md`。
- 高风险变更必须有独立风险验证，不能只依赖实现者的唯一自证测试；Review 可按需补充，不增加已授权提交/合并/推送的人工批准门禁。纯文档运行差异、路径和凭据检查，不机械重跑 Java 测试。
- 修改版本前读 `docs/ai-coding/VERSIONING_SPEC.md`：MINOR/PATCH 各为 0–9，`1.2.9 -> 1.3.0`、`1.9.9 -> 2.0.0`；先核远端、tag、在途分支和已发布制品占用，同批只升一次，纯规范不升业务版本，不覆盖旧 tag/同版制品。
- Git 集成、CI、远端制品、部署和实际验收分别取证；当前任务已给出的授权持续有效，未覆盖的外部发布动作仍需明确授权。

## 项目定位

- 项目名称：`ai-agent`
- 项目类型：AI Agent 执行层服务
- 技术栈：Java 17、Spring Boot 4、Spring AI 2、Spring Cloud Alibaba、Nacos、Dubbo、Gradle Wrapper、`com:rpc-api`
- 外部模型：Google Gemini、DeepSeek/OpenAI-compatible、通义千问 DashScope/OpenAI-compatible
- 核心职责：承载可拆分的 Spring AI Agent 执行逻辑，对外提供患者分析、问卷自然追问、问卷答案解释等 Agent 接口
- 核心风险：模型 Provider 选错、Prompt 污染、外部媒体 URL SSRF、密钥泄露、患者资料越权、日志输出用户隐私、把主 `ai` 服务的会话和问卷状态误搬到 Agent 层

## 修改前阅读顺序

1. `docs/ai-coding/README.md`
2. `docs/ai-coding/AI_CODING_GUIDE.md`
3. `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md`
4. `docs/ai-coding/AI_COMMENT_STYLE_GUIDE.md`
5. `docs/ai-coding/AI_DESIGN_PATTERN_GUIDE.md`
6. `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md`
7. `docs/ai-coding/BRANCHING_SPEC.md`：确认分支命名、短分支生命周期、release/hotfix、tag 和清理规则。
8. `docs/ai-coding/ENVIRONMENT_CONFIG_SPEC.md`：确认环境、Nacos namespace、Java profile 和前端/小程序边界。
9. `docs/ai-coding/VERSIONING_SPEC.md`：确认 `group = 'com'`、`version = '1.0.0'`、补丁递增和消费者同步规则。
10. `docs/ai-coding/RPC_API_CODING_SPEC.md`：涉及 Dubbo RPC provider、consumer、接口或 DTO 时必须阅读。
11. `docs/ai-coding/TESTING_SPEC.md`：确认业务模块 SpringBootTest、真实 HTTP 集成测试、模型 fake/stub 和 AssertJ 边界。
12. `docs/ai-coding/SECURITY_CODING_SPEC.md`
13. `docs/ai-coding/NACOS_CONFIG_SPEC.md`：修改 Nacos 配置中心、共享 dataId 或 `application.yml` import 前必读。
14. 目标 Controller、Service、DTO、配置或测试

## 项目边界

- `ai-agent` 是执行层，不是用户入口层；小程序、App、管理端仍通过主 `ai` 服务或网关访问。
- 主 `ai` 服务负责入口、鉴权、会话、审计、文件 token、普通聊天记忆、问卷模板、问卷会话、问卷答案落库和 outbox。
- `ai-agent` 不直接写 `ai_chat_memory`、`ai_chat_message`、问卷模板、问卷会话、问卷答案、文件对象或用户登录表。
- Agent 只能返回模型执行结果、结构化候选结果或流式文本；是否保存、推进状态、切换会话、推送外部系统由主服务控制。
- 模型选择必须通过 `AiChatClientProvider`，业务代码不得直接绑定某个模型供应商。
- 当前 Provider 允许值为 `google` / `deepseek` / `qwen`；新增 Provider 必须补齐白名单、配置、文档、测试和回滚说明。
- Prompt 优先通过 Nacos AI Prompt 管理或 `ai.prompts.*` 配置读取；代码中的长 Prompt 只能作为兜底，不能成为运营主数据。
- 测试分层按 `docs/ai-coding/TESTING_SPEC.md` 执行；核心 Agent 链路不能只靠 mock 或纯对象 `assertThat`，必须补 Spring Boot 级别测试。
- 问卷 Agent 必须无状态执行：只理解当前题、已采集答案摘要和本轮输入，不能保存问卷状态或跳题。
- 患者分析 Agent 必须保留医疗安全边界，不能替代医生诊断、治疗、用药或急救判断。
- 多模态附件 URL 默认不可信，必须限制协议、MIME、远端 Content-Type、数量和大小；不得支持本地文件路径、classpath、ftp、jar 等协议。
- `application.yml` 只保留本地启动和 Nacos import 入口；真实模型密钥、生产地址和数据库密码必须放在 Nacos。
- `ai-agent-spring.yaml` 位于当前全局 Nacos 配置 group `DEFAULT_GROUP`；不要再新增 `group=test` 的服务配置引用。
- 当前 `ai-agent` 不直接维护业务库 DDL；如果后续接入 PostgreSQL、MyBatis-Plus DDL 与 Seata AT，首次启动空库前必须先在目标业务库手动执行 `../utils/src/main/resources/db/postgresql/common-infra-schema.sql`，不能依赖应用首次启动自动创建 `undo_log`。
- 新增本服务对外 Controller、服务前缀，或新增后续独立 Agent 服务需要经网关访问时，必须同步评估 `../gateway` 的 Nacos `gateway-spring.yaml`；需要 Swagger UI 聚合的服务要先保证对应网关文档路径返回 200，再加入 `springdoc.swagger-ui.urls`。
- 跨服务 Dubbo RPC 接口和 DTO 统一维护在同级 `../rpc-api`；`ai-agent` 只实现或调用契约，不在本服务或 `utils` 复制接口定义。
- 本项目不得引入交易、K 线、下单、账户、行情、Discord、Apple 订阅等量化业务能力。

## 验证命令

```bash
./gradlew -q compileJava
./gradlew test
./gradlew clean build
bash scripts/check-secrets.sh
```

涉及 `rpc-api` 契约、模型 Provider、Prompt、多模态、Nacos 配置或接口契约时，必须执行完整构建或说明不能执行的原因。

## 禁止事项

- 禁止重新引入 Maven 构建。
- 禁止把模型调用散落到 Controller。
- 禁止把 Provider 名称、模型名、URL 或密钥写死在业务代码里。
- 禁止提交真实密钥、数据库密码、Webhook 或生产配置值。
- 禁止在 `ai-agent` 新增业务表、DDL、Entity、Repository 或 Mapper；确需持久化时回到主 `ai`、`rag` 或独立业务服务，并按目标服务表设计规范处理。
- 禁止把用户输入直接拼 SQL、文件路径、URL 目标或系统命令。
- 禁止让 Agent 直接保存问卷答案、修改会话状态或推送外部系统。
- 禁止提交 `.DS_Store`、本机绝对路径、临时日志和无关构建产物。
