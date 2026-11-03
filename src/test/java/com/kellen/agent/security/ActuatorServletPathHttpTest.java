package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nacos 配置了 server.servlet.context-path 与 spring.mvc.servlet.path 时，端点落在两段前缀之下，保护与探活跟着走。
 */
@SpringBootTest(classes = {AiAgentApplication.class, ActuatorProbeConfiguration.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "server.servlet.context-path=/ai-agent",
                "spring.mvc.servlet.path=/svc",
                "server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "security.actuator.token=" + ActuatorHttpTestSupport.TOKEN
        })
class ActuatorServletPathHttpTest extends ActuatorHttpTestSupport {
    @LocalServerPort int port;
    @Autowired ActuatorProbeConfiguration.CatchAllProbeEndpoint catchAllProbe;

    @Test void endpointsUnderBothPrefixesRequireToken() throws Exception {
        assertThat(get(port, "/ai-agent/svc/actuator/env", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/ai-agent/svc/actuator", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/ai-agent/svc/actuator/env", TOKEN).statusCode()).isEqualTo(200);
    }

    @Test void healthUnderBothPrefixesStaysPublic() throws Exception {
        assertThat(get(port, "/ai-agent/svc/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test void dotSegmentsCannotEscapeToCatchAllEndpoint() throws Exception {
        int invocationsBefore = catchAllProbe.invocations();
        assertThat(statusOf(port, "GET", "/ai-agent/svc/actuator/catchallprobe/../..", null)).isEqualTo(401);
        assertThat(statusOf(port, "GET", "/ai-agent/svc/actuator/catchallprobe/../health", null)).isEqualTo(401);
        assertThat(catchAllProbe.invocations()).isEqualTo(invocationsBefore);
    }

    @Test void businessEndpointsKeepWorking() throws Exception {
        assertThat(get(port, "/ai-agent/svc/api/agents/model", null).statusCode()).isEqualTo(200);
    }
}
