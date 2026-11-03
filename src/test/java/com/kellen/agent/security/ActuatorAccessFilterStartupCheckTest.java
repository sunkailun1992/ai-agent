package com.kellen.agent.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立管理端口的保护靠 jar 根目录下的 imports 文件挂进子容器；以展开的 classpath 启动等情况读不到它时，
 * 管理端口会静默失去保护，启动完成后必须留下错误日志。
 */
@ExtendWith(OutputCaptureExtension.class)
class ActuatorAccessFilterStartupCheckTest {
    private static final String MISSING_BRIDGE = "独立管理端口上的 Actuator 未挂上令牌保护";

    @Test void reportsUnprotectedManagementPort(CapturedOutput output) {
        filter("8081").verifyManagementServerProtected();
        assertThat(output).contains(MISSING_BRIDGE);
    }

    @Test void staysQuietOnceManagementPortIsBridged(CapturedOutput output) {
        ActuatorAccessFilter filter = filter("8081");
        filter.forManagementServer();
        filter.verifyManagementServerProtected();
        assertThat(output).doesNotContain(MISSING_BRIDGE);
    }

    @Test void samePortNeedsNoBridge(CapturedOutput output) {
        filter("7310").verifyManagementServerProtected();
        assertThat(output).doesNotContain(MISSING_BRIDGE);
    }

    private static ActuatorAccessFilter filter(String managementPort) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("server.port", "7310")
                .withProperty("management.server.port", managementPort)
                .withProperty("security.actuator.token", "synthetic-actuator-token");
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        return new ActuatorAccessFilter(environment, beans.getBeanProvider(PathMappedEndpoints.class),
                beans.getBeanProvider(DispatcherServletPath.class));
    }
}
