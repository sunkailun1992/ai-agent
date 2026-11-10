package com.kellen.agent.service;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;

/** 衔接 Spring AI 2 的流订阅与 SDK 传输释放；阻塞 SDK 在受限工作池订阅并恢复本轮 trace。 */
public final class StreamingCancellationAdvisor implements StreamAdvisor {
    @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        Context parent = Context.current();
        return Flux.defer(() -> {
            StreamingCallCancellation.Handle handle = StreamingCallCancellation.open();
            try {
                ChatClientRequest scoped = withLocalMarker(request, handle.id);
                Flux<ChatClientResponse> execution = Flux.from(subscriber -> {
                    try (Scope ignored = parent.makeCurrent(); Scope active = handle.activate()) {
                        // defer 将装配异常转成合法 onSubscribe/onError，不能让工作线程吞掉异常后悬挂。
                        Flux.defer(() -> chain.nextStream(scoped)).subscribe(subscriber);
                    }
                });
                // Google 同步 iterator 占用订阅线程；后续 request 不能排回该阻塞线程而饿死 SSE 写出。
                return execution.subscribeOn(Schedulers.boundedElastic(), false)
                        .doFinally(signal -> handle.close());
            } catch (RuntimeException | Error error) {
                handle.close();
                throw error;
            }
        });
    }

    private ChatClientRequest withLocalMarker(ChatClientRequest request, String id) {
        if (!(request.prompt().getOptions() instanceof OpenAiChatOptions options)) return request;
        OpenAiChatOptions copy = options;
        var headers = new HashMap<String, String>();
        if (copy.getCustomHeaders() != null) headers.putAll(copy.getCustomHeaders());
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(StreamingCallCancellation.HEADER));
        headers.put(StreamingCallCancellation.HEADER, id);
        copy = options.mutate().customHeaders(headers).build();
        return request.mutate().prompt(new Prompt(request.prompt().getInstructions(), copy)).build();
    }

    @Override public String getName() { return "sdk-stream-cancellation"; }
    @Override public int getOrder() { return org.springframework.core.Ordered.HIGHEST_PRECEDENCE; }
}
