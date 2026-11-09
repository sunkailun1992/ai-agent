package com.kellen.agent.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.function.Function;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AiModelCallObserver.class);
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
        return observeCall(provider, model, operation, AiPromptSnapshot.unknown(), invocation);
    }

    public <T> T observeCall(String provider, String model, String operation, AiPromptSnapshot prompt, Supplier<T> invocation) {
        return observePromptCall(provider, model, operation, () -> prompt, ignored -> invocation);
    }

    /** 先在执行父 span 内解析 Prompt/组装请求，再由返回的 Supplier 启动实际模型子调用。 */
    public <T> T observePromptCall(String provider, String model, String operation, Supplier<AiPromptSnapshot> promptSource,
                                  Function<AiPromptSnapshot, Supplier<T>> preparation) {
        Span execution = executionSpan(provider, model, operation, AiPromptSnapshot.unknown());
        String outcome = "failure";
        try (Scope ignored = execution.makeCurrent()) {
            AiPromptSnapshot prompt = java.util.Objects.requireNonNull(promptSource.get());
            promptAttributes(execution, prompt);
            Supplier<T> invocation = java.util.Objects.requireNonNull(preparation.apply(prompt));
            T result = modelCall(provider, model, operation, prompt, invocation);
            outcome = "success";
            return result;
        } finally {
            finishExecution(execution, outcome);
        }
    }

    private <T> T modelCall(String provider, String model, String operation, AiPromptSnapshot prompt, Supplier<T> invocation) {
        long startedAt = start();
        Span span = modelSpan(provider, model, operation, prompt);
        AiResponseMetadata metadata = new AiResponseMetadata();
        T result;
        try (Scope ignored = span.makeCurrent()) {
            result = invocation.get();
        } catch (RuntimeException | Error error) {
            finish(span, provider, model, operation, prompt, metadata, "failure", startedAt);
            throw error;
        }
        metadata.accept(result);
        finish(span, provider, model, operation, prompt, metadata, "success", startedAt);
        return result;
    }

    /** 每次订阅独立计时和建 span；Scope 只覆盖装配/订阅，不能跨异步回调占用线程上下文。 */
    public <T> Flux<T> observeStream(String provider, String model, String operation, Supplier<Flux<T>> invocation) {
        return observeStream(provider, model, operation, AiPromptSnapshot.unknown(), invocation);
    }

    public <T> Flux<T> observeStream(String provider, String model, String operation, AiPromptSnapshot prompt,
                                     Supplier<Flux<T>> invocation) {
        return observePromptStream(provider, model, operation, () -> prompt, ignored -> invocation.get());
    }

    /** 每次订阅持有自己的 Prompt 快照、响应元数据与终态，不跨请求复用可变累积器。 */
    public <T> Flux<T> observePromptStream(String provider, String model, String operation, Supplier<AiPromptSnapshot> promptSource,
                                           Function<AiPromptSnapshot, Flux<T>> invocation) {
        Context parent = Context.current();
        return Flux.defer(() -> {
            Span execution;
            try (Scope ignored = parent.makeCurrent()) {
                execution = executionSpan(provider, model, operation, AiPromptSnapshot.unknown());
            }
            Flux<T> source;
            AiPromptSnapshot prompt;
            Context executionContext = parent.with(execution);
            try (Scope ignored = executionContext.makeCurrent()) {
                prompt = java.util.Objects.requireNonNull(promptSource.get());
                promptAttributes(execution, prompt);
                source = java.util.Objects.requireNonNull(invocation.apply(prompt));
            } catch (RuntimeException | Error error) {
                finishExecution(execution, "failure");
                return Flux.error(error);
            }
            // 请求装配（包括记忆召回）已经结束；模型计时从实际源订阅前开始。
            long startedAt = start();
            Span span;
            try (Scope ignored = executionContext.makeCurrent()) { span = modelSpan(provider, model, operation, prompt); }
            Context modelContext = executionContext.with(span);
            AiResponseMetadata metadata = new AiResponseMetadata();
            AtomicBoolean finished = new AtomicBoolean();
            Flux<T> scoped = Flux.from(subscriber -> {
                try (Scope ignored = modelContext.makeCurrent()) {
                    source.subscribe(subscriber);
                }
            });
            return scoped.doOnNext(metadata::accept)
                    .doOnComplete(() -> finishOnce(finished, execution, span, provider, model, operation, prompt, metadata, "success", startedAt))
                    .doOnError(error -> finishOnce(finished, execution, span, provider, model, operation, prompt, metadata, "failure", startedAt))
                    .doOnCancel(() -> finishOnce(finished, execution, span, provider, model, operation, prompt, metadata, "cancelled", startedAt));
        });
    }

    private Span executionSpan(String provider, String model, String operation, AiPromptSnapshot prompt) {
        return tracer.spanBuilder("ai.agent.execution")
                .setAttribute("gen_ai.operation.name", "invoke_agent")
                .setAttribute("operation", safe(operation)).startSpan();
    }

    private Span modelSpan(String provider, String model, String operation, AiPromptSnapshot prompt) {
        return tracer.spanBuilder("ai.model.call")
                .setAttribute("gen_ai.operation.name", "chat")
                .setAttribute("gen_ai.provider.name", safe(provider))
                .setAttribute("gen_ai.request.model", safe(model))
                .setAttribute("fleet.ai.prompt.key", prompt.key())
                .setAttribute("fleet.ai.prompt.version", prompt.version())
                .setAttribute("fleet.ai.prompt.source", prompt.source())
                .setAttribute("operation", safe(operation)).startSpan();
    }

    private void promptAttributes(Span span, AiPromptSnapshot prompt) {
        span.setAttribute("fleet.ai.prompt.key", prompt.key());
        span.setAttribute("fleet.ai.prompt.version", prompt.version());
        span.setAttribute("fleet.ai.prompt.source", prompt.source());
    }

    private void finishOnce(AtomicBoolean finished, Span execution, Span span, String provider, String model, String operation,
                            AiPromptSnapshot prompt, AiResponseMetadata metadata, String outcome, long startedAt) {
        if (finished.compareAndSet(false, true)) {
            try { finish(span, provider, model, operation, prompt, metadata, outcome, startedAt); }
            finally { finishExecution(execution, outcome); }
        }
    }

    private void finishExecution(Span execution, String outcome) {
        execution.setAttribute("outcome", outcome);
        if ("failure".equals(outcome)) execution.setStatus(StatusCode.ERROR);
        execution.end();
    }

    private void finish(Span span, String provider, String model, String operation, AiPromptSnapshot prompt,
                        AiResponseMetadata metadata, String outcome, long startedAt) {
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("outcome", outcome);
            metadata.annotate(span);
            span.setAttribute("fleet.ai.usage.final", metadata.available() && "success".equals(outcome));
            if ("failure".equals(outcome)) span.setStatus(StatusCode.ERROR);
            try {
                var event = log.atInfo().addKeyValue("event", "ai.model.call")
                        .addKeyValue("summary", "AI " + safe(operation) + " finished: " + outcome)
                        .addKeyValue("ai_operation", safe(operation)).addKeyValue("ai_provider", safe(provider))
                        .addKeyValue("ai_model", safe(model)).addKeyValue("ai_prompt_key", prompt.key())
                        .addKeyValue("ai_prompt_version", prompt.version())
                        .addKeyValue("ai_input_summary", "prompt_source=" + prompt.source()
                                + "; system_characters=" + prompt.template().length() + "; content_capture=disabled")
                        .addKeyValue("duration_ms", Math.max(0, System.nanoTime() - startedAt) / 1_000_000)
                        .addKeyValue("outcome", outcome);
                metadata.logFields(event);
                event.log("AI model call finished");
            } catch (RuntimeException ignoredLoggingFailure) {
                // 遥测输出失败不能改变模型响应或取消行为。
            }
        } finally {
            span.end();
            try {
                record(provider, model, operation, outcome, startedAt);
            } catch (RuntimeException ignoredMetricsFailure) {
                // 指标后端失效不改变业务结果。
            }
        }
    }

    /** 固定编排步骤，不冒充模型自主 tool calling。 */
    public <T> T observeStep(String name, Supplier<T> invocation) {
        Span span = tracer.spanBuilder(name).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            span.setAttribute("business.outcome", "success");
            return invocation.get();
        } catch (RuntimeException error) {
            span.setAttribute("business.outcome", "error");
            span.setStatus(StatusCode.ERROR);
            throw error;
        } finally {
            span.end();
        }
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
        return AiPromptSnapshot.metadata(value);
    }
}
