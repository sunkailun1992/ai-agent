package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 未配置 security.actuator.token 时失败关闭：任何请求头都打不开 health 以外的端点，探活不受影响。 */
@SpringBootTest(classes = AiAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*"
        })
class ActuatorAccessWithoutTokenHttpTest extends ActuatorHttpTestSupport {
    @LocalServerPort int port;

    @Test void anyHeaderValueIsRejectedWhenNoTokenConfigured() throws Exception {
        assertThat(get(port, "/actuator/env", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/env", "any-value").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/env", "").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator", "").statusCode()).isEqualTo(401);
    }

    @Test void healthStaysPublicWhenNoTokenConfigured() throws Exception {
        assertThat(get(port, "/actuator/health", null).statusCode()).isEqualTo(200);
    }
}
