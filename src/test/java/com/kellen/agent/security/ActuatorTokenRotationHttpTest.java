package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 负责人在 Nacos 轮换令牌后无需重启即可生效。
 *
 * <p>Nacos 刷新的结果是同名配置源在运行中的 Environment 里被替换；这里直接替换配置源模拟该结果，
 * 不重建上下文。用例改动了共享 Environment，结束后丢弃上下文。</p>
 */
@SpringBootTest(classes = AiAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "security.actuator.token=" + ActuatorHttpTestSupport.TOKEN
        })
@DirtiesContext
class ActuatorTokenRotationHttpTest extends ActuatorHttpTestSupport {
    private static final String REFRESHED_SOURCE = "nacos-refresh-simulation";
    private static final String ROTATED_TOKEN = "rotated-synthetic-token";

    @LocalServerPort int port;
    @Autowired ConfigurableEnvironment environment;

    @Test void rotatedTokenReplacesPreviousTokenAndClearedTokenFailsClosed() throws Exception {
        assertThat(get(port, "/actuator/env", TOKEN).statusCode()).isEqualTo(200);

        environment.getPropertySources().addFirst(
                new MapPropertySource(REFRESHED_SOURCE, Map.of("security.actuator.token", ROTATED_TOKEN)));
        assertThat(get(port, "/actuator/env", ROTATED_TOKEN).statusCode()).isEqualTo(200);
        assertThat(get(port, "/actuator/env", TOKEN).statusCode()).isEqualTo(401);

        environment.getPropertySources().replace(REFRESHED_SOURCE,
                new MapPropertySource(REFRESHED_SOURCE, Map.of("security.actuator.token", " ")));
        assertThat(get(port, "/actuator/env", ROTATED_TOKEN).statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/env", " ").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/health", null).statusCode()).isEqualTo(200);
    }
}
