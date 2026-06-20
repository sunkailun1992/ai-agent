# AI Agent 注释规范

- 注释解释职责、边界、安全约束和失败策略，不逐行翻译代码。
- Controller、Service、配置类和外部模型调用入口应有类级或方法级 Javadoc。
- 模型 Provider 注释要说明 provider/model 来源和覆盖规则。
- Prompt 注释要说明医疗安全边界和问卷状态边界。
- 不用注释保留废弃代码、调试 main 或旧实现。
- 注释不得泄露模型 Key、内部地址、用户隐私或完整媒体 URL。
