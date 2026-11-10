package com.kellen.agent.service;

import com.kellen.agent.dto.AgentChatRequest;
import com.kellen.agent.memory.MemoryAgentClient;
import com.kellen.rpc.memory.MemoryCandidateRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryWriteRpcDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.core.env.Environment;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 Spring Bean、ChatClient 与外部模型替身，验证内部失败不会被友好文本覆盖。 */
@SpringBootTest(classes = PatientAnalysisObservationTests.Beans.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.import=file:../utils/ops/github-dev/nacos/DEFAULT_GROUP/logging.yml")
@EnabledIf("sharedLoggingAvailable")
@ExtendWith(OutputCaptureExtension.class)
class PatientAnalysisObservationTests {
    @Autowired PatientAnalysisAgentService service;
    @Autowired SimpleMeterRegistry metrics;
    @Autowired FakeModel model;
    @Autowired Environment environment;

    static boolean sharedLoggingAvailable() {
        return java.nio.file.Files.isRegularFile(java.nio.file.Path.of("../utils/ops/github-dev/nacos/DEFAULT_GROUP/logging.yml"));
    }

    @Test void failedModelPropagatesFailureWithoutLoggingExceptionContent(CapturedOutput output) {
        assertThat(environment.getProperty("logging.level.org.springframework.ai.chat.model.MessageAggregator")).isEqualTo("OFF");
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        model.output = Flux.error(new IllegalStateException("sensitive-error-marker"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.stream(request()).collectList().block())
                .isInstanceOf(IllegalStateException.class).hasMessage("sensitive-error-marker");
        assertThat(output).doesNotContain("sensitive-error-marker", "private-user-marker");
        assertThat(metrics.get("ai.agent.model.call").tag("outcome", "failure").timer().count()).isEqualTo(1);
    }

    @Test void cancellingModelIsRecordedAsCancelled() {
        model.output = Flux.never();
        var disposable = service.stream(request()).subscribe();
        disposable.dispose();
        assertThat(metrics.get("ai.agent.model.call").tag("outcome", "cancelled").timer().count()).isEqualTo(1);
    }

    private AgentChatRequest request() {
        return new AgentChatRequest("business-session", "qwen", "synthetic-system", "private-user-marker", "", "", "",
                java.util.List.of(), "test", "business-trace", "", "", "", "");
    }

    static class FakeModel implements ChatModel {
        volatile Flux<ChatResponse> output = Flux.never();
        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) { return output; }
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean SimpleMeterRegistry metrics() { return new SimpleMeterRegistry(); }
        @Bean FakeModel model() { return new FakeModel(); }
        @Bean AiModelCallObserver observer(SimpleMeterRegistry metrics) { return new AiModelCallObserver(metrics); }
        @Bean PatientAnalysisAgentService service(FakeModel model, AiModelCallObserver observer) {
            var provider = mock(AiChatClientProvider.class);
            when(provider.selection("qwen")).thenReturn(new AiChatClientProvider.CurrentClient("qwen", "synthetic-model", ChatClient.create(model)));
            var memory = new MemoryAgentClient() {
                @Override public MemoryRetrievalRpcDTO retrieve(MemoryRetrievalRpcRequest request) {
                    var response = new MemoryRetrievalRpcDTO(); response.setContext(""); return response;
                }
                @Override public MemoryWriteRpcDTO submitCandidate(MemoryCandidateRpcRequest request) { return new MemoryWriteRpcDTO(); }
            };
            return new PatientAnalysisAgentService(provider, new AiPromptProvider(key -> Optional.of("synthetic-prompt")), observer, memory);
        }
    }
}
