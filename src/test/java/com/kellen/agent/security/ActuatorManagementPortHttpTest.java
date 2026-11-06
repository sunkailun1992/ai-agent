package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 配置独立管理端口时，管理端口上的 Actuator 同样需要令牌。
 *
 * <p>独立管理端口由 Boot 在子上下文另起一个 Tomcat，主上下文的普通 Filter 不会自动挂过去；
 * 这里带上 management.server.base-path，用真实端口确认子容器里的保护、探活与令牌轮换。</p>
 */
@SpringBootTest(classes = {AiAgentApplication.class, ActuatorProbeConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "management.server.port=0",
                "management.server.base-path=/manage",
                "server.address=127.0.0.1",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "security.actuator.token=" + ActuatorHttpTestSupport.TOKEN
        })
class ActuatorManagementPortHttpTest extends ActuatorHttpTestSupport {
    private static final String REFRESHED_SOURCE = "nacos-refresh-simulation";

    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired ConfigurableEnvironment environment;
    @Autowired ActuatorProbeConfiguration.CatchAllProbeEndpoint catchAllProbe;

    @Test void managementPortRejectsRequestWithoutToken() throws Exception {
        assertThat(get(managementPort, "/manage/actuator/env", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/manage/actuator", null).statusCode()).isEqualTo(401);
        assertThat(statusOf(managementPort, "POST", "/manage/actuator/refresh", null)).isEqualTo(401);
    }

    @Test void prometheusRequiresValidToken() throws Exception {
        assertThat(get(managementPort, "/manage/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/manage/actuator/prometheus", "wrong").statusCode()).isEqualTo(401);
        var response = get(managementPort, "/manage/actuator/prometheus", TOKEN);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }

    @Test void managementPortAllowsConfiguredToken() throws Exception {
        assertThat(get(managementPort, "/manage/actuator/env", TOKEN).statusCode()).isEqualTo(200);
    }

    @Test void managementPortHealthStaysPublic() throws Exception {
        assertThat(get(managementPort, "/manage/actuator/health", null).statusCode()).isEqualTo(200);
    }

    /** 规范化后正好落在 context 根（如 /manage/actuator/catchallprobe/../..）的请求由 Tomcat 直接 302 到 /manage/，到不了应用。 */
    @Test void managementPortDotSegmentsCannotEscapeToCatchAllEndpoint() throws Exception {
        int invocationsBefore = catchAllProbe.invocations();
        assertThat(statusOf(managementPort, "GET", "/manage/actuator/catchallprobe/../../x", null)).isEqualTo(401);
        assertThat(statusOf(managementPort, "GET", "/manage/actuator/catchallprobe/../health", null)).isEqualTo(401);
        assertThat(catchAllProbe.invocations()).isEqualTo(invocationsBefore);
    }

    /** 子容器的配置源是启动时从父上下文合并的副本；令牌必须读主上下文，刷新后才不会停在旧值。 */
    @Test void rotatedTokenAppliesOnManagementPortWithoutRestart() throws Exception {
        environment.getPropertySources().addFirst(
                new MapPropertySource(REFRESHED_SOURCE, Map.of("security.actuator.token", "rotated-synthetic-token")));
        try {
            assertThat(get(managementPort, "/manage/actuator/env", "rotated-synthetic-token").statusCode()).isEqualTo(200);
            assertThat(get(managementPort, "/manage/actuator/env", TOKEN).statusCode()).isEqualTo(401);
        } finally {
            environment.getPropertySources().remove(REFRESHED_SOURCE);
        }
    }

    @Test void applicationPortKeepsServingBusinessEndpoints() throws Exception {
        assertThat(get(port, "/api/agents/model", null).statusCode()).isEqualTo(200);
    }
}
