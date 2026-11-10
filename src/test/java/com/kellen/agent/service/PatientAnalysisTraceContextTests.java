package com.kellen.agent.service;

import com.kellen.agent.dto.AgentChatRequest;
import com.kellen.agent.memory.MemoryAgentClient;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 直接覆盖真实 service 的外层 defer，避免只测 observer 而漏掉父上下文捕获时点。 */
class PatientAnalysisTraceContextTests {
    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider telemetry = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final FakeModel model = new FakeModel();
    private final PatientAnalysisAgentService service = service();

    @AfterEach void closeTelemetry() {
        telemetry.close();
        metrics.close();
    }

    @Test void assemblyParentWinsOverUnrelatedSubscriberAndSubscriberContextIsRestored() {
        Span assemblyParent = telemetry.get("test").spanBuilder("assembly-parent").startSpan();
        Span subscriberParent = telemetry.get("test").spanBuilder("unrelated-subscriber").startSpan();
        Flux<String> stream = assembleUnder(assemblyParent);
        try (Scope ignored = subscriberParent.makeCurrent()) {
            assertThat(stream.collectList().block()).containsExactly("synthetic-output");
            assertThat(Span.current()).isSameAs(subscriberParent);
        }
        var execution = executions().get(0);
        assertThat(execution.getParentSpanId()).isEqualTo(assemblyParent.getSpanContext().getSpanId());
        assertThat(execution.getTraceId()).isEqualTo(assemblyParent.getSpanContext().getTraceId());
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        assemblyParent.end();
        subscriberParent.end();
    }

