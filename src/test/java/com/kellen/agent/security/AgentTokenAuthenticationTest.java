package com.kellen.agent.security;

import com.ai.agent.support.FakeIssuer;
import com.kellen.agent.dto.AgentChatRequest;
import com.kellen.agent.memory.DubboMemoryAgentClient;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcService;
import com.kellen.security.SecurityUser;
import com.kellen.security.oauth2.FleetResourceServerAutoConfiguration;
import com.kellen.security.oauth2.SecurityContextResult;
import com.kellen.security.oauth2.SecurityContextRpcService;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai-agent 只收 ai 经令牌交换取得的令牌：受众 github-ai-agent、azp github-ai、RS256。用户取令牌里的 user_id，活动租户经 user 服务的
 * 业务上下文核实（X-Tenant-Id 只能选用户所属的租户），请求正文的用户与租户必须与之一致。旧的 HMAC 头不再有任何作用。
 * 真实资源服务器（utils）、真实签名校验与真实 Memory 适配器；签发方与 user 服务是本地替身。
 */
@SpringBootTest(classes = AgentTokenAuthenticationTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"test.agent-token.fixture=true", "spring.config.name=isolated-agent-token", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "spring.security.oauth2.resourceserver.jwt.jws-algorithms=RS256",
                "spring.security.oauth2.resourceserver.jwt.audiences=github-ai-agent",
                "fleet.security.oauth2.required-azp=github-ai"})
class AgentTokenAuthenticationTest {

    static final FakeIssuer ISSUER = FakeIssuer.start();

    @LocalServerPort
    int port;

