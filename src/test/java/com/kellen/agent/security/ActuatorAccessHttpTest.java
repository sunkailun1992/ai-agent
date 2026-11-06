package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内网直连实例端口时，除 health 外的 Actuator 端点必须携带令牌。
 *
 * <p>配置复现最危险的 Nacos 取值：端点全部暴露（模拟错误的全端点暴露配置）。用真实应用上下文与真实 HTTP，
 * 覆盖 Boot、Spring Cloud 与 Nacos 在本服务实际注册的全部端点，不调用模型。另挂测试专用的通配端点和一个
 * 映射在端点根路径下的独立 Servlet，验证点段与 Servlet 映射都不能绕开保护范围。</p>
 */
@SpringBootTest(classes = {AiAgentApplication.class, ActuatorProbeConfiguration.class,
        ActuatorAccessHttpTest.ExtraServletConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "management.endpoint.health.probes.enabled=true",
                "security.actuator.token=" + ActuatorHttpTestSupport.TOKEN
        })
class ActuatorAccessHttpTest extends ActuatorHttpTestSupport {
    private static final String PROBE_LOGGER = "com.kellen.agent.security.ActuatorProbe";

    @LocalServerPort int port;
    @Autowired PathMappedEndpoints endpoints;
    @Autowired LoggingSystem loggingSystem;
    @Autowired ActuatorProbeConfiguration.CatchAllProbeEndpoint catchAllProbe;
    @Autowired ExtraServlet extraServlet;

    @AfterEach void resetProbeLogger() {
        loggingSystem.setLogLevel(PROBE_LOGGER, null);
    }

    @Test void envRejectsRequestWithoutToken() throws Exception {
        HttpResponse<String> response = get(port, "/actuator/env", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"code\":\"ACTUATOR_AUTH_REQUIRED\"}");
    }

