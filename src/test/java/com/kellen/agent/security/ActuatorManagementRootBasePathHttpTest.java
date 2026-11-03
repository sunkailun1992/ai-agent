package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立管理端口且端点根路径设为 / 时，端点直接挂在管理端口根下，目录页就是 /，同样需要令牌。
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
                "management.endpoints.web.base-path=/",
                "server.address=127.0.0.1",
                "management.server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "security.actuator.token=" + ActuatorHttpTestSupport.TOKEN
        })
class ActuatorManagementRootBasePathHttpTest extends ActuatorHttpTestSupport {
    @LocalManagementPort int managementPort;
    @Autowired ActuatorProbeConfiguration.CatchAllProbeEndpoint catchAllProbe;

    @Test void rootLinksPageRequiresToken() throws Exception {
        assertThat(get(managementPort, "/", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/", TOKEN).statusCode()).isEqualTo(200);
    }

    @Test void rootMappedEndpointsRequireTokenExceptHealth() throws Exception {
        assertThat(get(managementPort, "/env", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/env", TOKEN).statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/health", null).statusCode()).isEqualTo(200);
    }

    @Test void dotSegmentsCannotReachCatchAllEndpointThroughRoot() throws Exception {
        int invocationsBefore = catchAllProbe.invocations();
        assertThat(statusOf(managementPort, "GET", "/catchallprobe/..", null)).isEqualTo(401);
        assertThat(statusOf(managementPort, "GET", "/catchallprobe/../health", null)).isEqualTo(401);
        assertThat(catchAllProbe.invocations()).isEqualTo(invocationsBefore);
    }
}
