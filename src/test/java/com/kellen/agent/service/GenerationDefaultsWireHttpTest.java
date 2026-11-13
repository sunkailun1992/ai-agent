package com.kellen.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kellen.agent.config.AgentProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 真实 Spring Boot 绑定、生产 Provider、Spring AI/OpenAI SDK 与自有回环 HTTP；不访问厂商。 */
@Timeout(15)
class GenerationDefaultsWireHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ANSWER = "synthetic-answer";
    private static final List<String> DEPLOYMENT_FIELDS = List.of(
            "temperature", "top_p", "max_tokens", "max_completion_tokens", "reasoning_effort",
            "thinking", "enable_thinking", "extra_body");
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer upstream;
    private java.util.concurrent.ExecutorService executor;
    private boolean fail;
    private boolean usageWithChoice;
    private String terminalFinish = "stop";
    private String usageFinish;

    @BeforeEach void start() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        upstream.setExecutor(executor);
        upstream.createContext("/", exchange -> {
            JsonNode body = JSON.readTree(exchange.getRequestBody().readAllBytes());
            received.add(new Received(exchange.getRequestURI().getPath(), body));
            boolean stream = body.path("stream").asBoolean();
            String response;
            if (fail) {
                response = "{\"error\":{\"message\":\"synthetic-unavailable\",\"type\":\"server_error\",\"code\":\"unavailable\"}}";
            } else if (stream) {
                response = """
                        data: {"id":"synthetic","object":"chat.completion.chunk","created":1,"model":"synthetic","choices":[{"index":0,"delta":{"role":"assistant","content":"synthetic-answer"},"finish_reason":null}]}

                        data: {"id":"synthetic","object":"chat.completion.chunk","created":1,"model":"synthetic","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: {"id":"synthetic","object":"chat.completion.chunk","created":1,"model":"synthetic","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                        data: [DONE]

                        """;
                response = response.replace("\"finish_reason\":\"stop\"", "\"finish_reason\":\"" + terminalFinish + "\"");
                if (usageWithChoice) {
                    String finish = usageFinish == null ? "null" : "\"" + usageFinish + "\"";
                    response = response.replace("\"choices\":[],\"usage\"",
                            "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":" + finish + "}],\"usage\"");
                }
            } else {
                response = """
                        {"id":"synthetic","object":"chat.completion","created":1,"model":"synthetic",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"synthetic-answer"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", fail || !stream ? "application/json" : "text/event-stream");
            exchange.sendResponseHeaders(fail ? 503 : 200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            } finally {
                exchange.close();
            }
        });
        upstream.start();
    }

    @AfterEach void stop() throws InterruptedException {
        upstream.stop(0);
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "deepseek,DEPLOYMENT,false", "qwen,DEPLOYMENT,false",
            "deepseek,DEPLOYMENT,true", "qwen,DEPLOYMENT,true",
            "deepseek,SERVICE,false", "qwen,SERVICE,false",
            "deepseek,SERVICE,true", "qwen,SERVICE,true",
            "deepseek,DEFAULT,false", "qwen,DEFAULT,false",
            "deepseek,DEFAULT,true", "qwen,DEFAULT,true"
    })
    void generationSourceControlsTheActualSdkRequest(String provider, String source, boolean stream) {
        context(provider, source).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AgentProperties.class);
            assertThat(properties.deepseek().baseUrl()).isEqualTo(origin() + "/v1");
            assertThat(properties.qwen().baseUrl()).isEqualTo(origin() + "/v1");
            ChatClient client = context.getBean(AiChatClientProvider.class).selection(provider).chatClient();
            assertThat(invoke(client, stream)).isEqualTo(ANSWER);
            assertThat(received).hasSize(1);
            Received request = received.get(0);
            assertThat(request.path()).isEqualTo("/v1/chat/completions");
            JsonNode body = request.body();
            assertThat(body.path("model").asText()).isEqualTo("synthetic-" + provider + "-alias");
            assertThat(body.path("stream").asBoolean()).isEqualTo(stream);
            if (stream) assertThat(body.at("/stream_options/include_usage").asBoolean()).isTrue();
            if (source.equals("DEPLOYMENT")) {
                for (String field : DEPLOYMENT_FIELDS) assertThat(body.has(field)).as(field + " must be absent").isFalse();
            } else {
                assertThat(body.path("temperature").asDouble()).isEqualTo(0.47);
                assertThat(body.path("top_p").asDouble()).isEqualTo(0.76);
                assertThat(body.path("max_tokens").asInt()).isEqualTo(2304);
                if (provider.equals("deepseek")) {
                    assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");
                    assertThat(body.at("/thinking/type").asText()).isEqualTo("disabled");
                } else {
                    assertThat(body.has("reasoning_effort")).isFalse();
                    assertThat(body.has("thinking")).isFalse();
                }
            }
        });
    }

    @ParameterizedTest
    @CsvSource({
            "deepseek,DEPLOYMENT,false", "qwen,DEPLOYMENT,false",
            "deepseek,DEPLOYMENT,true", "qwen,DEPLOYMENT,true",
            "deepseek,SERVICE,false", "qwen,SERVICE,false",
            "deepseek,SERVICE,true", "qwen,SERVICE,true"
    })
    void a503IsNotRetriedByTheSyncOrAsyncSdk(String provider, String source, boolean stream) {
        fail = true;
        context(provider, source).run(context -> {
            assertThat(context).hasNotFailed();
            ChatClient client = context.getBean(AiChatClientProvider.class).selection(provider).chatClient();
            assertThatThrownBy(() -> invoke(client, stream)).isInstanceOf(RuntimeException.class);
            assertThat(received).hasSize(1);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void anUnknownGenerationSourceFailsBindingBeforeAnyModelRequest(String provider) {
        context(provider, "UNRECOGNIZED").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class);
            assertThat(received).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"deepseek", "qwen"})
    void directlyConstructedNullSourceKeepsServiceDefaults(String provider) {
        if (provider.equals("deepseek")) {
            var properties = new AgentProperties.DeepSeekProperties(origin(), "synthetic", "synthetic", 0.47, 0.76,
                    2304, 180, new AgentProperties.DeepSeekThinkingProperties(false, "high"), null);
            assertThat(properties.generationDefaultsSource()).isEqualTo(AgentProperties.GenerationDefaultsSource.SERVICE);
        } else {
            var properties = new AgentProperties.QwenProperties(origin(), "synthetic", "synthetic", 0.47, 0.76,
                    2304, 180, null);
            assertThat(properties.generationDefaultsSource()).isEqualTo(AgentProperties.GenerationDefaultsSource.SERVICE);
        }
        assertThat(received).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "stop,NO_CHOICE,stop", "stop,NULL,stop", "stop,UNKNOWN,stop", "stop,other,stop",
            "stop,length,length", "UNKNOWN,stop,stop", "UNKNOWN,UNKNOWN,other"
    })
    void terminalReasonSurvivesRealSdkUsageTailsInTheProductionObserver(String terminal, String tail, String expected) {
        terminalFinish = terminal;
        usageWithChoice = !tail.equals("NO_CHOICE");
        usageFinish = tail.equals("NULL") ? null : tail;
        var exporter = InMemorySpanExporter.create();
        try (var telemetry = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), telemetry.get("synthetic-test"));
            context("qwen", "DEPLOYMENT").run(context -> {
                assertThat(context).hasNotFailed();
                ChatClient client = context.getBean(AiChatClientProvider.class).selection("qwen").chatClient();
                var responses = observer.observeStream("qwen", "synthetic-qwen-alias", "patient_analysis",
                        () -> client.prompt("synthetic-input").stream().chatResponse()).collectList().block(Duration.ofSeconds(8));
                assertThat(received).hasSize(1);
                assertThat(responses).isNotEmpty();
                if (usageWithChoice && (usageFinish == null || tail.equals("UNKNOWN") || tail.equals("other"))) {
                    // Spring AI 2.0.2 起把 SDK 的空值或未知枚举报告为 null，原始 _UNKNOWN 只留在消息元数据；必须经过真实链路再验证合并规则。
                    var last = responses.get(responses.size() - 1).getResult();
                    assertThat(last.getMetadata().getFinishReason()).isNull();
                    assertThat(String.valueOf(last.getOutput().getMetadata().get("finishReason"))).isEqualTo("_UNKNOWN");
                }
                var model = exporter.getFinishedSpanItems().stream()
                        .filter(span -> span.getName().equals("ai.model.call")).toList();
                assertThat(model).hasSize(1);
                assertThat(model.get(0).getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("success");
                assertThat(model.get(0).getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(1L);
                assertThat(model.get(0).getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(1L);
                assertThat(model.get(0).getAttributes().get(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons")))
                        .containsExactly(expected);
            });
        }
    }

    private String invoke(ChatClient client, boolean stream) {
        if (stream) return String.join("", client.prompt("synthetic-input").stream().content().collectList().block(Duration.ofSeconds(8)));
        return client.prompt("synthetic-input").call().content();
    }

    private String origin() {
        return "http://127.0.0.1:" + upstream.getAddress().getPort();
    }

    private ApplicationContextRunner context(String provider, String source) {
        var runner = new ApplicationContextRunner().withUserConfiguration(Beans.class)
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withPropertyValues(
                        "ai.agent.deepseek.base-url=" + origin() + "/v1", "ai.agent.qwen.base-url=" + origin() + "/v1",
                        "ai.agent.deepseek.api-key=synthetic-scoped-key", "ai.agent.qwen.api-key=synthetic-scoped-key",
                        "ai.agent.deepseek.model=synthetic-deepseek-alias", "ai.agent.qwen.model=synthetic-qwen-alias",
                        "ai.agent.deepseek.temperature=0.47", "ai.agent.qwen.temperature=0.47",
                        "ai.agent.deepseek.top-p=0.76", "ai.agent.qwen.top-p=0.76",
                        "ai.agent.deepseek.max-tokens=2304", "ai.agent.qwen.max-tokens=2304",
                        "ai.agent.deepseek.thinking.enabled=false", "ai.agent.deepseek.thinking.reasoning-effort=high");
        return source.equals("DEFAULT") ? runner : runner.withPropertyValues("ai.agent." + provider + ".generation-defaults-source=" + source);
    }

    record Received(String path, JsonNode body) { }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    @Import(AiChatClientProvider.class)
    static class Beans {
        @Bean ObservationRegistry observationRegistry() { return ObservationRegistry.create(); }
        @Bean ChatClient.Builder googleBuilder() { return ChatClient.builder(mock(ChatModel.class)); }
    }
}
