package com.kellen.agent.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 模型推理与 Agent 准备分层：真实 SDK 父子、终态和日志上下文均可独立验证。 */
class AiModelCallHierarchyTests {
    @Test void synchronousPreparationFinishesBeforeModelChildAndUsageBelongsOnlyToChild() {
        var exporter = InMemorySpanExporter.create();
        try (var telemetry = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), telemetry.get("test"));
            var modelContext = new AtomicReference<String>();
            Object result = observer.observePromptCall("qwen", "model", "document_analysis", AiPromptSnapshot::unknown, prompt -> {
                observer.observeStep("memory.retrieve", () -> "synthetic");
                return (Supplier<ChatResponse>) () -> {
                    modelContext.set(Span.current().getSpanContext().getSpanId());
                    return response();
                };
            });
            assertThat(result).isInstanceOf(ChatResponse.class);
            var execution = span(exporter, "ai.agent.execution");
            var model = span(exporter, "ai.model.call");
            var memory = span(exporter, "memory.retrieve");
            assertThat(model.getParentSpanId()).isEqualTo(execution.getSpanId());
            assertThat(memory.getParentSpanId()).isEqualTo(execution.getSpanId());
            assertThat(model.getStartEpochNanos()).isGreaterThanOrEqualTo(memory.getEndEpochNanos());
            assertThat(modelContext.get()).isEqualTo(model.getSpanId());
            assertThat(execution.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
            assertThat(model.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(7L);
        }
    }

    @Test void streamPreparationAndModelSubscriptionUseSiblingMemoryAndSeparateTerminalSpan() {
        var exporter = InMemorySpanExporter.create();
        try (var telemetry = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), telemetry.get("test"));
            var subscribedSpan = new AtomicReference<String>();
            observer.observePromptStream("qwen", "model", "patient_analysis", AiPromptSnapshot::unknown, prompt -> {
                observer.observeStep("memory.retrieve", () -> "synthetic");
                return Flux.defer(() -> {
                    subscribedSpan.set(Span.current().getSpanContext().getSpanId());
                    return Flux.just(response());
                });
            }).blockLast();
            var execution = span(exporter, "ai.agent.execution");
            var model = span(exporter, "ai.model.call");
            var memory = span(exporter, "memory.retrieve");
            assertThat(model.getParentSpanId()).isEqualTo(execution.getSpanId());
            assertThat(memory.getParentSpanId()).isEqualTo(execution.getSpanId());
            assertThat(model.getStartEpochNanos()).isGreaterThanOrEqualTo(memory.getEndEpochNanos());
            assertThat(subscribedSpan.get()).isEqualTo(model.getSpanId());
            assertThat(model.getAttributes().get(AttributeKey.booleanKey("fleet.ai.usage.final"))).isTrue();
            assertThat(execution.getAttributes().get(AttributeKey.booleanKey("fleet.ai.usage.available"))).isNull();
        }
    }

    @Test void terminalLoggerRunsUnderModelChildAndCancellationEndsBothOnce() {
        var exporter = InMemorySpanExporter.create();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiModelCallObserver.class);
        var loggedSpan = new AtomicReference<String>();
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
                loggedSpan.set(Span.current().getSpanContext().getSpanId());
            }
        };
        appender.start(); logger.addAppender(appender);
        try (var telemetry = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var registry = new SimpleMeterRegistry();
            var observer = new AiModelCallObserver(registry, telemetry.get("test"));
            var subscription = observer.observeStream("qwen", "model", "patient_analysis", Flux::never).subscribe();
            subscription.dispose(); subscription.dispose();
            var model = span(exporter, "ai.model.call");
            var execution = span(exporter, "ai.agent.execution");
            assertThat(exporter.getFinishedSpanItems()).hasSize(2);
            assertThat(loggedSpan.get()).isEqualTo(model.getSpanId());
            assertThat(model.getParentSpanId()).isEqualTo(execution.getSpanId());
            assertThat(model.getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("cancelled");
            assertThat(execution.getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("cancelled");
            assertThat(registry.get("ai.agent.model.call").timer().count()).isEqualTo(1);
        } finally { logger.detachAppender(appender); appender.stop(); }
    }

    @Test void unavailablePromptEndsExecutionWithoutInventingAModelCall() {
        var exporter = InMemorySpanExporter.create();
        try (var telemetry = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var registry = new SimpleMeterRegistry();
            var observer = new AiModelCallObserver(registry, telemetry.get("test"));
            observer.observePromptStream("qwen", "model", "patient_analysis", () -> {
                throw new IllegalStateException("synthetic");
            }, prompt -> Flux.just(response())).onErrorResume(error -> Flux.empty()).blockLast();
            assertThat(exporter.getFinishedSpanItems()).hasSize(1);
            var execution = span(exporter, "ai.agent.execution");
            assertThat(execution.getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("failure");
            assertThat(registry.find("ai.agent.model.call").timer()).isNull();
        }
    }

    @Test void synchronousModelErrorEndsBothSpansAndKeepsTheOriginalFailure() {
        var exporter = InMemorySpanExporter.create();
        try (var telemetry = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), telemetry.get("test"));
            var failure = new AssertionError("synthetic");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> observer.observeCall("qwen", "model", "document_analysis", () -> {
                throw failure;
            })).isSameAs(failure);
            assertThat(exporter.getFinishedSpanItems()).hasSize(2);
            assertThat(span(exporter, "ai.model.call").getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("failure");
            assertThat(span(exporter, "ai.agent.execution").getAttributes().get(AttributeKey.stringKey("outcome"))).isEqualTo("failure");
        }
    }

    private SpanData span(InMemorySpanExporter exporter, String name) {
        return exporter.getFinishedSpanItems().stream().filter(item -> item.getName().equals(name)).findFirst().orElseThrow();
    }
    private ChatResponse response() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(7, 13, 20)).build());
    }
}
