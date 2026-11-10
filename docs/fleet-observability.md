# 监控采集与回滚

## 需求与边界

本批版本为 `0.2.4`，精确消费轻量 `com:fleet-observability:0.1.1`。使用 Micrometer Prometheus registry 提供指标，OpenTelemetry API 1.59.0 仅消费统一启动入口注入的 Java agent；应用不建立 SDK，也不依赖重型 utils。Java agent 的指标与日志 exporter 保持关闭（`OTEL_METRICS_EXPORTER=none`、`OTEL_LOGS_EXPORTER=none`），日志继续走共享 ES 配置。生产 classpath 不引入测试 SDK。

共享日志配置必须配套 `logging.structured.json.context.include=false`：关闭 Boot 默认自由 MDC/KVP 合流，再由轻量库固定成员 SPI 输出白名单。显式业务 KVP 优先，MDC 仅回退合法 trace/span 身份，同名键不会使整条 ECS 编码失败。AI 用量继续保持数字/布尔类型；未知 MDC、自由 message、异常 message/堆栈不输出，普通框架错误仅生成安全类名摘要。

固定部署包名为 `ai-agent.jar`，`applicationVersion` 纳入 `bootJar` 增量输入；版本变化必须重新打包并读回真实 Manifest。IDEA 日常仍从原生 Spring Boot 主类绿叶 Run/Debug，不要求改用 Gradle 启动。

指标固定维度为 `application=ai-agent`、构建产物 `version`、`environment=github-dev`；部署环境可通过 `observability.environment` 覆盖。业务会话 ID、患者、租户、用户、trace ID、URL、prompt 和正文不得作为标签。

原生 IDEA Spring Boot 启动不依赖 `META-INF/build-info.properties`。指标版本先读取统一启动入口提供的非空 `spring.application.version`，其次读取可选的 Jar `BuildProperties.version`；两者均缺失或空白时标记为 `unknown`，仍正常装配监控。此回退不生成虚假构建信息，也不关闭管理端点保护。

## 内部管理入口

运行配置来自共享 `actuator.yaml`，不把环境值搬回 base application.yml。只在专用内网或回环地址启用独立监听：`management.server.port`（计划采集端口 `17310`，实际值以目标部署核实）、`management.server.address`；允许范围为 `management.endpoints.web.exposure.include=health,prometheus`。采集器通过 `actuatorReq` 请求头发送私有 `security.actuator.token`，不能把真实令牌写入 Git、命令示例或日志。无令牌、错误令牌、空白配置和未解析配置均拒绝采集。

部署前必须核实绑定地址、主/管理两个实际监听端口、Prometheus 到管理地址的可达性和防火墙边界。不能用公网网关业务路由暴露采集接口。应分别验证无令牌 401、错误令牌 401、正确令牌 200 和输出包含 JVM 指标。不得为采集设置 exposure=*。

沿用 `ActuatorAccessFilter` 与独立管理子容器桥接，health 保留匿名探活，其余已配置端点需要运维令牌。没有新增敏感管理端点暴露；独立管理端口桥接缺失时抛错使启动失败，不能仅记录日志后继续运行。

OpenAI-compatible 模型使用应用的 `ObservationRegistry`。患者分析流保留 `ai.agent.execution` 整轮 span（`gen_ai.operation.name=invoke_agent`）。Prompt 解析、请求组装和 `memory.retrieve` 在整轮父 span 内完成；供应商实际同步调用或流式源订阅至终态使用独立的 `ai.model.call` 子 span（`gen_ai.operation.name=chat`）。模型时长不包含前置 Prompt/记忆准备；用量、模型响应元数据和 `event=ai.model.call` 日志只归属于模型子 span，避免父子重复计数。Prompt 不可用时仅终结整轮失败，不伪造未发生的模型调用。`memory.retrieve` 是模型调用的同级固定编排步骤，不称为模型自主 tool calling。DTO 的 `traceId` 仍是既有业务关联字段，不作为 OTel trace ID，不更改请求契约。

模型子 span 记录 `gen_ai.provider.name/request.model/response.model/response.finish_reasons`，以及有限的 `fleet.ai.*` Prompt 来源/实际版本、用量可用性、公开推理输出/摘要存在性。并发冷流每次订阅分别创建整轮/模型 span，成功、错误和取消均只终结一次；日志输出临时进入模型子 span 后恢复原线程上下文。

患者流通过 `ChatResponse` 保留供应商报告用量，正常业务文本保持原协议。累计 usage 只保留最后一次报告，不逐流片累加；未报告保持未知，不伪造0。Google output 为实际报告的 candidates + thoughts；OpenAI-compatible completion 已包含 reasoning，不重复加总。`gen_ai.usage.reasoning.output_tokens` 是 output 的子集。取消或失败时已报告用量不冒充完整终态用量；`fleet.ai.usage.final` 仅在成功完成并确有用量报告时为 true。

默认只写 `event=ai.model.call` 与安全 `summary`、`ai_operation/provider/model/prompt_key/prompt_version`、实际报告的 token、用量可用性、公开推理存在性及字符数摘要。不写 `ai_input/ai_output`、Prompt、回答、原始异常、任意 nativeUsage 或 thought signatures。供应商公开摘要/公开 reasoning 输出分别标识，不能声称可取得未公开的内部思维。正文诊断由共享轻量观测库的受信服务器开关 `fleet.observability.ai-content-enabled=false` 与安全投影接口统一控制；本服务在该适配接入并验收前始终省略正文，不能由请求头开启，也不能截取自由正文代替投影。

Nacos Prompt 的正文与解析版本保存在同一不可变快照，热刷新一起替换；配置的 label 不冒充实际版本。调用方传入 Prompt 时明确 `source=caller/version=unknown`，调用方自己的真实版本在主服务编排 span 上记录。SSE 成功、失败、取消各终结一次观测，模型错误经过固定文案降级后仍计作 failure；并发订阅独立保存元数据与父上下文。日志或用量元数据解析失败不改变模型响应。

## 验收与回滚

SSE 安全日志合同测试直接加载同级 `utils/ops/github-dev/nacos/DEFAULT_GROUP/logging.yml`；缺少该共享模板的单仓 CI 明确跳过此合同测试。仅工作区内加载实际模板后的测试通过，才能宣称该配置下的隐私标记回归通过。独立核心 span、线程上下文和管理端点测试仍在单仓运行。

源码验证执行 `./gradlew clean test bootJar --no-daemon` 与 `STRICT_SECRET_SCAN=1 bash scripts/check-secrets.sh`。测试使用合成令牌与本机临时监听，不连接真实 Nacos、模型供应商或监控服务器；端到端采集、真实 Dubbo/HTTP trace、ES 关联和生产资源影响仍需部署后独立验收。

源码测试、实际 Jar Manifest/轻量依赖、远端发布和运行态采集分别验收。回滚先停用采集目标与 Java agent 启动参数，再成套回退服务制品及日志配置；关闭监控不能放宽现有管理令牌或公网阻断。历史 Spring Boot Admin/Zipkin 已退役，当前 profiles 与配置分类不再导入这两项，不恢复旧配置补采集。
