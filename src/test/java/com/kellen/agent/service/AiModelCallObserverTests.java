package com.kellen.agent.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 观测真实流终态、父上下文及取消行为；仅在测试中建立 SDK。 */
class AiModelCallObserverTests {
    @Test void successErrorAndCancellationAreDistinctAndContainNoContent() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var registry = new SimpleMeterRegistry();
            var observer = new AiModelCallObserver(registry, provider.get("test"));
            assertThat(observer.observeStream("qwen", "test-model", "patient_analysis", () -> Flux.just("private-body"))
                    .collectList().block()).containsExactly("private-body");
            observer.observeStream("qwen", "test-model", "patient_analysis",
                    () -> Flux.error(new IllegalArgumentException("private-exception")))
                    .onErrorResume(error -> Flux.empty()).blockLast();
            var subscription = observer.observeStream("qwen", "test-model", "patient_analysis", Flux::never).subscribe();
            subscription.dispose();
            assertThat(exporter.getFinishedSpanItems()).hasSize(3);
            assertThat(exporter.getFinishedSpanItems()).extracting(s -> s.getAttributes().get(AttributeKey.stringKey("outcome")))
                    .containsExactly("success", "failure", "cancelled");
            assertThat(exporter.getFinishedSpanItems().get(1).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(exporter.getFinishedSpanItems().toString()).doesNotContain("private-body", "private-exception");
            for (String outcome : new String[]{"success", "failure", "cancelled"}) {
                assertThat(registry.get("ai.agent.model.call").tag("outcome", outcome).timer().count()).isEqualTo(1);
            }
        }
    }

    @Test void reusedWorkerKeepsEachCapturedParentAndRestoresContext() {
        var exporter = InMemorySpanExporter.create();
        var worker = reactor.core.scheduler.Schedulers.newSingle("observer-test");
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracer = provider.get("test");
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), tracer);
            for (int i = 0; i < 2; i++) {
                Span parent = tracer.spanBuilder("parent-" + i).startSpan();
                Flux<String> execution;
                try (Scope ignored = parent.makeCurrent()) {
                    execution = observer.observeStream("qwen", "test-model", "patient_analysis",
                            () -> Flux.just(Span.current().getSpanContext().getTraceId()));
                }
                assertThat(execution.subscribeOn(worker).blockLast()).isEqualTo(parent.getSpanContext().getTraceId());
                parent.end();
            }
            assertThat(reactor.core.publisher.Mono.fromCallable(() -> Span.current().getSpanContext().isValid())
                    .subscribeOn(worker).block()).isFalse();
        } finally {
            worker.dispose();
        }
    }

    @Test void subscriptionUsesParentAndRestoresThreadContext() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var tracer = provider.get("test");
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), tracer);
            Span parent = tracer.spanBuilder("parent").startSpan();
            AtomicReference<String> modelTrace = new AtomicReference<>();
            try (Scope ignored = parent.makeCurrent()) {
                observer.observeStream("qwen", "test-model", "patient_analysis", () -> Flux.defer(() -> {
                    modelTrace.set(Span.current().getSpanContext().getTraceId());
                    return Flux.just("result");
                })).blockLast();
                assertThat(Span.current()).isSameAs(parent);
            }
            parent.end();
            assertThat(modelTrace.get()).isEqualTo(parent.getSpanContext().getTraceId());
            assertThat(exporter.getFinishedSpanItems().get(0).getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        }
    }
}
