package com.kellen.agent.service;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.openai.models.completions.CompletionUsage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

import java.util.List;
import java.util.Set;

/** 只保留响应中的类型化用量和公开推理存在性，不保留正文或 nativeUsage 对象。 */
final class AiResponseMetadata {
    private static final long MAX_TOKENS = 1_000_000_000L;
    private static final Set<String> FINISH_REASONS = Set.of("stop", "length", "tool_calls", "content_filter",
            "max_tokens", "safety", "error", "cancelled", "recitation", "other");
    private Long inputTokens;
    private Long outputTokens;
    private Long reasoningTokens;
    private String responseModel = "unknown";
    private String finishReason;
    private boolean reasoningPresent;
    private boolean summaryPresent;
    private long outputCharacters;
    private long chunks;

    synchronized void accept(Object value) {
        try {
            read(value);
        } catch (RuntimeException ignoredMetadataFailure) {
            // 可选 SDK 元数据损坏不能改变模型响应；不打印其内容或异常。
        }
    }

    private void read(Object value) {
        if (value instanceof ResponseEntity<?, ?> entity) {
            value = entity.getResponse();
        }
        if (!(value instanceof ChatResponse response)) {
            return;
        }
        String reportedModel = AiPromptSnapshot.metadata(response.getMetadata().getModel());
        if (!"unknown".equals(reportedModel)) responseModel = reportedModel;
        Usage usage = response.getMetadata().getUsage();
        // 流式 usage 是累计值；只替换最后一次实际报告，不逐片相加。
        if (usage != null && !(usage instanceof EmptyUsage)) {
            if (usage instanceof GoogleGenAiUsage && usage.getNativeUsage() instanceof GenerateContentResponseUsageMetadata nativeUsage) {
                Long input = token(nativeUsage.promptTokenCount().orElse(null));
                Long candidates = token(nativeUsage.candidatesTokenCount().orElse(null));
                Long reasoning = token(nativeUsage.thoughtsTokenCount().orElse(null));
                retainUsage(input, candidates == null ? null : token(candidates + (reasoning == null ? 0 : reasoning)), reasoning);
            } else if (!(usage instanceof GoogleGenAiUsage)) {
                Long reasoning = null;
                if (usage.getNativeUsage() instanceof CompletionUsage nativeUsage) {
                    reasoning = token(nativeUsage.completionTokensDetails()
                            .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens).orElse(null));
                }
                // OpenAI-compatible completionTokens 已包含 reasoning，不能再次累加。
                retainUsage(token(usage.getPromptTokens()), token(usage.getCompletionTokens()), reasoning);
            }
        }
        for (var generation : response.getResults()) {
            var output = generation.getOutput();
            Object publicReasoning = output.getMetadata().get("reasoningContent");
            reasoningPresent |= publicReasoning instanceof String text && !text.isBlank();
            boolean thought = Boolean.TRUE.equals(output.getMetadata().get("isThought"))
                    || Boolean.TRUE.equals(generation.getMetadata().get("isThought"));
            summaryPresent |= thought && output.getText() != null && !output.getText().isBlank();
            reasoningPresent |= summaryPresent;
            if (output.getText() != null) {
                outputCharacters = Math.min(MAX_TOKENS, outputCharacters + output.getText().length());
                chunks = Math.min(MAX_TOKENS, chunks + 1);
            }
            String finish = generation.getMetadata().getFinishReason();
            if (finish != null && !finish.isBlank()) {
                String normalized = finish.toLowerCase(java.util.Locale.ROOT);
                finishReason = FINISH_REASONS.contains(normalized) ? normalized : "other";
            }
        }
    }

    private void retainUsage(Long input, Long output, Long reasoning) {
        if (input != null || output != null || reasoning != null) {
            inputTokens = input;
            outputTokens = output;
            reasoningTokens = reasoning != null && (output == null || reasoning <= output) ? reasoning : null;
        }
    }

    private static Long token(Number value) {
        return value != null && value.longValue() >= 0 && value.longValue() <= MAX_TOKENS ? value.longValue() : null;
    }

    synchronized boolean available() {
        return inputTokens != null || outputTokens != null || reasoningTokens != null;
    }

    private String reasoningKind() {
        return summaryPresent ? "summary" : reasoningPresent ? "provider_output" : "none";
    }

    synchronized void annotate(Span span) {
        span.setAttribute("gen_ai.response.model", responseModel);
        span.setAttribute("fleet.ai.usage.available", available());
        span.setAttribute("fleet.ai.reasoning.present", reasoningPresent);
        span.setAttribute("fleet.ai.reasoning.summary_present", summaryPresent);
        span.setAttribute("fleet.ai.reasoning.kind", reasoningKind());
        if (finishReason != null) {
            span.setAttribute(AttributeKey.stringArrayKey("gen_ai.response.finish_reasons"), List.of(finishReason));
        }
        if (inputTokens != null) span.setAttribute("gen_ai.usage.input_tokens", inputTokens);
        if (outputTokens != null) span.setAttribute("gen_ai.usage.output_tokens", outputTokens);
        if (reasoningTokens != null) span.setAttribute("gen_ai.usage.reasoning.output_tokens", reasoningTokens);
    }

    synchronized void logFields(LoggingEventBuilder event) {
        event.addKeyValue("ai_usage_available", available())
                .addKeyValue("ai_reasoning_present", reasoningPresent)
                .addKeyValue("ai_reasoning_kind", reasoningKind())
                .addKeyValue("ai_output_summary", "text_chunks=" + chunks + "; characters=" + outputCharacters
                        + "; finish=" + (finishReason == null ? "unreported" : finishReason));
        if (inputTokens != null) event.addKeyValue("ai_input_tokens", inputTokens);
        if (outputTokens != null) event.addKeyValue("ai_output_tokens", outputTokens);
        if (reasoningTokens != null) event.addKeyValue("ai_reasoning_tokens", reasoningTokens);
    }
}
