package com.ai.agent.service;

import com.kellen.agent.AiAgentApplication;
import com.kellen.agent.service.AiChatClientProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI Provider 选择链路测试。
 */
@SpringBootTest(classes = AiAgentApplication.class, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.ai.google.genai.api-key=test-key",
        "ai.agent.provider=qwen",
        "ai.agent.qwen.api-key=test-qwen-key",
        "ai.agent.qwen.model=qwen-test",
        "logging.file.path=build/logs",
        "logging.file.name=build/logs/ai-agent-provider-test.log"
})
class AiChatClientProviderTests {

    private static final HttpServer MODEL = modelServer();
    @Autowired private ObservationRegistry observations;
    @Autowired private AiChatClientProvider provider;

    @DynamicPropertySource static void modelProperties(DynamicPropertyRegistry properties) {
        properties.add("ai.agent.qwen.base-url", () -> "http://127.0.0.1:" + MODEL.getAddress().getPort() + "/v1");
    }

    @AfterAll static void closeModel() { MODEL.stop(0); }

    @Test void compatibleModelUsesApplicationObservationRegistry() {
        var names = new CopyOnWriteArrayList<String>();
        observations.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStart(Observation.Context context) { names.add(context.getName()); }
        });
        assertThat(provider.selection("qwen").chatClient().prompt("synthetic-input").call().content()).isEqualTo("synthetic-output");
        assertThat(names).contains("gen_ai.client.operation");
    }

    private static HttpServer modelServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = """
                        {"id":"synthetic","object":"chat.completion","created":1,"model":"qwen-test",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"synthetic-output"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
    }

    @Test
    void shouldSelectQwenAsDefaultProvider() {
        AiChatClientProvider.CurrentClient currentClient = provider.defaultSelection();

        assertEquals("qwen", currentClient.provider());
        assertEquals("qwen-test", currentClient.model());
        assertTrue(provider.supportedProviders().contains("qwen"));
    }

    @Test
    void shouldFallbackUnsupportedProviderToGoogle() {
        AiChatClientProvider.CurrentClient currentClient = provider.selection("unknown");

        assertEquals("google", currentClient.provider());
    }
}