    @Autowired
    Probe probe;

    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER::issuer);
    }

    @AfterAll
    static void stop() {
        ISSUER.close();
    }

    @BeforeEach
    void reset() {
        probe.calls.set(0);
        probe.tenant = null;
        probe.sourceTenant = null;
    }

    @Test
    void noTokenIs401() throws Exception {
        assertThat(send(null, null, "user-a", "tenant-a").statusCode()).isEqualTo(401);
        assertThat(send("not-a-jwt", null, "user-a", "tenant-a").statusCode()).isEqualTo(401);
        assertThat(probe.calls).hasValue(0);
    }

    @Test
    void tokenWithAudienceGithubApiIs401() throws Exception {
        // 网关签给业务后端的用户令牌（受众 github-api）不能直接调 ai-agent。
        assertThat(send(ISSUER.token("user-a", "github-api", "github-gateway"), null, "user-a", "tenant-a").statusCode()).isEqualTo(401);
        assertThat(probe.calls).hasValue(0);
    }

    @Test
    void tokenFromWrongAzpIs403() throws Exception {
        assertThat(send(ISSUER.token("user-a", "github-ai-agent", "github-gateway"), null, "user-a", "tenant-a").statusCode()).isEqualTo(403);
        assertThat(probe.calls).hasValue(0);
    }

    @Test
    void exchangedTokenYieldsUserIdentity() throws Exception {
        assertThat(send(ISSUER.exchangedToken("user-a"), "tenant-a", "user-a", "tenant-a").statusCode()).isEqualTo(200);
        assertThat(probe.tenant).isEqualTo("tenant-a");
        assertThat(probe.sourceTenant).isEqualTo("source-other");
        // 用户的第二个租户：X-Tenant-Id 选中、经业务上下文核实后才是活动租户。
        assertThat(send(ISSUER.exchangedToken("user-a"), "tenant-c", "user-a", "tenant-c").statusCode()).isEqualTo(200);
        assertThat(probe.tenant).isEqualTo("tenant-c");
        // 正文里的用户或租户与令牌、业务上下文不一致：拒绝，不查记忆。
        assertThat(send(ISSUER.exchangedToken("user-a"), "tenant-a", "user-b", "tenant-a").statusCode()).isEqualTo(403);
        assertThat(send(ISSUER.exchangedToken("user-a"), "tenant-a", "user-a", "tenant-c").statusCode()).isEqualTo(403);
        // 选别人的租户、停用用户：资源服务器直接拒绝。
        assertThat(send(ISSUER.exchangedToken("user-a"), "tenant-b", "user-a", "tenant-b").statusCode()).isEqualTo(403);
        assertThat(send(ISSUER.exchangedToken("user-disabled"), null, "user-disabled", "tenant-a").statusCode()).isEqualTo(403);
        assertThat(probe.calls).hasValue(2);
    }

    @Test
    void hmacHeadersAreIgnored() throws Exception {
        assertThat(send(null, null, "user-a", "tenant-a", true).statusCode()).isEqualTo(401);
        assertThat(send(ISSUER.exchangedToken("user-a"), "tenant-a", "user-a", "tenant-a", true).statusCode()).isEqualTo(200);
        assertThat(probe.calls).hasValue(1);
    }

    private HttpResponse<String> send(String token, String tenantHeader, String user, String tenant) throws Exception {
        return send(token, tenantHeader, user, tenant, false);
    }

    private HttpResponse<String> send(String token, String tenantHeader, String user, String tenant, boolean hmacHeaders) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agents/patient-analysis/stream"))
                .header("Content-Type", "application/json");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (tenantHeader != null) {
            request.header("X-Tenant-Id", tenantHeader);
        }
        if (hmacHeaders) {
            request.header("X-Open-Client-Id", "github-ai").header("X-Open-Timestamp", "1234567890")
                    .header("X-Open-Nonce", "synthetic-nonce").header("X-Open-Signature", "0".repeat(64));
        }
        String body = "{\"message\":\"synthetic\",\"userId\":\"" + user + "\",\"tenantId\":\"" + tenant
                + "\",\"sourceTenantId\":\"source-other\",\"patientId\":\"fixture\"}";
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 与患者分析同一路径的探针：按本轮身份经真实 Dubbo Memory 适配器查记忆，记下带出的租户。
     */
    @RestController
    static class Probe {
        final DubboMemoryAgentClient memory;
        final AtomicInteger calls = new AtomicInteger();
        volatile String tenant;
        volatile String sourceTenant;

        Probe() {
            memory = new DubboMemoryAgentClient();
            ReflectionTestUtils.setField(memory, "retrievalRpcService", (MemoryRetrievalRpcService) request -> {
                calls.incrementAndGet();
                tenant = RpcContext.getClientAttachment().getAttachment("tenantId");
                sourceTenant = request.getSourceTenantId();
                return new MemoryRetrievalRpcDTO();
            });
        }

        @PostMapping("/api/agents/patient-analysis/stream")
        String execute(@RequestBody AgentChatRequest body) {
            AgentRequestIdentity identity = AgentRequestIdentity.current(body);
            MemoryRetrievalRpcRequest request = new MemoryRetrievalRpcRequest();
            request.setTenantId(body.tenantId());
            request.setUserId(body.userId());
            request.setPatientId(body.patientId());
            request.setSourceTenantId(body.sourceTenantId());
            memory.retrieve(request, identity);
            return "verified";
        }
    }

    /**
     * 测试上下文：真实 Spring Security、utils 资源服务器与 Boot 的 issuer-uri 解码器；user 服务的业务上下文是替身。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.agent-token.fixture", havingValue = "true")
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class,
            PropertyPlaceholderAutoConfiguration.class, ConfigurationPropertiesAutoConfiguration.class,
            SecurityAutoConfiguration.class, ServletWebSecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class,
            FleetResourceServerAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class})
    static class Config {

        @Bean
        Probe probe() {
            return new Probe();
        }

        @Bean
        SecurityContextRpcService securityContextRpcService() {
            return (userId, requestedTenantId) -> {
                if ("user-disabled".equals(userId)) {
                    return SecurityContextResult.of(SecurityContextResult.Status.DISABLED);
                }
                if (!"user-a".equals(userId)) {
                    return SecurityContextResult.of(SecurityContextResult.Status.USER_NOT_FOUND);
                }
                List<String> tenants = List.of("tenant-a", "tenant-c");
                String active = requestedTenantId == null ? "tenant-a" : requestedTenantId;
                if (!tenants.contains(active)) {
                    return SecurityContextResult.of(SecurityContextResult.Status.TENANT_DENIED);
                }
                return SecurityContextResult.ok(SecurityUser.builder().userId("user-a").username("user-a").tenantId(active)
                        .deptId("dept-a").dataScope("SELF").dataScopeDeptIds(List.of()).authorities(List.of())
                        .enabled(true).tenantIds(tenants).build());
            };
        }
    }
}
