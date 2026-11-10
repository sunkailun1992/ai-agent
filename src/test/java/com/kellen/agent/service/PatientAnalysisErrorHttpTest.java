package com.kellen.agent.service;

import com.kellen.agent.controller.AgentController;
import com.kellen.agent.memory.MemoryAgentClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真 Controller/Service/ChatClient 与可控模型替身；错误事件不能变成普通成功文本。 */
@SpringBootTest(classes = PatientAnalysisErrorHttpTest.Beans.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.name=error-isolated", "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.import-check.enabled=false",
                "test.stream.error.fixture=true"})
class PatientAnalysisErrorHttpTest {
    @LocalServerPort int port;
    @Autowired FakeModel model;
    @Autowired SimpleMeterRegistry metrics;

    @BeforeEach void reset() {
        model.calls.set(0);
        metrics.clear();
        model.output = Flux.error(new IllegalStateException("synthetic-private-provider-error"));
    }

    @Test void failedModelIsAnExplicitErrorEventWithTheExistingFriendlyText() throws Exception {
        var response = post("typed-sse-v1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("X-AI-Stream-Protocol")).contains("typed-sse-v1");
        assertThat(response.body()).contains("event:error", "data:[AI Agent 调用失败] 请稍后重试")
                .doesNotContain("synthetic-private-provider-error", "synthetic-private-input", "id:");
        assertThat(metrics.get("ai.agent.model.call").tag("outcome", "failure").timer().count()).isEqualTo(1);
        assertThat(metrics.find("ai.agent.model.call").tag("outcome", "success").timer()).isNull();
    }

    @Test void partialOutputEndsWithAnErrorEvent() throws Exception {
        model.output = Flux.just(text("synthetic-partial"))
                .concatWith(Flux.error(new IllegalStateException("synthetic-private-provider-error")));
        var response = post("typed-sse-v1");
        assertThat(response.body()).containsSubsequence("data:synthetic-partial", "event:error",
                "data:[AI Agent 调用失败] 请稍后重试");
        assertThat(response.body()).doesNotContain("synthetic-private-provider-error");
    }

    @Test void legacyReaderStillReceivesOnlyTheFriendlyDataOnFailure() throws Exception {
        var response = post(null);
        String legacyText = response.body().lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5)).reduce("", String::concat);
        assertThat(legacyText).isEqualTo("[AI Agent 调用失败] 请稍后重试");
        assertThat(response.body()).contains("event:error");
    }

    @Test void successfulLegacyRequestKeepsItsTextChunks() throws Exception {
        model.output = Flux.just(text("synthetic-output"));
        var response = post(null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("data:synthetic-output").doesNotContain("event:error");
        assertThat(response.headers().firstValue("X-AI-Stream-Protocol")).contains("typed-sse-v1");
    }

    @Test void unsupportedProtocolIsRejectedBeforeModelExecution() throws Exception {
        var response = post("typed-sse-v2");
        assertThat(response.statusCode()).isEqualTo(406);
        assertThat(model.calls).hasValue(0);
    }

    @Test void retiredExecutionRoutesAreAbsent() throws Exception {
        for (String path : List.of("/api/agents/questionnaire/reply", "/api/agents/questionnaire/interpret")) {
            assertThat(post(null, path).statusCode()).isEqualTo(404);
        }
        assertThat(model.calls).hasValue(0);
    }

    private HttpResponse<String> post(String protocol) throws Exception {
        return post(protocol, "/api/agents/patient-analysis/stream");
    }

    private HttpResponse<String> post(String protocol, String path) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream");
        if (protocol != null) builder.header("X-AI-Stream-Protocol", protocol);
        return HttpClient.newHttpClient().send(builder.POST(HttpRequest.BodyPublishers.ofString(
                "{\"provider\":\"qwen\",\"message\":\"synthetic-private-input\",\"systemPrompt\":\"synthetic-system\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private ChatResponse text(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    static class FakeModel implements ChatModel {
        volatile Flux<ChatResponse> output;
        final AtomicInteger calls = new AtomicInteger();
        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { calls.incrementAndGet(); return output; }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "test.stream.error.fixture", havingValue = "true")
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import(AgentController.class)
    static class Beans {
        @Bean FakeModel model() { return new FakeModel(); }
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean AiChatClientProvider clients(FakeModel model) {
            var clients = mock(AiChatClientProvider.class);
            when(clients.selection("qwen")).thenReturn(
                    new AiChatClientProvider.CurrentClient("qwen", "synthetic-model", ChatClient.create(model)));
            return clients;
        }
        @Bean PatientAnalysisAgentService patient(AiChatClientProvider clients, SimpleMeterRegistry metrics) {
            return new PatientAnalysisAgentService(clients, new AiPromptProvider(key -> Optional.of("synthetic-prompt")),
                    new AiModelCallObserver(metrics), mock(MemoryAgentClient.class));
        }
    }
}
