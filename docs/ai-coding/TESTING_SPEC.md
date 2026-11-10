# 测试分层规范

## 定位

`ai-agent` 是 Agent 执行层服务。核心 Controller、Provider 选择、Prompt 读取、模型执行编排必须覆盖真实 Spring Boot 业务链路，不能只靠纯对象断言。

## 主流分层

- 单元测试：DTO、枚举、Prompt 拼装、小工具、小函数，使用 JUnit 5 + AssertJ。
- Slice 测试：局部 Controller 可以使用 `@WebMvcTest`，只作为补充。
- Service 集成测试：核心业务用 `@SpringBootTest` 注入真实 Spring Bean，验证 Provider 选择、配置绑定和 Agent 编排。
- HTTP 集成测试：核心对外接口用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)`，通过 `TestRestTemplate` 或 `WebTestClient` 发真实 HTTP 请求。
- 跨服务测试：Dubbo、Gateway、Nacos、主 `ai` 服务等链路放到独立 integration profile 或测试环境。

## assertThat 规则

`assertThat` 是断言工具，可以继续使用。判断测试是否充分，看被断言的数据是否来自真实业务链路，而不是看是否用了 `assertThat`。

## 业务测试要求

- Provider 白名单、模型选择、Prompt 读取、患者分析 Agent 等核心路径必须有 Spring Boot 级别测试。
- Controller 测试优先走真实 HTTP 请求，验证路由、参数校验、统一响应、流式边界和异常处理。
- 不允许只用 mock 或纯 Java 对象测试替代核心业务集成测试。

## 外部模型

- 默认测试不调用真实模型供应商。
- 模型调用默认使用 fake provider、stub client、mock server 或测试 profile。
- 需要真实模型的测试必须单独标记为 integration，不进入普通 CI 默认路径。

## 必跑命令

```bash
./gradlew clean test
```
