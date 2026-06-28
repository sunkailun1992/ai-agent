package com.ai.agent.service;

import com.kellen.agent.AiAgentApplication;
import com.kellen.agent.service.AiChatClientProvider;
import org.junit.jupiter.api.Test;
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

    @Autowired
    private AiChatClientProvider provider;

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
