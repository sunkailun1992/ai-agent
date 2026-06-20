package com.ai.agent.service;

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
}
