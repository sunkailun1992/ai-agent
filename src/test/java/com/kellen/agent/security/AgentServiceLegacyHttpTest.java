package com.kellen.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 未切换 gateway origin 的旧院内部署仍保留原有网络调用契约。 */
@SpringBootTest(classes = AgentServiceAuthenticationHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=agent-auth-legacy-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false"})
class AgentServiceLegacyHttpTest {
    @LocalServerPort int port;
    @Test void emptyPublicOriginKeepsLegacyInternalMode() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agents/model"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
}
