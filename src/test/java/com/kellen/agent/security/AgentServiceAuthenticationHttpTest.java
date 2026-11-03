package com.kellen.agent.security;

import com.kellen.agent.config.AgentRegistryProperties;
import com.kellen.agent.config.AgentSecurityProperties;
import com.kellen.rpc.auth.AuthOpenApiRpcService;
import com.kellen.rpc.auth.OpenApiSignatureVerifyRpcRequest;
import org.apache.dubbo.rpc.RpcException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import jakarta.servlet.http.HttpServletRequest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 真 HTTP + 既有 user RPC 合成桩，覆盖验签、原始请求绑定与 SSE，不调用模型和外部认证。 */
@SpringBootTest(classes = AgentServiceAuthenticationHttpTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=agent-auth-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "ai.agent.registry.public-base-url=https://gateway.example.invalid",
                "ai.agent.security.provider-code=kellen-agent", "ai.agent.security.allowed-client-ids=kellen-ai,kellen.ai",
                "ai.agent.security.max-body-bytes=1024"})
class AgentServiceAuthenticationHttpTest {
    @LocalServerPort int port;
    @Autowired StubAuth auth;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach void reset() { auth.calls.set(0); auth.nonces.clear(); auth.unavailable = false; auth.strictAccepted = true; }

    @Test void aFalseStrictVerificationResultCannotReachTheController() throws Exception {
        auth.strictAccepted = false;
        String path = "/api/agents/echo";
        assertThat(send("POST", path, "private", signed("POST", path, "private")).statusCode()).isEqualTo(401);
    }

