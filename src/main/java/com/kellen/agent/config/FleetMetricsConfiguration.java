package com.kellen.agent.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** 为进程指标增加固定部署维度；业务会话、患者、租户与 trace ID 不能作为指标标签。 */
@Configuration(proxyBeanMethods = false)
public class FleetMetricsConfiguration {
    @Bean
    MeterRegistryCustomizer<MeterRegistry> fleetMetricsTags(ObjectProvider<BuildProperties> builds,
            Environment environment) {
        // 原生 IDE 不保证生成 build-info；统一启动入口的显式版本优先于可能陈旧的构建资源。
        String version = environment.getProperty("spring.application.version");
        if (!StringUtils.hasText(version)) {
            BuildProperties build = builds.getIfAvailable();
            version = build == null ? null : build.getVersion();
        }
        String metricVersion = StringUtils.hasText(version) ? version : "unknown";
        return registry -> registry.config().commonTags("application", "ai-agent", "version", metricVersion,
                "environment", environment.getProperty("observability.environment", "github-dev"));
    }
}
