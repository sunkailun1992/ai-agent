package com.ai.agent.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Agent 模型调用观测服务。
 */
@Service
public class AiModelCallObserver {

    private final MeterRegistry meterRegistry;

    public AiModelCallObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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
