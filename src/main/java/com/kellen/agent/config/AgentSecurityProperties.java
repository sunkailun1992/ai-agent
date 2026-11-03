package com.kellen.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * 网关 Agent 服务间调用策略；系统编码和客户端由部署配置控制，不能来自模型或用户正文。
 *
 * @param providerCode user 统一认证中专用的外部系统编码；未配置时网关模式拒绝调用。
 * @param allowedClientIds 允许调用执行层的专用服务客户端白名单；为空时拒绝调用。
 * @param maxBodyBytes 验签前内存缓冲上限，默认 32 MiB、最大 64 MiB。
 */
@ConfigurationProperties(prefix = "ai.agent.security")
public record AgentSecurityProperties(@DefaultValue("") String providerCode,
                                      @DefaultValue Set<String> allowedClientIds,
                                      @DefaultValue("33554432") int maxBodyBytes) {
    public AgentSecurityProperties {
        providerCode = providerCode == null ? "" : providerCode.trim();
        allowedClientIds = allowedClientIds == null ? Set.of() : Set.copyOf(allowedClientIds);
        if (maxBodyBytes <= 0 || maxBodyBytes > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("ai.agent.security.max-body-bytes 必须介于 1 与 64 MiB");
        }
    }
}
