package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/** 令牌写成引用但被引用的键不存在时按未配置处理：照字面带上占位符也打不开端点，也不能变成 500。 */
@SpringBootTest(classes = AiAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "security.actuator.token=${missing.actuator.token.source}"
        })
class ActuatorUnresolvedTokenHttpTest extends ActuatorHttpTestSupport {
    @LocalServerPort int port;

    @Test void unresolvablePlaceholderFailsClosed() throws Exception {
        assertThat(get(port, "/actuator/env", null).statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/env", "${missing.actuator.token.source}").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/health", null).statusCode()).isEqualTo(200);
    }
}
