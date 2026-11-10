package com.kellen.agent.service;

import com.sun.net.httpserver.HttpServer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用实际共享 OkHttp 客户端验证逐请求隔离、晚到 Call 和本地标记的出网边界。 */
class StreamingCancellationTransportTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> leakedMarker = new AtomicReference<>();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(StreamingCallCancellation.openAiInterceptor()).build();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            leakedMarker.set(exchange.getRequestHeaders().getFirst(StreamingCallCancellation.HEADER));
            if (exchange.getRequestURI().getPath().equals("/complete")) {
                byte[] body = "completed".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
                exchange.close(); return;
            }
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                while (!Thread.currentThread().isInterrupted()) {
                    body.write("synthetic-token\n".getBytes(StandardCharsets.UTF_8)); body.flush(); Thread.sleep(20);
                }
            } catch (IOException disconnected) { }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach void stop() throws InterruptedException {
        server.stop(0); executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        client.dispatcher().executorService().shutdown(); client.connectionPool().evictAll();
    }

    @Test void cancellingOneSubscriptionDoesNotCancelItsConcurrentSibling() throws IOException {
        try (var first = StreamingCallCancellation.open(); var second = StreamingCallCancellation.open()) {
            Call firstCall = call(first.id), secondCall = call(second.id);
            try (var firstResponse = firstCall.execute(); var secondResponse = secondCall.execute()) {
                assertThat(firstResponse.body().source().readUtf8Line()).isEqualTo("synthetic-token");
                assertThat(secondResponse.body().source().readUtf8Line()).isEqualTo("synthetic-token");
                assertThat(requests).hasValue(2); assertThat(leakedMarker).hasValue(null);
                first.close(); first.close();
                assertThat(firstCall.isCanceled()).isTrue(); assertThat(secondCall.isCanceled()).isFalse();
                assertThat(secondResponse.body().source().readUtf8Line()).isEqualTo("synthetic-token");
            }
        }
    }

    @Test void cancellationBeforeTheSdkCreatesItsCallFailsBeforeNetwork() {
        var owner = StreamingCallCancellation.open(); String id = owner.id; owner.close();
        assertThatThrownBy(() -> call(id).execute()).isInstanceOf(IOException.class).hasMessage("AI_STREAM_CANCELLED");
        assertThat(requests).hasValue(0);
    }

    @Test void lateCallOnAlreadyClosedGoogleScopeIsImmediatelyCancelled() throws Exception {
        var owner = StreamingCallCancellation.open();
        OkHttpClient google = client.newBuilder().eventListenerFactory(StreamingCallCancellation.googleListener()).build();
        Call call;
        try (var active = owner.activate()) { call = google.newCall(new Request.Builder().url(url()).build()); }
        owner.close();
        assertThatThrownBy(call::execute).isInstanceOf(IOException.class);
        assertThat(call.isCanceled()).isTrue(); assertThat(requests).hasValue(0);
    }

    @Test void nestedGoogleScopesRestoreTheirPreviousRequestOwner() throws Exception {
        try (var first = StreamingCallCancellation.open(); var second = StreamingCallCancellation.open()) {
            OkHttpClient google = client.newBuilder().eventListenerFactory(StreamingCallCancellation.googleListener()).build();
            Call firstCall, secondCall;
            try (var activeFirst = first.activate()) {
                try (var activeSecond = second.activate()) { secondCall = google.newCall(new Request.Builder().url(url()).build()); }
                firstCall = google.newCall(new Request.Builder().url(url()).build());
            }
            try (var firstResponse = firstCall.execute(); var secondResponse = secondCall.execute()) {
                first.close(); assertThat(firstCall.isCanceled()).isTrue();
                assertThat(secondCall.isCanceled()).isFalse(); assertThat(requests).hasValue(2);
                assertThat(secondResponse.body().source().readUtf8Line()).isEqualTo("synthetic-token");
            }
        }
    }

    @Test void completelyReadCallIsReleasedBeforeItsSubscriptionEnds() throws IOException {
        var owner = StreamingCallCancellation.open();
        Call call = client.newCall(new Request.Builder().url(url().replace("/stream", "/complete"))
                .header(StreamingCallCancellation.HEADER, owner.id).build());
        try (var response = call.execute()) { assertThat(response.body().string()).isEqualTo("completed"); }
        owner.close(); assertThat(call.isCanceled()).isFalse(); assertThat(leakedMarker).hasValue(null);
    }

    @Test void googleTransportPreservesTimeoutAndTlsDefaultsWithoutAutomaticReconnect() {
        var google = StreamingCallCancellation.googleHttpClient();
        try {
            assertThat(google.connectTimeoutMillis()).isZero(); assertThat(google.readTimeoutMillis()).isZero();
            assertThat(google.writeTimeoutMillis()).isZero(); assertThat(google.callTimeoutMillis()).isZero();
            assertThat(google.hostnameVerifier()).isSameAs(new OkHttpClient().hostnameVerifier());
            assertThat(google.retryOnConnectionFailure()).isFalse();
        } finally { google.dispatcher().executorService().shutdown(); google.connectionPool().evictAll(); }
    }

    private Call call(String id) { return client.newCall(new Request.Builder().url(url()).header(StreamingCallCancellation.HEADER, id).build()); }
    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/stream"; }
}
