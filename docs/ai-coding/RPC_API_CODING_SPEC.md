# RPC API 协作规范

## 契约归属

- 跨服务 Dubbo RPC 接口、DTO、枚举和值对象统一维护在同级 `../rpc-api`。
- 当前服务只实现或调用 `rpc-api` 中的契约，不在本仓库复制接口和 DTO。
- `utils` 只提供 Dubbo 上下文透传、公共配置、公共工具和中间件适配，不维护业务 RPC 契约。

## 当前服务角色

- `ai-agent` 是 Agent 执行层，可以通过 `rpc-api` 暴露或消费稳定 Agent 执行契约。
- Agent 契约只能表达执行请求和执行结果，不保存主 `ai` 服务的会话、审计数据或文件元数据。
- 不把模型 SDK、Prompt 主数据、用户隐私原始材料或 provider 内部实现类型暴露到 RPC 契约中。

## 依赖和 CI

- Gradle 依赖使用 `implementation "com:rpc-api:${versions.rpcApi}"`。
- 本地联调前先在 `../rpc-api` 执行 `./gradlew publishToMavenLocal`。
- CI 必须先 checkout `sunkailun1992/rpc-api` 并 `publishToMavenLocal`，再编译本服务。
- 修改 RPC 契约时，至少同步编译 `../rpc-api`、provider 和直接 consumer。

## 上下文

- 登录用户、租户、数据源、Seata XID、请求 ID、版本号、流量泳道等横切上下文优先由 Dubbo attachment/filter 透传。
- 确实作为业务查询条件的字段可以保留在接口参数中，例如显式查询指定租户用户。
