package com.kellen.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Nacos AI A2A 注册中心写入配置。
 *
 * <p>ai-agent 作为执行层，启动时通过 Nacos 官方 {@code A2aService} SDK 自动发布
 * AgentCard 并注册运行时端点，停机时注销，替代在控制台手工维护 AgentCard。
 * 凭据和地址必须由 Nacos 配置中心或环境变量注入，不写入仓库明文。</p>
 *
 * @param enabled            是否启用自动注册；关闭时不连接注册中心，便于本地或测试启动。
 * @param serverAddr         Nacos 服务端地址，建议引用 {@code custom.infra-nacos-addr}。
 * @param namespace          注册使用的命名空间，与配置中心保持一致。
 * @param username           Nacos 鉴权用户名。
 * @param password           Nacos 鉴权密码。
 * @param contextPath        Nacos 上下文路径，默认 {@code /nacos}。
 * @param advertiseHost      对外暴露的可达地址；留空时自动探测本机 IP，容器内通常即为可达地址。
 * @param advertisePort      对外暴露端口；为 0 时回退到 {@code server.port}。
 * @param supportTls         端点是否走 HTTPS，决定 AgentCard 接口 URL 的协议。
 * @param protocolVersion    A2A 协议版本号。
 * @param cardVersion        AgentCard 版本号，升级卡内容时递增。
 * @param tenant             A2A 端点租户标识，跨应用隔离用，可留空。
 * @param providerOrg        AgentCard provider.organization 展示名。
 */
@ConfigurationProperties(prefix = "ai.agent.registry")
public record AgentRegistryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String serverAddr,
        @DefaultValue("") String namespace,
        @DefaultValue("") String username,
        @DefaultValue("") String password,
        @DefaultValue("/nacos") String contextPath,
        @DefaultValue("") String advertiseHost,
        @DefaultValue("0") int advertisePort,
        @DefaultValue("false") boolean supportTls,
        @DefaultValue("1.0") String protocolVersion,
        @DefaultValue("1.0.0") String cardVersion,
        @DefaultValue("") String tenant,
        @DefaultValue("Orb AI") String providerOrg
) {
}
