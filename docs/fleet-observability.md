# 监控采集与回滚

## 需求与边界

本批版本为 `0.2.2`。使用 Micrometer Prometheus registry 提供指标，OpenTelemetry API 1.59.0 仅消费统一启动入口注入的 Java agent；应用不建立 SDK，也不依赖重型 utils。Java agent 的指标与日志 exporter 保持关闭（`OTEL_METRICS_EXPORTER=none`、`OTEL_LOGS_EXPORTER=none`），日志继续走共享 ES 配置。生产 classpath 不引入测试 SDK。

指标固定维度为 `application=ai-agent`、构建产物 `version`、`environment=github-dev`；部署环境可通过 `observability.environment` 覆盖。业务会话 ID、患者、租户、用户、trace ID、URL、prompt 和正文不得作为标签。

原生 IDEA Spring Boot 启动不依赖 `META-INF/build-info.properties`。指标版本先读取统一启动入口提供的非空 `spring.application.version`，其次读取可选的 Jar `BuildProperties.version`；两者均缺失或空白时标记为 `unknown`，仍正常装配监控。此回退不生成虚假构建信息，也不关闭管理端点保护。

## 内部管理入口

运行配置来自共享 `actuator.yaml`，不把环境值搬回 base application.yml。只在专用内网或回环地址启用独立监听：`management.server.port`（计划采集端口 `17310`，实际值以目标部署核实）、`management.server.address`；允许范围为 `management.endpoints.web.exposure.include=health,prometheus`。采集器通过 `actuatorReq` 请求头发送私有 `security.actuator.token`，不能把真实令牌写入 Git、命令示例或日志。无令牌、错误令牌、空白配置和未解析配置均拒绝采集。

部署前必须核实绑定地址、主/管理两个实际监听端口、Prometheus 到管理地址的可达性和防火墙边界。不能用公网网关业务路由暴露采集接口。应分别验证无令牌 401、错误令牌 401、正确令牌 200 和输出包含 JVM 指标。不得为采集设置 exposure=*。

沿用 `ActuatorAccessFilter` 与独立管理子容器桥接，health 保留匿名探活，其余已配置端点需要运维令牌。没有新增敏感管理端点暴露；独立管理端口桥接缺失时抛错使启动失败，不能仅记录日志后继续运行。

OpenAI-compatible 模型使用应用的 `ObservationRegistry`。患者分析流和问卷调用增加 `ai.agent.execution` span，只携带 provider、固定 operation、终态 outcome。SSE 成功、失败、取消分别保留 `ai.agent.model.call` timer；模型错误经过固定文案降级后仍计作 failure。原始异常、prompt、回包和业务会话 ID 不进入自定义 span，线程上下文在装配与订阅后恢复。DTO 的 `traceId` 是既有业务关联字段，不作为 OTel trace ID，不更改契约。

## 验收与回滚

SSE 安全日志合同测试直接加载同级 `utils/ops/github-dev/nacos/DEFAULT_GROUP/logging.yml`；缺少该共享模板的单仓 CI 明确跳过此合同测试。仅工作区内加载实际模板后的测试通过，才能宣称该配置下的隐私标记回归通过。独立核心 span、线程上下文和管理端点测试仍在单仓运行。

源码验证执行 `./gradlew clean test bootJar --no-daemon` 与 `STRICT_SECRET_SCAN=1 bash scripts/check-secrets.sh`。测试使用合成令牌与本机临时监听，不连接真实 Nacos、模型供应商或监控服务器；端到端采集、真实 Dubbo/HTTP trace、ES 关联和生产资源影响仍需部署后独立验收。

本批没有发布制品、推送、部署或修改远程 Nacos。回滚先停用采集目标与 Java agent 启动参数，再回退服务制品及其配套配置；关闭监控不能放宽现有管理令牌或公网阻断。历史 Spring Boot Admin/Zipkin 已退役，当前 profiles 与配置分类不再导入这两项，不恢复旧配置补采集。
