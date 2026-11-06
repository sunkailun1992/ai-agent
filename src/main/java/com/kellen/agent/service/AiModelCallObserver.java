package com.kellen.agent.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.Context;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

/**
 * Agent 模型调用观测服务。
 */
@Service
public class AiModelCallObserver {

    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    @Autowired
    public AiModelCallObserver(MeterRegistry meterRegistry) {
        this(meterRegistry, GlobalOpenTelemetry.getTracer("com.kellen.ai-agent"));
    }

    AiModelCallObserver(MeterRegistry meterRegistry, Tracer tracer) {
        this.meterRegistry = meterRegistry;
        this.tracer = tracer;
    }

    /** 只记录固定执行元数据；异常只标记失败，不能交给 span.recordException 采集原文。 */
    public <T> T observeCall(String provider, String model, String operation, Supplier<T> invocation) {
        long startedAt = start();
        Span span = executionSpan(provider, operation);
        try (Scope ignored = span.makeCurrent()) {
            T result = invocation.get();
            finish(span, provider, model, operation, "success", startedAt);
            return result;
        } catch (RuntimeException error) {
            finish(span, provider, model, operation, "failure", startedAt);
            throw error;
        }
    }

    /** 每次订阅独立计时和建 span；Scope 只覆盖装配/订阅，不能跨异步回调占用线程上下文。 */
    public <T> Flux<T> observeStream(String provider, String model, String operation, Supplier<Flux<T>> invocation) {
        Context parent = Context.current();
        return Flux.defer(() -> {
            long startedAt = start();
            Span span = tracer.spanBuilder("ai.agent.execution").setParent(parent)
                    .setAttribute("provider", safe(provider)).setAttribute("operation", safe(operation)).startSpan();
            AtomicBoolean finished = new AtomicBoolean();
            Flux<T> source;
            try (Scope ignored = span.makeCurrent()) {
                source = invocation.get();
            } catch (RuntimeException error) {
                source = Flux.error(error);
            }
            Flux<T> execution = source;
            Flux<T> scoped = Flux.from(subscriber -> {
                try (Scope ignored = span.makeCurrent()) {
                    execution.subscribe(subscriber);
                }
            });
            return scoped.doOnComplete(() -> finishOnce(finished, span, provider, model, operation, "success", startedAt))
                    .doOnError(error -> finishOnce(finished, span, provider, model, operation, "failure", startedAt))
                    .doOnCancel(() -> finishOnce(finished, span, provider, model, operation, "cancelled", startedAt));
        });
    }

    private Span executionSpan(String provider, String operation) {
        return tracer.spanBuilder("ai.agent.execution").setAttribute("provider", safe(provider))
                .setAttribute("operation", safe(operation)).startSpan();
    }

    private void finishOnce(AtomicBoolean finished, Span span, String provider, String model, String operation,
                            String outcome, long startedAt) {
        if (finished.compareAndSet(false, true)) {
            finish(span, provider, model, operation, outcome, startedAt);
        }
    }

    private void finish(Span span, String provider, String model, String operation, String outcome, long startedAt) {
        span.setAttribute("outcome", outcome);
        if ("failure".equals(outcome)) span.setStatus(StatusCode.ERROR);
        span.end();
        record(provider, model, operation, outcome, startedAt);
    }

    public long start() {
        return System.nanoTime();
    }

    public void success(String provider, String model, String operation, long startNanos) {
        record(provider, model, operation, "success", startNanos);
    }

    public void failure(String provider, String model, String operation, long startNanos) {
        record(provider, model, operation, "failure", startNanos);
    }

    public void cancelled(String provider, String model, String operation, long startNanos) {
        record(provider, model, operation, "cancelled", startNanos);
    }

    private void record(String provider, String model, String operation, String outcome, long startNanos) {
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        Timer.builder("ai.agent.model.call")
                .tag("provider", safe(provider))
                .tag("model", safe(model))
                .tag("operation", safe(operation))
                .tag("outcome", safe(outcome))
                .register(meterRegistry)
                .record(duration);
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
