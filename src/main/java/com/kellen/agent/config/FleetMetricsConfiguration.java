package com.kellen.agent.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 为进程指标增加固定部署维度；业务会话、患者、租户与 trace ID 不能作为指标标签。 */
@Configuration(proxyBeanMethods = false)
public class FleetMetricsConfiguration {
    @Bean
    MeterRegistryCustomizer<MeterRegistry> fleetMetricsTags(BuildProperties build, Environment environment) {
        return registry -> registry.config().commonTags("application", "ai-agent", "version", build.getVersion(),
                "environment", environment.getProperty("observability.environment", "github-dev"));
    }
}
