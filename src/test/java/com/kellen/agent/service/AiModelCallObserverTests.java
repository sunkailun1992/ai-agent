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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;
import java.time.Duration;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 观测真实流终态、父上下文及取消行为；仅在测试中建立 SDK。 */
class AiModelCallObserverTests {
    @Test void concurrentStreamsKeepReportedUsageAndParentsSeparate() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), provider.get("test"));
            Span firstParent = provider.get("test").spanBuilder("first").startSpan();
            Span secondParent = provider.get("test").spanBuilder("second").startSpan();
            Flux<ChatResponse> first;
            Flux<ChatResponse> second;
            try (Scope ignored = firstParent.makeCurrent()) {
                first = observer.observeStream("qwen", "model", "patient_analysis", () -> Flux.just(response(1, 2))
                        .delayElements(Duration.ofMillis(5)));
            }
            try (Scope ignored = secondParent.makeCurrent()) {
                second = observer.observeStream("qwen", "model", "patient_analysis", () -> Flux.just(response(3, 4))
                        .delayElements(Duration.ofMillis(5)));
            }
            Flux.merge(first, second).collectList().block();
            assertThat(models(exporter)).hasSize(2);
            for (var span : models(exporter)) {
                boolean firstTrace = span.getTraceId().equals(firstParent.getSpanContext().getTraceId());
                assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")))
                        .isEqualTo(firstTrace ? 2L : 4L);
                var execution = exporter.getFinishedSpanItems().stream()
                        .filter(item -> item.getSpanId().equals(span.getParentSpanId())).findFirst().orElseThrow();
                assertThat(execution.getName()).isEqualTo("ai.agent.execution");
                assertThat(execution.getParentSpanId()).isEqualTo((firstTrace ? firstParent : secondParent).getSpanContext().getSpanId());
            }
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
            firstParent.end();
            secondParent.end();
        }
    }

    @Test void terminalLogUsesControlledFieldsAndNeverTheResponseBody() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AiModelCallObserver.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), provider.get("test"));
            observer.observeCall("qwen", "model", "patient_analysis", () -> response(7, 13));
            var event = appender.list.get(0);
            var fields = event.getKeyValuePairs().stream().collect(java.util.stream.Collectors.toMap(pair -> pair.key, pair -> pair.value));
            assertThat(fields).containsEntry("event", "ai.model.call").containsEntry("ai_input_tokens", 7L)
                    .containsEntry("ai_output_tokens", 13L).containsEntry("ai_usage_available", true);
            assertThat(fields.keySet()).doesNotContain("ai_input", "ai_output", "nativeUsage", "message");
            assertThat(fields.toString()).doesNotContain("SYNTHETIC_PRIVATE_ANSWER");
            assertThat(event.getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private List<io.opentelemetry.sdk.trace.data.SpanData> models(InMemorySpanExporter exporter) {
        return exporter.getFinishedSpanItems().stream().filter(span -> span.getName().equals("ai.model.call")).toList();
    }

    private ChatResponse response(int input, int output) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("SYNTHETIC_PRIVATE_ANSWER"))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(input, output, input + output)).build());
    }

    @Test void actualResponseMetadataSurvivesWithoutRetainingContent() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), provider.get("test"));
            var response = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("SYNTHETIC_PRIVATE_ANSWER")
                    .properties(Map.of("reasoningContent", "SYNTHETIC_PRIVATE_REASONING")).build())),
                    ChatResponseMetadata.builder().model("reported-model").usage(new DefaultUsage(7, 13, 20)).build());
            assertThat(observer.observeCall("qwen", "requested-model", "patient_analysis", () -> response))
                    .isSameAs(response);
            var span = models(exporter).get(0);
            assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.request.model"))).isEqualTo("requested-model");
            assertThat(span.getAttributes().get(AttributeKey.stringKey("gen_ai.response.model"))).isEqualTo("reported-model");
            assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(7L);
            assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(13L);
            assertThat(span.getAttributes().get(AttributeKey.booleanKey("fleet.ai.reasoning.present"))).isTrue();
            assertThat(span.toString()).doesNotContain("SYNTHETIC_PRIVATE");
        }
    }

    @Test void streamUsesFinalCumulativeUsageOnceAndKeepsMissingUsageUnknown() {
        var exporter = InMemorySpanExporter.create();
        try (var provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), provider.get("test"));
            var response = new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic"))),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(7, 13, 20)).build());
            observer.observeStream("qwen", "model", "patient_analysis", () -> Flux.just(response, response)).blockLast();
            observer.observeCall("qwen", "model", "patient_analysis", () -> "no metadata");
            assertThat(models(exporter).get(0).getAttributes()
                    .get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(13L);
            assertThat(models(exporter).get(1).getAttributes()
                    .get(AttributeKey.booleanKey("fleet.ai.usage.available"))).isFalse();
            assertThat(models(exporter).get(1).getAttributes()
                    .get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
        }
    }

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
            assertThat(models(exporter)).hasSize(3);
            assertThat(models(exporter)).extracting(s -> s.getAttributes().get(AttributeKey.stringKey("outcome")))
                    .containsExactly("success", "failure", "cancelled");
            assertThat(models(exporter).get(1).getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(models(exporter).toString()).doesNotContain("private-body", "private-exception");
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
            var execution = exporter.getFinishedSpanItems().stream().filter(item -> item.getName().equals("ai.agent.execution"))
                    .findFirst().orElseThrow();
            assertThat(models(exporter).get(0).getParentSpanId()).isEqualTo(execution.getSpanId());
            assertThat(execution.getParentSpanId()).isEqualTo(parent.getSpanContext().getSpanId());
            assertThat(Span.current().getSpanContext().isValid()).isFalse();
        }
    }
}
