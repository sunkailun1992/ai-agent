package com.kellen.agent.service;

import java.util.Optional;

/**
 * Prompt Registry 读取抽象。
 */
@FunctionalInterface
public interface AiPromptRegistryClient {

    /**
     * 查询 Prompt 模板。
     *
     * @param promptKey Prompt Key。
     * @return Prompt 正文。
     */
    Optional<String> prompt(String promptKey);

    /** 兼容现有读取器；不能把未知版本推定为配置标签或正文摘要。 */
    default Optional<AiPromptSnapshot> snapshot(String promptKey) {
        return prompt(promptKey).map(template -> new AiPromptSnapshot(promptKey, "unknown", "nacos", template));
    }
}
