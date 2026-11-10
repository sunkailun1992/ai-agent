package com.kellen.agent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实生产配置、SDK 1.58.0 与回环 HTTP；任何请求前先断言 baseUrl，禁止误打官方上游。 */
@Timeout(15)
class GoogleNativeTransportHttpTest {
    private static final String KEY = "synthetic-native-scoped-vkey";
    private static final String MARKER = "synthetic-native-private-content";
    private static final String RESPONSE = """
            {"candidates":[{"index":0,"content":{"role":"model","parts":[
            {"text":"synthetic-thought","thought":true},{"text":"synthetic-answer"},
            {"functionCall":{"name":"synthetic_lookup","args":{"query":"synthetic"}}}]},"finishReason":"STOP"}],
            "usageMetadata":{"promptTokenCount":3,"candidatesTokenCount":2,"thoughtsTokenCount":1,"totalTokenCount":6},
            "modelVersion":"fixture","responseId":"synthetic-native-response"}
            """;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private java.util.concurrent.ExecutorService executor;
    private boolean fail;

    @BeforeEach void start() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        upstream.setExecutor(executor);
        upstream.createContext("/", exchange -> {
            received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(),
                    exchange.getRequestHeaders().getFirst("x-goog-api-key"), exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            boolean stream = exchange.getRequestURI().getPath().endsWith(":streamGenerateContent");
            byte[] response = (fail ? "{\"error\":{\"code\":503,\"message\":\"synthetic-upstream-failure\",\"status\":\"UNAVAILABLE\"}}"
                    : stream ? "data: " + RESPONSE.replace("\n", "") + "\n\n" : RESPONSE).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", stream && !fail ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(fail ? 503 : 200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            finally { exchange.close(); }
        });
        upstream.start();
    }

    @AfterEach void stop() throws InterruptedException {
        upstream.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test void nativeGeneratePreservesPrefixScopedHeaderThinkingUsageAndParts() {
        context(baseUrl() + "/").run(context -> {
            assertThat(context).hasNotFailed();
            Client client = checkedClient(context.getBean(Client.class));
            var response = client.models.generateContent("fixture", MARKER, config());
            assertThat(received).hasSize(1);
            assertRequest(received.get(0), "generateContent");
            var parts = response.candidates().orElseThrow().get(0).content().orElseThrow().parts().orElseThrow();
            assertThat(parts.get(0).thought()).contains(true);
            assertThat(parts.get(1).text()).contains("synthetic-answer");
            assertThat(parts.get(2).functionCall().orElseThrow().name()).contains("synthetic_lookup");
            assertThat(response.usageMetadata().orElseThrow().thoughtsTokenCount()).contains(1);
            assertThat(response.usageMetadata().orElseThrow().totalTokenCount()).contains(6);
        });
    }

    @Test void nativeStreamUsesTheSamePrefixAndPreservesNativeFrames() {
        context(baseUrl()).run(context -> {
            assertThat(context).hasNotFailed();
            Client client = checkedClient(context.getBean(Client.class));
            var responses = new ArrayList<com.google.genai.types.GenerateContentResponse>();
            try (var stream = client.models.generateContentStream("fixture", MARKER, config())) {
                stream.forEach(responses::add);
            }
            assertThat(responses).hasSize(1);
            assertThat(received).hasSize(1);
            assertRequest(received.get(0), "streamGenerateContent");
            assertThat(received.get(0).query()).isEqualTo("alt=sse");
            assertThat(responses.get(0).candidates().orElseThrow().get(0).content().orElseThrow().parts().orElseThrow().get(0).thought()).contains(true);
            assertThat(responses.get(0).usageMetadata().orElseThrow().thoughtsTokenCount()).contains(1);
        });
    }

    @Test void sdk503MakesOneUpstreamAttempt() {
        fail = true;
        context(baseUrl()).run(context -> {
            assertThat(context).hasNotFailed();
            Client client = checkedClient(context.getBean(Client.class));
            HttpOptions options = (HttpOptions) ReflectionTestUtils.getField(ReflectionTestUtils.getField(client, "apiClient"), "httpOptions");
            assertThat(options.retryOptions().orElseThrow().attempts()).contains(1);
            assertThatThrownBy(() -> client.models.generateContent("fixture", MARKER, config())).isInstanceOf(RuntimeException.class);
            assertThat(received).hasSize(1);
        });
    }

    @Test void springGoogleModel503DoesNotAddAnOuterRetry() {
        fail = true;
        var unrelatedRetry = new org.springframework.core.retry.RetryTemplate(
                org.springframework.core.retry.RetryPolicy.withMaxRetries(2));
        context(baseUrl()).withBean(org.springframework.core.retry.RetryTemplate.class, () -> unrelatedRetry).run(context -> {
            assertThat(context).hasNotFailed();
            checkedClient(context.getBean(Client.class));
            assertThat(context.getBean(org.springframework.core.retry.RetryTemplate.class)).isSameAs(unrelatedRetry);
            assertThat(ReflectionTestUtils.getField(context.getBean(GoogleGenAiChatModel.class), "retryTemplate"))
                    .isNotSameAs(unrelatedRetry);
            assertThatThrownBy(() -> context.getBean(GoogleGenAiChatModel.class).call(new Prompt(MARKER)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(received).hasSize(1);
        });
    }

    private ApplicationContextRunner context(String base) {
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                GoogleStreamingTransportConfiguration.class, GoogleGenAiChatAutoConfiguration.class))
                .withUserConfiguration(Tools.class)
                .withPropertyValues("spring.ai.google.genai.api-key=" + KEY,
                        "spring.ai.google.genai.chat.options.model=fixture", "ai.agent.google.base-url=" + base);
    }

    private Client checkedClient(Client client) {
        Object configured = ReflectionTestUtils.invokeMethod(client, "baseUrl");
        assertThat(configured).isEqualTo(java.util.Optional.of(baseUrl()));
        return client;
    }

    private void assertRequest(Received request, String operation) {
        assertThat(request.path()).isEqualTo("/proxy/gemini/v1beta/models/fixture:" + operation);
        assertThat(request.key()).isEqualTo(KEY);
        assertThat(request.authorization()).isNull();
        try {
            JsonNode body = new ObjectMapper().readTree(request.body());
            assertThat(body.at("/contents/0/parts/0/text").asText()).isEqualTo(MARKER);
            assertThat(body.at("/generationConfig/thinkingConfig/includeThoughts").asBoolean()).isTrue();
            assertThat(body.at("/generationConfig/thinkingConfig/thinkingBudget").asInt()).isEqualTo(1024);
            assertThat(body.at("/tools/0/functionDeclarations/0/name").asText()).isEqualTo("synthetic_lookup");
        } catch (IOException error) { throw new AssertionError("Synthetic request was not native JSON", error); }
    }

    private GenerateContentConfig config() {
        return GenerateContentConfig.fromJson("""
                {"thinkingConfig":{"includeThoughts":true,"thinkingBudget":1024},
                "tools":[{"functionDeclarations":[{"name":"synthetic_lookup","description":"synthetic fixture", "parameters":{"type":"OBJECT","properties":{"query":{"type":"STRING"}}}}]}]}
                """);
    }

    private String baseUrl() { return "http://127.0.0.1:" + upstream.getAddress().getPort() + "/proxy/gemini"; }
    private record Received(String path, String query, String key, String authorization, String body) { }
    @TestConfiguration(proxyBeanMethods = false)
    static class Tools {
        @Bean ToolCallingManager toolCallingManager() { return ToolCallingManager.builder().build(); }
    }
}