    @Test void endpointLinksRejectRequestWithoutToken() throws Exception {
        assertThat(get(port, "/actuator", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/", null).statusCode()).isEqualTo(401);
    }

    @Test void everyExposedEndpointExceptHealthRejectsRequestWithoutToken() throws Exception {
        String healthPath = endpoints.getPath(EndpointId.of("health"));
        List<String> paths = endpoints.getAllPaths().stream().filter(path -> !path.equals(healthPath)).toList();
        assertThat(paths).contains("/actuator/env", "/actuator/loggers", "/actuator/refresh", "/actuator/configprops");
        for (String path : paths) {
            assertThat(statusOf(port, "GET", path, null)).as(path).isEqualTo(401);
        }
    }

    @Test void wrongOrBlankTokenIsRejected() throws Exception {
        assertThat(get(port, "/actuator/env", TOKEN + "-wrong").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/env", "").statusCode()).isEqualTo(401);
    }

    @Test void everyMethodRequiresToken() throws Exception {
        for (String method : new String[]{"HEAD", "OPTIONS", "POST", "PUT", "DELETE"}) {
            assertThat(statusOf(port, method, "/actuator/env", null)).as(method).isEqualTo(401);
        }
    }

    @Test void writeOperationWithoutTokenIsRejectedBeforeInvocation() throws Exception {
        HttpResponse<String> response = send(port, "POST", "/actuator/loggers/" + PROBE_LOGGER,
                "{\"configuredLevel\":\"DEBUG\"}", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(configuredLevel()).isNull();
    }

    @Test void configuredTokenAllowsReadAndWrite() throws Exception {
        assertThat(get(port, "/actuator/env", TOKEN).statusCode()).isEqualTo(200);
        assertThat(get(port, "/actuator", TOKEN).statusCode()).isEqualTo(200);
        HttpResponse<String> response = send(port, "POST", "/actuator/loggers/" + PROBE_LOGGER,
                "{\"configuredLevel\":\"DEBUG\"}", TOKEN);
        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(configuredLevel()).isEqualTo(LogLevel.DEBUG);
    }

    @Test void healthAndProbeGroupsStayPublic() throws Exception {
        HttpResponse<String> health = get(port, "/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(get(port, "/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(get(port, "/actuator/health/readiness", null).statusCode()).isEqualTo(200);
    }

    @Test void healthExemptionStopsAtPathSegmentBoundary() throws Exception {
        assertThat(statusOf(port, "GET", "/actuator/healthx", null)).isEqualTo(401);
    }

    @Test void nonCanonicalPathsCannotReachProtectedEndpoints() throws Exception {
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (String path : new String[]{"/actuator/env/", "/%61ctuator/env", "/actuator/%65nv", "/actuator;a=b/env",
                "/actuator/env;a=b", "/actuator/health/../env", "/actuator/health/..;/env", "/actuator/health;/../env",
                "/actuator/health/%2e%2e/env", "/x/../actuator/env", "//actuator/env", "/actuator//env",
                "/actuator/./env", "/actuator/health;a=b"}) {
            expected.put(path, 401);
        }
        // Tomcat 默认拒绝编码斜线，请求到不了过滤器。
        expected.put("/actuator%2fenv", 400);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            assertThat(statusOf(port, "GET", entry.getKey(), null)).as(entry.getKey()).isEqualTo(entry.getValue());
        }
    }

    @Test void dotSegmentsCannotBorrowHealthExemptionForCatchAllEndpoint() throws Exception {
        int invocationsBefore = catchAllProbe.invocations();
        assertThat(statusOf(port, "GET", "/actuator/catchallprobe/../health", null)).isEqualTo(401);
        assertThat(catchAllProbe.invocations()).isEqualTo(invocationsBefore);
        assertThat(get(port, "/actuator/catchallprobe/any/path", TOKEN).statusCode()).isEqualTo(200);
    }

    @Test void dotSegmentsCannotEscapeEndpointAreaToReachCatchAllEndpoint() throws Exception {
        int invocationsBefore = catchAllProbe.invocations();
        for (String path : new String[]{"/actuator/catchallprobe/../..", "/actuator/catchallprobe/x/../../..",
                "/actuator;a=b/catchallprobe/../..", "/actuator/catchallprobe/%2e%2e/%2e%2e",
                "/actuator/catchallprobe/../../api/agents/model"}) {
            assertThat(statusOf(port, "GET", path, null)).as(path).isEqualTo(401);
        }
        assertThat(catchAllProbe.invocations()).isEqualTo(invocationsBefore);
    }

    @Test void servletMappedUnderBasePathRequiresToken() throws Exception {
        int invocationsBefore = extraServlet.invocations.get();
        for (String path : new String[]{"/actuator/extra", "/actuator/extra/", "/actuator/extra/x",
                "/actuator/extra/actuator/health"}) {
            assertThat(statusOf(port, "GET", path, null)).as(path).isEqualTo(401);
        }
        assertThat(extraServlet.invocations).hasValue(invocationsBefore);
        assertThat(get(port, "/actuator/extra/x", TOKEN).statusCode()).isEqualTo(200);
    }

    @Test void businessEndpointsDoNotNeedActuatorToken() throws Exception {
        assertThat(get(port, "/api/agents/model", null).statusCode()).isEqualTo(200);
    }

    private LogLevel configuredLevel() {
        LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(PROBE_LOGGER);
        return configuration == null ? null : configuration.getConfiguredLevel();
    }

    /** 独立于 DispatcherServlet、映射在端点根路径下的 Servlet，pathInfo 非空但并不属于 Spring MVC。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class ExtraServletConfiguration {
        @Bean ExtraServlet extraServlet() { return new ExtraServlet(); }
        @Bean ServletRegistrationBean<ExtraServlet> extraServletRegistration(ExtraServlet servlet) {
            return new ServletRegistrationBean<>(servlet, "/actuator/extra/*");
        }
    }

    static class ExtraServlet extends HttpServlet {
        final AtomicInteger invocations = new AtomicInteger();
        @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
            invocations.incrementAndGet();
            response.getWriter().write("extra");
        }
    }
}
