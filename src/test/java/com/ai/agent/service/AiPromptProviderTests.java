package com.ai.agent.service;

import com.kellen.agent.config.AiPromptProperties;
import com.kellen.agent.service.AiPromptProvider;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AiPromptProvider 单元测试。
 */
class AiPromptProviderTests {

    /**
     * 验证 Nacos AI Prompt Registry 优先于 YAML 兜底 Prompt。
     */
    @Test
    void registryPromptWinsBeforeYamlPrompt() {
        AiPromptProperties properties = new AiPromptProperties();
        AiPromptProperties.PromptDefinition definition = new AiPromptProperties.PromptDefinition();
        definition.setLabel("stable");
        definition.setText("YAML Prompt");
        properties.setDefinitions(Map.of(AiPromptProvider.PATIENT_ANALYSIS_SYSTEM, definition));
        AiPromptProvider provider = new AiPromptProvider(properties, key -> Optional.of("Registry Prompt"));

        String prompt = provider.prompt(AiPromptProvider.PATIENT_ANALYSIS_SYSTEM, "Fallback Prompt");

        assertEquals("Registry Prompt", prompt);
    }

    /**
     * 验证标签不匹配时回退本地兜底，避免 canary Prompt 误进 stable 链路。
     */
    @Test
    void fallsBackWhenYamlLabelDoesNotMatch() {
        AiPromptProperties properties = new AiPromptProperties();
        properties.setActiveLabel("stable");
        AiPromptProperties.PromptDefinition definition = new AiPromptProperties.PromptDefinition();
        definition.setLabel("canary");
        definition.setText("Canary Prompt");
        properties.setDefinitions(Map.of(AiPromptProvider.QUESTIONNAIRE_REPLY_SYSTEM, definition));
        AiPromptProvider provider = new AiPromptProvider(properties, key -> Optional.empty());

        String prompt = provider.prompt(AiPromptProvider.QUESTIONNAIRE_REPLY_SYSTEM, "Fallback Prompt");

        assertEquals("Fallback Prompt", prompt);
    }
}
