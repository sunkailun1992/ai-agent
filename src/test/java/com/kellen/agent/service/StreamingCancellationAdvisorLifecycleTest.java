package com.kellen.agent.service;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 生命周期补充测试；真实 SDK/HTTP 取消和并发隔离另由网络集成测试覆盖。 */
class StreamingCancellationAdvisorLifecycleTest {
    @Test void completionErrorCancellationAndSubscribeFailureReleaseEveryHandle() throws Exception {
        for (String terminal : List.of("complete", "error", "cancel", "subscribe-failure")) {
            var chain = mock(StreamAdvisorChain.class);
            var seen = new AtomicReference<ChatClientRequest>();
            var activated = new CountDownLatch(1);
            var originalFailure = new IllegalStateException("synthetic-failure");
            when(chain.nextStream(any())).thenAnswer(invocation -> {
                seen.set(invocation.getArgument(0)); activated.countDown();
                assertThat(currentOwner()).isNotNull();
                if (terminal.equals("subscribe-failure")) throw originalFailure;
                if (terminal.equals("error")) return Flux.error(originalFailure);
                return terminal.equals("cancel") ? Flux.never() : Flux.empty();
            });
            var options = OpenAiChatOptions.builder().model("synthetic").customHeaders(Map.of("X-Existing", "retained")).build();
            var request = new ChatClientRequest(new Prompt("synthetic-private", options), Map.of());
            var output = new StreamingCancellationAdvisor().adviseStream(request, chain);
            if (terminal.equals("cancel")) {
                var subscription = output.subscribe();
                assertThat(activated.await(2, TimeUnit.SECONDS)).isTrue(); subscription.dispose(); subscription.dispose();
            } else if (terminal.equals("complete")) assertThat(output.collectList().block(Duration.ofSeconds(2))).isEmpty();
            else assertThatThrownBy(() -> output.collectList().block(Duration.ofSeconds(2))).isSameAs(originalFailure);
            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(pendingCount()).isZero());
            assertThat(currentOwner()).isNull();
            var headers = ((OpenAiChatOptions) seen.get().prompt().getOptions()).getCustomHeaders();
            assertThat(headers).containsEntry("X-Existing", "retained").containsKey(StreamingCallCancellation.HEADER);
            assertThat(options.getCustomHeaders()).containsExactlyEntriesOf(Map.of("X-Existing", "retained"));
        }
    }

    @Test void blockingSubscriptionMovesToWorkerAndKeepsTheCapturedModelSpan() {
        var chain = mock(StreamAdvisorChain.class);
        var original = Span.wrap(SpanContext.createFromRemoteParent("abcdefabcdefabcdefabcdefabcdefab", "abcdefabcdefabcd",
                TraceFlags.getSampled(), TraceState.getDefault()));
        var actual = new AtomicReference<SpanContext>();
        var callingThread = Thread.currentThread();
        when(chain.nextStream(any())).thenAnswer(invocation -> {
            assertThat(Thread.currentThread()).isNotSameAs(callingThread);
            actual.set(Span.current().getSpanContext()); return Flux.empty();
        });
        Flux<ChatClientResponse> output;
        try (var ignored = original.makeCurrent()) {
            output = new StreamingCancellationAdvisor().adviseStream(new ChatClientRequest(new Prompt("synthetic"), Map.of()), chain);
        }
        output.blockLast(Duration.ofSeconds(2));
        assertThat(actual.get()).isEqualTo(original.getSpanContext()); assertThat(Span.current().getSpanContext().isValid()).isFalse();
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(pendingCount()).isZero());
    }

    private int pendingCount() throws Exception {
        var field = StreamingCallCancellation.class.getDeclaredField("PENDING"); field.setAccessible(true);
        return ((Map<?, ?>) field.get(null)).size();
    }
    private Object currentOwner() throws Exception {
        var field = StreamingCallCancellation.class.getDeclaredField("CURRENT"); field.setAccessible(true);
        return ((ThreadLocal<?>) field.get(null)).get();
    }
}
