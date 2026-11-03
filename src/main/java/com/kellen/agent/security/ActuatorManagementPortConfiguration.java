package com.kellen.agent.security;

import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * 独立管理端口（management.server.port 与业务端口不同）时，把 Actuator 令牌边界挂到 Boot 另起的管理子容器。
 *
 * <p>子容器只注册自己上下文里的 Filter，主上下文的 {@link ActuatorAccessFilter} 不会自动生效。这里沿用 Boot 给
 * springSecurityFilterChain 的桥接方式，取父上下文的同一实例派生管理端口变体，令牌仍读主上下文的 Environment。</p>
 *
 * <p>只经 META-INF/spring 下的 ManagementContextConfiguration.imports 导入管理上下文：同端口时导入主上下文但端口条件不成立，
 * 独立端口时只导入子上下文。刻意不加 {@code @ManagementContextConfiguration}，它自带 {@code @Configuration}，会被主上下文的
 * 组件扫描收进去。</p>
 */
class ActuatorManagementPortConfiguration {

    @Bean
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    FilterRegistrationBean<ActuatorAccessFilter> actuatorManagementPortAccessFilter(ActuatorAccessFilter applicationFilter) {
        FilterRegistrationBean<ActuatorAccessFilter> registration =
                new FilterRegistrationBean<>(applicationFilter.forManagementServer());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
