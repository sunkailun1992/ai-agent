package com.kellen.agent.security;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** 旧内网模式不创建新增认证 RPC 引用，避免给未切换的部署引入新注册中心依赖。 */
final class AgentGatewayModeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return StringUtils.hasText(context.getEnvironment().getProperty("ai.agent.registry.public-base-url"));
    }
}
