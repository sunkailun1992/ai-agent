package com.kellen.agent.service;

import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.openai.models.completions.CompletionUsage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 用 SDK 的真实 Usage 类型验证供应商口径；所有内容均为合成测试值。 */
class AiReportedUsageTests {
    @Test void googleCandidatesAndThoughtsAreCountedOnceInOutput() {
        var usage = GoogleGenAiUsage.from(GenerateContentResponseUsageMetadata.builder()
                .promptTokenCount(7).candidatesTokenCount(13).thoughtsTokenCount(5).totalTokenCount(25).build());
        SpanData span = observe(usage);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isEqualTo(7L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(18L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.reasoning.output_tokens"))).isEqualTo(5L);
    }

    @Test void openAiCompletionAlreadyIncludesReasoning() {
        var nativeUsage = CompletionUsage.builder().promptTokens(7).completionTokens(13).totalTokens(20)
                .completionTokensDetails(CompletionUsage.CompletionTokensDetails.builder().reasoningTokens(5).build()).build();
        SpanData span = observe(new DefaultUsage(7, 13, 20, nativeUsage));
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isEqualTo(13L);
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.reasoning.output_tokens"))).isEqualTo(5L);
    }

    @Test void googleSyntheticZerosWithoutNativeReportRemainUnknown() {
        SpanData span = observe(GoogleGenAiUsage.from(null));
        assertThat(span.getAttributes().get(AttributeKey.booleanKey("fleet.ai.usage.available"))).isFalse();
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens"))).isNull();
    }

    @Test void explicitZeroIsReportedAndUnknownNativeObjectIsNeverSerialized() {
        Object secret = new Object() {
            @Override public String toString() { throw new AssertionError("native metadata must never be serialized"); }
        };
        SpanData span = observe(new DefaultUsage(0, 0, 0, secret));
        assertThat(span.getAttributes().get(AttributeKey.booleanKey("fleet.ai.usage.available"))).isTrue();
        assertThat(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens"))).isZero();
    }

    private SpanData observe(Usage usage) {
        var exporter = InMemorySpanExporter.create();
        try (var telemetry = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            var response = new ChatResponse(List.of(new Generation(new AssistantMessage("synthetic-output"))),
                    ChatResponseMetadata.builder().usage(usage).build());
            var observer = new AiModelCallObserver(new SimpleMeterRegistry(), telemetry.get("test"));
            assertThat(observer.observeCall("google", "synthetic-model", "patient_analysis",
                    new AiPromptSnapshot("synthetic-key", "1.2.3", "nacos", "SYNTHETIC_PRIVATE_PROMPT"), () -> response))
                    .isSameAs(response);
            var span = exporter.getFinishedSpanItems().get(0);
            assertThat(span.getAttributes().get(AttributeKey.stringKey("fleet.ai.prompt.version"))).isEqualTo("1.2.3");
            assertThat(span.toString()).doesNotContain("SYNTHETIC_PRIVATE_PROMPT", "synthetic-output");
            return span;
        }
    }
}
