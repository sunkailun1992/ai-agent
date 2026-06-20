package com.ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 执行层配置。
 *
 * @param provider 默认模型供应商。
 * @param google   Google Gemini 扩展配置。
 * @param deepseek DeepSeek/OpenAI-compatible 配置。
 */
@ConfigurationProperties(prefix = "ai.agent")
public record AgentProperties(
        @DefaultValue("google") String provider,
        @DefaultValue GoogleProperties google,
        @DefaultValue DeepSeekProperties deepseek
) {

    /**
     * Google Gemini 扩展配置。
     *
     * @param thinking Gemini thinking 配置。
     */
    public record GoogleProperties(
            @DefaultValue GoogleThinkingProperties thinking
    ) {
    }

    /**
     * Gemini thinking 配置。
     *
     * @param enabled         是否开启 thinking 覆盖。
     * @param includeThoughts 是否包含思考内容。
     * @param level           thinking 强度。
     */
    public record GoogleThinkingProperties(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("false") boolean includeThoughts,
            @DefaultValue("MEDIUM") String level
    ) {
    }

    /**
     * DeepSeek/OpenAI-compatible 模型配置。
     *
     * @param baseUrl        接口基础地址。
     * @param apiKey         API Key。
     * @param model          模型名。
     * @param temperature    采样温度。
     * @param topP           nucleus 采样范围。
     * @param maxTokens      输出 token 上限。
     * @param timeoutSeconds 请求超时秒数。
     * @param thinking       思考模式配置。
     */
    public record DeepSeekProperties(
            @DefaultValue("https://api.deepseek.com") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("deepseek-chat") String model,
            @DefaultValue("0.3") double temperature,
            @DefaultValue("0.8") double topP,
            @DefaultValue("8192") int maxTokens,
            @DefaultValue("180") long timeoutSeconds,
            @DefaultValue DeepSeekThinkingProperties thinking
    ) {
    }

    /**
     * DeepSeek 思考模式配置。
     *
     * @param enabled         是否开启思考模式。
     * @param reasoningEffort 思考强度。
     */
    public record DeepSeekThinkingProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("medium") String reasoningEffort
    ) {
    }
}
