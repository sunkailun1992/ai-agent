package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** Nacos 改了端点根路径或 health 路径映射时，保护与公开范围跟着实际映射走，不依赖写死的 /actuator。 */
@SpringBootTest(classes = AiAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "management.endpoints.web.base-path=/ops",
                "management.endpoints.web.path-mapping.health=probe",
                "server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "security.actuator.token=" + ActuatorHttpTestSupport.TOKEN
        })
class ActuatorCustomBasePathHttpTest extends ActuatorHttpTestSupport {
    @LocalServerPort int port;

    @Test void customBasePathIsProtected() throws Exception {
        assertThat(get(port, "/ops/env", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/ops", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/ops/env", TOKEN).statusCode()).isEqualTo(200);
    }

    @Test void remappedHealthStaysPublic() throws Exception {
        assertThat(get(port, "/ops/probe", null).statusCode()).isEqualTo(200);
    }

    @Test void formerHealthPathIsNoLongerExempt() throws Exception {
        assertThat(get(port, "/ops/health", null).statusCode()).isEqualTo(401);
    }
}
