package com.kellen.agent.service;

import org.springframework.stereotype.Service;

/**
 * Agent Prompt 统一读取入口。
 */
@Service
public class AiPromptProvider {

    public static final String PATIENT_ANALYSIS_SYSTEM = "patient-analysis-system";
    public static final String QUESTIONNAIRE_INTERPRETER_SYSTEM = "questionnaire-interpreter-system";
    public static final String QUESTIONNAIRE_REPLY_SYSTEM = "questionnaire-reply-system";

    private final AiPromptRegistryClient registryClient;

    public AiPromptProvider(AiPromptRegistryClient registryClient) {
        this.registryClient = registryClient;
    }

    /**
     * 获取 Prompt 正文。
     *
     * @param key Prompt Key。
     * @return 最终 Prompt。
     */
    public String prompt(String key) {
        return registryClient.prompt(key)
                .orElseThrow(() -> new IllegalStateException("Nacos AI Prompt 未发布或不可读取: " + key));
    }
}
