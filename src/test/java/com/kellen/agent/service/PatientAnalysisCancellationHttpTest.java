package com.kellen.agent.service;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.kellen.agent.controller.AgentController;
import com.kellen.agent.memory.MemoryAgentClient;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真 Servlet SSE、PatientAnalysisAgentService、ChatClient 与厂商 SDK；厂商端仅为回环延迟 HTTP 替身。 */
@SpringBootTest(classes = PatientAnalysisCancellationHttpTest.Beans.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=cancel-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "spring.mvc.async.request-timeout=20000", "test.cancel.fixture=true"})
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PatientAnalysisCancellationHttpTest {
    private static final String TRACE = "1234567890abcdef1234567890abcdef";
    private static final String PARENT = "1234567890abcdef";
    @LocalServerPort int port;
    @Autowired ModelFixture model;
    @Autowired AiChatClientProvider clients;
    @Autowired InMemorySpanExporter exporter;
    @Autowired SimpleMeterRegistry metrics;
    private HttpServer upstream;
    private java.util.concurrent.ExecutorService executor;
    private volatile boolean stopping;
    private volatile boolean google;
    private final CountDownLatch transportClosed = new CountDownLatch(1);
    private final AtomicInteger sent = new AtomicInteger();
    private final List<ch.qos.logback.classic.spi.ILoggingEvent> logs = new CopyOnWriteArrayList<>();
    private ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> appender;

    @BeforeEach void prepare() throws IOException {
        exporter.reset(); metrics.clear(); model.cancelled = new AtomicInteger(); model.completed = new AtomicInteger();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); upstream.setExecutor(executor);
        upstream.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                // 不发送结束帧：客户端关闭时，模型 HTTP 请求一定仍处于活动状态。
                while (!stopping) {
                    String chunk = google
                            ? "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"synthetic-active-token \"}],\"role\":\"model\"},\"index\":0}],\"modelVersion\":\"fixture\"}"
                            : "{\"id\":\"fixture\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"synthetic-active-token \"},\"finish_reason\":null}]}";
                    body.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8)); body.flush(); sent.incrementAndGet();
                    Thread.sleep(60);
                }
            } catch (IOException disconnected) { transportClosed.countDown(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        upstream.start();
        appender = new ch.qos.logback.core.AppenderBase<>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) { logs.add(event); }
        };
        appender.start(); logger().addAppender(appender);
    }

    @AfterEach void stop() throws InterruptedException {
        stopping = true; upstream.stop(0); executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        logger().detachAppender(appender); appender.stop();
        if (model.delegate instanceof GoogleGenAiChatModel googleModel) {
            try { googleModel.destroy(); } catch (Exception error) { throw new AssertionError(error); }
        }
    }

    @Test void googleDisconnectCancelsAnActiveModelHttpStream() throws Exception {
        google = true;
        Client client = Client.builder().apiKey("synthetic-fixture")
                .clientOptions(com.google.genai.types.ClientOptions.builder().customHttpClient(StreamingCallCancellation.googleHttpClient()).build())
                .httpOptions(HttpOptions.builder().baseUrl(upstreamUrl()).apiVersion("v1beta").build()).build();
        model.delegate = GoogleGenAiChatModel.builder().genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model("fixture").build()).build();
        cancellation();
    }

    @Test void openAiDisconnectCancelsAnActiveModelHttpStream() throws Exception {
        model.delegate = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl(upstreamUrl()).apiKey("synthetic-fixture").model("fixture")
                .maxRetries(0).timeout(Duration.ofSeconds(10)).build())
                .httpClientBuilderCustomizer(builder -> builder.interceptor(StreamingCallCancellation.openAiInterceptor())).build();
        cancellation();
    }

    private String upstreamUrl() { return "http://127.0.0.1:" + upstream.getAddress().getPort(); }

    private void cancellation() throws Exception {
        when(clients.selection("qwen")).thenReturn(new AiChatClientProvider.CurrentClient("qwen", "fixture", ChatClient.builder(model).defaultAdvisors(new StreamingCancellationAdvisor()).build()));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/agents/patient-analysis/stream"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"provider\":\"qwen\",\"message\":\"synthetic-private-input\",\"systemPrompt\":\"synthetic-private-system\"}"))
                .build();
        {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<java.io.InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                assertThat(reader.readLine()).contains("synthetic-active-token");
                assertThat(model.completed).hasValue(0); assertThat(model.cancelled).hasValue(0);
                assertThat(sent.get()).isPositive();
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(model.cancelled).hasValue(1));
            assertThat(model.completed).hasValue(0);
            assertThat(transportClosed.await(3, TimeUnit.SECONDS)).as("取消必须到达 SDK HTTP 连接，不能只丢弃下游数据").isTrue();
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(exporter.getFinishedSpanItems()).hasSize(3));
            var execution = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("ai.agent.execution")).findFirst().orElseThrow();
            var call = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("ai.model.call")).findFirst().orElseThrow();
            assertThat(execution.getParentSpanId()).isEqualTo(PARENT); assertThat(execution.getTraceId()).isEqualTo(TRACE);
            assertThat(call.getParentSpanId()).isEqualTo(execution.getSpanId()); assertThat(call.getTraceId()).isEqualTo(TRACE);
            assertThat(model.subscribedSpanId).isEqualTo(call.getSpanId());
            assertThat(call.getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("cancelled");
            assertThat(execution.getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("cancelled");
            assertThat(metrics.get("ai.agent.model.call").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
            assertThat(logs).hasSize(1);
            var fields = logs.get(0).getKeyValuePairs().stream().collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(fields).containsEntry("outcome", "cancelled");
            assertThat(call.getAttributes().get(AttributeKey.booleanKey("fleet.ai.usage.final"))).isFalse();
            assertThat(fields.toString()).doesNotContain("synthetic-private-input", "synthetic-private-system", "synthetic-active-token");
        }
    }

    private ch.qos.logback.classic.Logger logger() {
        return (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiModelCallObserver.class);
    }

    static class ModelFixture implements ChatModel {
        volatile ChatModel delegate;
        volatile String subscribedSpanId;
        volatile AtomicInteger cancelled = new AtomicInteger(), completed = new AtomicInteger();
        @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() { return delegate.getOptions(); }
        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            subscribedSpanId = Span.current().getSpanContext().getSpanId();
            AtomicInteger cancelled = this.cancelled, completed = this.completed;
            return delegate.stream(prompt).doOnCancel(cancelled::incrementAndGet).doOnComplete(completed::incrementAndGet);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "test.cancel.fixture", havingValue = "true")
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import(AgentController.class)
    static class Beans {
        @Bean ModelFixture model() { return new ModelFixture(); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean InMemorySpanExporter exporter() { return InMemorySpanExporter.create(); }
        @Bean(destroyMethod = "close") SdkTracerProvider telemetry(InMemorySpanExporter exporter) {
            return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        }
        @Bean AiModelCallObserver observer(SimpleMeterRegistry metrics, SdkTracerProvider telemetry) {
            return new AiModelCallObserver(metrics, telemetry.get("cancel-http-test"));
        }
        @Bean AiChatClientProvider clients() { return mock(AiChatClientProvider.class); }
        @Bean PatientAnalysisAgentService patient(AiChatClientProvider clients, AiModelCallObserver observer) {
            var memory = mock(MemoryAgentClient.class);
            var response = new MemoryRetrievalRpcDTO(); response.setContext("");
            when(memory.retrieve(any())).thenReturn(response);
            return new PatientAnalysisAgentService(clients, new AiPromptProvider(key -> Optional.of("synthetic-prompt")), observer, memory);
        }
        @Bean Filter syntheticRemoteParent() {
            return (request, response, chain) -> {
                var parent = Span.wrap(SpanContext.createFromRemoteParent(TRACE, PARENT, TraceFlags.getSampled(), TraceState.getDefault()));
                try (Scope ignored = parent.makeCurrent()) { chain.doFilter(request, response); }
            };
        }
    }
}
