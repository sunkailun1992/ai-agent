package com.ai.agent.service;

import com.kellen.agent.service.AiPromptProvider;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AiPromptProvider 单元测试。
 */
class AiPromptProviderTests {

    /**
     * 验证 Prompt 只从 Nacos AI Prompt Registry 返回。
     */
    @Test
    void returnsRegistryPrompt() {
        AiPromptProvider provider = new AiPromptProvider(key -> Optional.of("Registry Prompt"));

        String prompt = provider.prompt(AiPromptProvider.PATIENT_ANALYSIS_SYSTEM);

        assertEquals("Registry Prompt", prompt);
    }

    /**
     * 验证注册中心没有 Prompt 时不再回退本地配置。
     */
    @Test
    void throwsWhenRegistryPromptMissing() {
        AiPromptProvider provider = new AiPromptProvider(key -> Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> provider.prompt(AiPromptProvider.QUESTIONNAIRE_REPLY_SYSTEM));
    }
}
