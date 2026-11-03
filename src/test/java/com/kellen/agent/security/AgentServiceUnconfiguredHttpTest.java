package com.kellen.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** 切换网关地址但遗漏专用身份时，必须拒绝而非回退匿名调用。 */
@SpringBootTest(classes = AgentServiceAuthenticationHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=agent-auth-missing-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "ai.agent.registry.public-base-url=https://gateway.example.invalid"})
class AgentServiceUnconfiguredHttpTest {
    @LocalServerPort int port;
    @Test void publicOriginWithoutServerOwnedProviderAndAllowlistFailsClosed() throws Exception {
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/agents/model"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"code\":\"AGENT_AUTH_UNAVAILABLE\"}");
    }
}
