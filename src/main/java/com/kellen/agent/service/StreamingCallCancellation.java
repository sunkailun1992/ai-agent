package com.kellen.agent.service;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import java.time.Duration;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 每轮流只持有自己的 HTTP Call；不取消共享客户端、连接池或其他请求。 */
public final class StreamingCallCancellation {
    static final String HEADER = "X-Kellen-Local-Stream";
    private static final ThreadLocal<Handle> CURRENT = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Handle> PENDING = new ConcurrentHashMap<>();

    private StreamingCallCancellation() { }

    static Handle open() {
        Handle handle = new Handle();
        PENDING.put(handle.id, handle);
        return handle;
    }

    /** SDK 1.58.0 的 custom-client 分支跳过默认 timeout 初始化，须显式保持官方三个零超时。 */
    public static OkHttpClient googleHttpClient() {
        return new OkHttpClient.Builder().connectTimeout(Duration.ZERO).readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ZERO).retryOnConnectionFailure(false)
                .eventListenerFactory(googleListener()).build();
    }

    /** Google 同步创建 Call 时绑定当前订阅，监听器不会读取正文或认证信息。 */
    public static EventListener.Factory googleListener() {
        return call -> {
            Handle owner = CURRENT.get();
            return new EventListener() {
                @Override public void callStart(Call current) { if (owner != null) owner.register(current); }
                @Override public void callEnd(Call current) { if (owner != null) owner.release(current); }
                @Override public void callFailed(Call current, IOException failure) { if (owner != null) owner.release(current); }
            };
        };
    }

    /** OpenAI 在 SDK 线程异步建 Call；临时标记仅在本机匹配，出网前无条件移除。 */
    public static Interceptor openAiInterceptor() {
        return chain -> {
            String id = chain.request().header(HEADER);
            if (id == null) return chain.proceed(chain.request());
            Handle owner = PENDING.get(id);
            if (owner == null) throw new IOException("AI_STREAM_CANCELLED");
            owner.register(chain.call());
            if (chain.call().isCanceled()) throw new IOException("AI_STREAM_CANCELLED");
            var response = chain.proceed(chain.request().newBuilder().removeHeader(HEADER).build());
            ResponseBody body = response.body();
            if (body == null) { owner.release(chain.call()); return response; }
            // 成功读完/关闭的 Call 已完成，不应在终态清理时再标成 cancelled。
            BufferedSource source = Okio.buffer(new ForwardingSource(body.source()) {
                private volatile boolean exhausted;
                @Override public long read(okio.Buffer sink, long byteCount) throws IOException {
                    try {
                        long count = super.read(sink, byteCount);
                        if (count == -1) { exhausted = true; owner.release(chain.call()); }
                        return count;
                    } catch (IOException failure) { owner.release(chain.call()); throw failure; }
                }
                @Override public void close() throws IOException {
                    // 未读到结尾就关闭即放弃本流（Spring AI 2.0.2 起下游取消会先关闭响应）；只关正文不会及时断开连接，须取消本轮 Call。
                    try { if (!exhausted) chain.call().cancel(); super.close(); } finally { owner.release(chain.call()); }
                }
            });
            return response.newBuilder().body(new ResponseBody() {
                @Override public okhttp3.MediaType contentType() { return body.contentType(); }
                @Override public long contentLength() { return body.contentLength(); }
                @Override public BufferedSource source() { return source; }
            }).build();
        };
    }

    static final class Handle implements AutoCloseable {
        final String id = UUID.randomUUID().toString();
        private final Set<Call> calls = new HashSet<>();
        private boolean closed;

        io.opentelemetry.context.Scope activate() {
            Handle previous = CURRENT.get();
            CURRENT.set(this);
            return () -> { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); };
        }

        synchronized void register(Call call) {
            if (closed) call.cancel(); else calls.add(call);
        }

        synchronized void release(Call call) { calls.remove(call); }

        @Override public void close() {
            Set<Call> snapshot;
            synchronized (this) {
                if (closed) return;
                closed = true;
                snapshot = Set.copyOf(calls);
                calls.clear();
            }
            PENDING.remove(id, this);
            snapshot.forEach(Call::cancel);
        }
    }
}