    @Test void anonymousCannotReachProtectedControllerOrRoot() throws Exception {
        assertThat(send("POST", "/api/agents/echo", "private", null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/agents", "", null).statusCode()).isEqualTo(401);
        assertThat(auth.calls).hasValue(0);
    }

    @Test void allowedClientReachesPostWithExactOriginalBodyAndServerOwnedProvider() throws Exception {
        String body = "{\"message\":\"测试\\n\\\"escaped\\\"\",\"caller\":\"spoofed\"}";
        HttpResponse<String> response = send("POST", "/api/agents/echo", body, signed("POST", "/api/agents/echo", body));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body);
        assertThat(auth.calls).hasValue(1);
        assertThat(auth.last.getProviderCode()).isEqualTo("kellen-agent");
    }

    @Test void allowedDottedClientIdUsesSameCallerIdentityContract() throws Exception {
        var response = send("POST", "/api/agents/echo", "ok", signed("POST", "/api/agents/echo", "ok", "kellen.ai"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
        assertThat(auth.calls).hasValue(1);
        assertThat(auth.last.getClientId()).isEqualTo("kellen.ai");
    }

    @Test void getBindsExactRawQueryAndEmptyBody() throws Exception {
        String path = "/api/agents/model?provider=qwen&mode=ocr&model=fixture%20v1";
        HttpResponse<String> response = send("GET", path, "", signed("GET", path, ""));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(auth.last.getBody()).isEqualTo("GET\n/api/agents/model\nprovider=qwen&mode=ocr&model=fixture%20v1\n"
                + "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test void bodyQueryPathAndMethodTamperingAreRejected() throws Exception {
        assertThat(send("POST", "/api/agents/echo", "changed", signed("POST", "/api/agents/echo", "original"))
                .statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/agents/echo?mode=changed", "ok", signed("POST", "/api/agents/echo?mode=original", "ok"))
                .statusCode()).isEqualTo(401);
        assertThat(send("POST", "/api/agents/other", "ok", signed("POST", "/api/agents/echo", "ok"))
                .statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/agents/model", "", signed("POST", "/api/agents/model", ""))
                .statusCode()).isEqualTo(401);
    }

    @Test void missingInvalidDuplicateAndOversizedHeadersAreRejectedBeforeRpc() throws Exception {
        String path = "/api/agents/echo";
        String[] headers = signed("POST", path, "");
        headers[7] = "invalid";
        assertThat(send("POST", path, "", headers).statusCode()).isEqualTo(401);
        headers = signed("POST", path, "");
        headers[1] = "a".repeat(129);
        assertThat(send("POST", path, "", headers).statusCode()).isEqualTo(401);
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path)).headers(signed("POST", path, ""))
                .header("X-Open-Nonce", "duplicate-nonce").POST(HttpRequest.BodyPublishers.noBody());
        assertThat(http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(auth.calls).hasValue(0);
    }

    @Test void forbiddenClientAndProviderFailuresDoNotReachController() throws Exception {
        String[] headers = signed("POST", "/api/agents/echo", "");
        headers[1] = "untrusted-client";
        assertThat(send("POST", "/api/agents/echo", "", headers).statusCode()).isEqualTo(403);
        auth.unavailable = true;
        HttpResponse<String> response = send("POST", "/api/agents/echo", "private", signed("POST", "/api/agents/echo", "private"));
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).isEqualTo("{\"code\":\"AGENT_AUTH_UNAVAILABLE\"}");
    }

    @Test void replayRejectedByExistingAuthority() throws Exception {
        String[] headers = signed("POST", "/api/agents/echo", "ok");
        assertThat(send("POST", "/api/agents/echo", "ok", headers).statusCode()).isEqualTo(200);
        assertThat(send("POST", "/api/agents/echo", "ok", headers).statusCode()).isEqualTo(401);
    }

    @Test void oversizedBodyIsRejectedBeforeRpc() throws Exception {
        String body = "x".repeat(1025);
        assertThat(send("POST", "/api/agents/echo", body, signed("POST", "/api/agents/echo", body)).statusCode()).isEqualTo(413);
        assertThat(auth.calls).hasValue(0);
    }

    @Test void chunkedOversizedBodyIsRejectedBeforeRpc() throws Exception {
        String body = "x".repeat(1025);
        HttpRequest request = HttpRequest.newBuilder(url("/api/agents/echo"))
                .headers(signed("POST", "/api/agents/echo", body))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))).build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
        assertThat(auth.calls).hasValue(0);
    }

    @Test void verifiedBodyRemainsReplayableForController() throws Exception {
        String body = "测试 fixture";
        var response = send("POST", "/api/agents/replay", body, signed("POST", "/api/agents/replay", body));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(body + "|" + body);
    }

    @Test void sseAsyncDispatchDoesNotVerifyNonceTwice() throws Exception {
        HttpResponse<String> response = send("POST", "/api/agents/stream", "ok", signed("POST", "/api/agents/stream", "ok"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("data:fixture");
        assertThat(auth.calls).hasValue(1);
    }

    @Test void publicNonAgentEndpointIsUnaffected() throws Exception {
        assertThat(send("GET", "/health-fixture", "", null).statusCode()).isEqualTo(200);
        assertThat(auth.calls).hasValue(0);
    }

    @Test void nonCanonicalAgentPathsCannotBypassVerification() throws Exception {
        for (String path : new String[]{"/api/agents//echo", "/api//agents/echo", "/api/agents/./echo", "/api/%61gents/echo", "/api/agents/echo;v=1"}) {
            int status = send("POST", path, "", signed("POST", path, "")).statusCode();
            assertThat(status).as(path).isBetween(400, 499);
        }
        assertThat(auth.calls).hasValue(0);
    }

    @Test void urlEncodedFormRetainsQueryAndRepeatedBodyParameters() throws Exception {
        String path = "/api/agents/form?item=query";
        String body = "item=first&item=%E6%B5%8B%E8%AF%95";
        HttpRequest request = HttpRequest.newBuilder(url(path)).headers(signed("POST", path, body))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("query|first|测试");
    }

    @Test void sharedPostVectorMatchesAiCaller() {
        String body = "{\"message\":\"测试\\n\\\"escaped\\\"\"}";
        String envelope = AgentServiceAuthenticationFilter.envelope("POST", "/api/agents/report-analysis", "", body.getBytes(StandardCharsets.UTF_8));
        assertThat(envelope).endsWith("a55defebb71cfce9b279b3993d5ffb74635429af756ea2d7cb7c14e690fe9b91");
        assertThat(StubAuth.hmac("kellen-ai\n1792473600123\n7b2c6c02-3c30-4eb0-a50e-25ed31fce99f\n" + envelope))
                .isEqualTo("e4c951b9f5a5510af9004c289ec8ae3c2d4b0064e288f8170934d8e79a11d6ed");
    }

    URI url(String path) { return URI.create("http://localhost:" + port + path); }
    HttpResponse<String> send(String method, String path, String body, String[] headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url(path));
        if (headers != null) { request.headers(headers); }
        request.method(method, body.isEmpty() ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    String[] signed(String method, String path, String body) {
        return signed(method, path, body, "kellen-ai");
    }
    String[] signed(String method, String path, String body, String clientId) {
        URI uri = URI.create(path);
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String envelope = AgentServiceAuthenticationFilter.envelope(method, uri.getRawPath(),
                uri.getRawQuery() == null ? "" : uri.getRawQuery(), body.getBytes(StandardCharsets.UTF_8));
        return new String[]{"X-Open-Client-Id", clientId, "X-Open-Timestamp", timestamp,
                "X-Open-Nonce", nonce, "X-Open-Signature", StubAuth.hmac(clientId + "\n" + timestamp + "\n" + nonce + "\n" + envelope)};
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @EnableConfigurationProperties({AgentRegistryProperties.class, AgentSecurityProperties.class})
    @Import({AgentServiceAuthenticationFilter.class, FixtureController.class})
    static class Config {
        @Bean StubAuth auth() { return new StubAuth(); }
        @Bean AgentSignatureVerifier verifier(StubAuth auth) { return new AgentSignatureVerifier(auth); }
    }

    @RestController
    static class FixtureController {
        @PostMapping({"/api/agents/echo", "/api/agents/other"}) String echo(@RequestBody String body) { return body; }
        @PostMapping("/api/agents/replay") String replay(HttpServletRequest request) throws java.io.IOException {
            String first = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return first + "|" + request.getReader().readLine();
        }
        @GetMapping("/api/agents/model") String model() { return "fixture-model"; }
        @PostMapping(value="/api/agents/stream", produces=MediaType.TEXT_EVENT_STREAM_VALUE) SseEmitter stream() {
            SseEmitter emitter = new SseEmitter();
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try { emitter.send("fixture"); emitter.complete(); } catch (Exception exception) { emitter.completeWithError(exception); }
            });
            return emitter;
        }
        @PostMapping("/api/agents/form") String form(HttpServletRequest request) { return String.join("|", request.getParameterValues("item")); }
        @GetMapping("/health-fixture") String health() { return "ok"; }
    }

    static class StubAuth implements AuthOpenApiRpcService {
        final AtomicInteger calls = new AtomicInteger();
        final Set<String> nonces = ConcurrentHashMap.newKeySet();
        volatile boolean unavailable;
        volatile boolean strictAccepted = true;
        volatile OpenApiSignatureVerifyRpcRequest last;
        @Override public void verifyOpenApiSignature(OpenApiSignatureVerifyRpcRequest request) {
            throw new AssertionError("Agent must not use the optional-signature RPC");
        }
        @Override public boolean verifyRequiredOpenApiSignature(OpenApiSignatureVerifyRpcRequest request) {
            if (!strictAccepted) { return false; }
            calls.incrementAndGet(); last = request;
            if (unavailable) { throw new RpcException(RpcException.NETWORK_EXCEPTION, "synthetic unavailable"); }
            String expected = hmac(request.getClientId() + "\n" + request.getTimestamp() + "\n" + request.getNonce() + "\n" + request.getBody());
            if (!expected.equals(request.getSignature()) || !nonces.add(request.getNonce())) { throw new SecurityException("synthetic rejection"); }
            return true;
        }
        static String hmac(String text) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec("synthetic-agent-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }
    }
}
