package com.kellen.agent.service;

import com.kellen.agent.config.AiPromptProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Optional;

/**
 * Agent Prompt 统一读取入口。
 */
@Service
public class AiPromptProvider {

    public static final String PATIENT_ANALYSIS_SYSTEM = "patient-analysis-system";
    public static final String QUESTIONNAIRE_INTERPRETER_SYSTEM = "questionnaire-interpreter-system";
    public static final String QUESTIONNAIRE_REPLY_SYSTEM = "questionnaire-reply-system";

    private final AiPromptProperties properties;
    private final AiPromptRegistryClient registryClient;

    public AiPromptProvider(AiPromptProperties properties, AiPromptRegistryClient registryClient) {
        this.properties = properties;
        this.registryClient = registryClient;
    }

    /**
     * 获取 Prompt 正文。
     *
     * @param key      Prompt Key。
     * @param fallback 本地兜底。
     * @return 最终 Prompt。
     */
    public String prompt(String key, String fallback) {
        if (!properties.isEnabled()) {
            return fallback;
        }
        Optional<String> registryPrompt = registryClient.prompt(key);
        if (registryPrompt.isPresent()) {
            return registryPrompt.get();
        }
        AiPromptProperties.PromptDefinition definition = properties.getDefinitions()
                .get(properties.normalizeKey(key));
        if (definition == null || !definition.isEnabled() || !StringUtils.hasText(definition.getText())) {
            return fallback;
        }
        if (StringUtils.hasText(properties.getActiveLabel())
                && StringUtils.hasText(definition.getLabel())
                && !properties.getActiveLabel().trim().equalsIgnoreCase(definition.getLabel().trim())) {
            return fallback;
        }
        return definition.getText().trim();
    }
}