    @Test void streamsWithDifferentAssemblyParentsReuseWorkerWithoutLeakingContext() {
        var worker = Schedulers.newSingle("patient-parent-regression");
        try {
            for (int index = 0; index < 2; index++) {
                Span parent = telemetry.get("test").spanBuilder("assembly-parent-" + index).startSpan();
                Flux<String> stream = assembleUnder(parent);
                assertThat(stream.subscribeOn(worker).collectList().block()).containsExactly("synthetic-output");
                var executions = exporter.getFinishedSpanItems().stream()
                        .filter(span -> span.getName().equals("ai.agent.execution")).toList();
                assertThat(executions).hasSize(index + 1);
                assertThat(executions.get(index).getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
                assertThat(executions.get(index).getTraceId()).isEqualTo(parent.getSpanContext().getTraceId());
                assertThat(Mono.fromCallable(() -> Span.current().getSpanContext().isValid())
                        .subscribeOn(worker).block()).isFalse();
                parent.end();
            }
        } finally {
            worker.dispose();
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
    }

    @Test void actualServiceRecordsSuccessFailureAndCancellationOnceWithOriginalParent() {
        Span parent = telemetry.get("test").spanBuilder("assembly-parent").startSpan();
        assertThat(assembleUnder(parent).collectList().block()).containsExactly("synthetic-output");
        model.output = Flux.error(new IllegalStateException("synthetic-model-failure"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> assembleUnder(parent).collectList().block())
                .isInstanceOf(IllegalStateException.class).hasMessage("synthetic-model-failure");
        model.output = Flux.never();
        var subscription = assembleUnder(parent).subscribe();
        subscription.dispose();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(executions()).hasSize(3));
        assertThat(executions()).extracting(span -> span.getAttributes().get(AttributeKey.stringKey("outcome")))
                .containsExactly("success", "failure", "cancelled");
        for (var execution : executions()) {
            assertThat(execution.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(execution.getTraceId()).isEqualTo(parent.getSpanContext().getTraceId());
        }
        for (String outcome : List.of("success", "failure", "cancelled")) {
            assertThat(metrics.get("ai.agent.model.call").tag("outcome", outcome).timer().count()).isEqualTo(1);
        }
        assertThat(Span.current().getSpanContext().isValid()).isFalse();
        parent.end();
    }

    @Test void actualChatClientKeepsUsageAndMemoryStepBelowTheExecution() {
        model.output = Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic-output"))),
                ChatResponseMetadata.builder().model("reported-model").usage(new DefaultUsage(7, 13, 20)).build()));
        Span parent = telemetry.get("test").spanBuilder("entry").startSpan();
        assertThat(assembleUnder(parent).collectList().block()).containsExactly("synthetic-output");
        var execution = executions().get(0);
        var modelCall = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("ai.model.call"))
                .findFirst().orElseThrow();
        assertThat(modelCall.getParentSpanId()).isEqualTo(execution.getSpanId());
        assertThat(modelCall.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(7L);
        assertThat(modelCall.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(13L);
        assertThat(execution.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
        assertThat(model.subscribedSpanId).isEqualTo(modelCall.getSpanId());
        var memory = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("memory.retrieve"))
                .findFirst().orElseThrow();
        assertThat(memory.getParentSpanId()).isEqualTo(execution.getSpanId());
        assertThat(memory.getTraceId()).isEqualTo(parent.getSpanContext().getTraceId());
        parent.end();
    }

    @Test void usageOnlyEmptyTextDoesNotEmitAFrameWhileWhitespaceAndUsageSurvive() {
        model.output = Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic-output")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(" ")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(""))),
                        ChatResponseMetadata.builder().usage(new DefaultUsage(7, 13, 20)).build()));
        Span parent = telemetry.get("test").spanBuilder("entry").startSpan();
        assertThat(assembleUnder(parent).collectList().block()).containsExactly("synthetic-output", " ");
        var modelCall = exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("ai.model.call"))
                .findFirst().orElseThrow();
        assertThat(modelCall.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(7L);
        assertThat(modelCall.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(13L);
        assertThat(modelCall.getAttributes().get(AttributeKey.booleanKey("fleet.ai.usage.final"))).isTrue();
        parent.end();
    }

    @Test void unavailablePromptPropagatesFailureAndKeepsFailureObservation() {
        var missingPromptService = service(key -> Optional.empty());
        Span parent = telemetry.get("test").spanBuilder("entry").startSpan();
        Flux<String> stream;
        try (Scope ignored = parent.makeCurrent()) {
            stream = missingPromptService.stream(new AgentChatRequest("business-session", "qwen", "", "synthetic-user", "", "", "",
                    List.of(), "test", "business-trace", "", "", "", ""));
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stream.collectList().block())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Nacos AI Prompt");
        assertThat(executions()).hasSize(1);
        assertThat(executions().get(0).getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("failure");
        assertThat(executions().get(0).getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
        parent.end();
    }

    private java.util.List<io.opentelemetry.sdk.trace.data.SpanData> executions() {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("ai.agent.execution")).toList();
    }

    private Flux<String> assembleUnder(Span parent) {
        try (Scope ignored = parent.makeCurrent()) {
            return service.stream(new AgentChatRequest("business-session", "qwen", "synthetic-system", "synthetic-user", "", "", "",
                    List.of(), "test", "business-trace", "", "", "", ""));
        }
    }

    private PatientAnalysisAgentService service() {
        return service(key -> Optional.of("synthetic-prompt"));
    }

    private PatientAnalysisAgentService service(AiPromptRegistryClient prompts) {
        var provider = mock(AiChatClientProvider.class);
        when(provider.selection("qwen")).thenReturn(new AiChatClientProvider.CurrentClient("qwen", "synthetic-model", ChatClient.create(model)));
        var memory = mock(MemoryAgentClient.class);
        var response = new MemoryRetrievalRpcDTO();
        response.setContext("");
        when(memory.retrieve(any())).thenReturn(response);
        return new PatientAnalysisAgentService(provider, new AiPromptProvider(prompts),
                new AiModelCallObserver(metrics, telemetry.get("test")), memory);
    }

    private static class FakeModel implements ChatModel {
        volatile String subscribedSpanId;
        volatile Flux<ChatResponse> output = Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic-output")))));
        @Override public ChatResponse call(Prompt prompt) { throw new UnsupportedOperationException(); }
        @Override public Flux<ChatResponse> stream(Prompt prompt) {
            subscribedSpanId = Span.current().getSpanContext().getSpanId();
            return output;
        }
    }
}
