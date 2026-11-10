package com.kellen.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Agent 执行层配置。
 *
 * @param provider 默认模型供应商。
 * @param google   Google Gemini 扩展配置。
 * @param deepseek DeepSeek/OpenAI-compatible 配置。
 * @param qwen     通义千问 DashScope/OpenAI-compatible 配置。
 */
@ConfigurationProperties(prefix = "ai.agent")
public record AgentProperties(
        @DefaultValue("google") String provider,
        @DefaultValue GoogleProperties google,
        @DefaultValue DeepSeekProperties deepseek,
        @DefaultValue QwenProperties qwen
) {

    /** 生成参数来源；SERVICE 保留旧服务选项，DEPLOYMENT 由模型网关维护默认值。 */
    public enum GenerationDefaultsSource {
        SERVICE,
        DEPLOYMENT
    }

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
     * @param generationDefaultsSource 生成参数来源，默认 SERVICE；DEPLOYMENT 不下发服务采样、输出上限和思考覆盖。
     */
    public record DeepSeekProperties(
            @DefaultValue("https://api.deepseek.com") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("deepseek-chat") String model,
            @DefaultValue("0.3") double temperature,
            @DefaultValue("0.8") double topP,
            @DefaultValue("8192") int maxTokens,
            @DefaultValue("180") long timeoutSeconds,
            @DefaultValue DeepSeekThinkingProperties thinking,
            @DefaultValue("SERVICE") GenerationDefaultsSource generationDefaultsSource
    ) {
        @ConstructorBinding
        public DeepSeekProperties {
            if (generationDefaultsSource == null) generationDefaultsSource = GenerationDefaultsSource.SERVICE;
        }

        /** 保留旧构造签名；直接调用方未提供来源时继续使用服务参数。 */
        public DeepSeekProperties(String baseUrl, String apiKey, String model, double temperature, double topP,
                                  int maxTokens, long timeoutSeconds, DeepSeekThinkingProperties thinking) {
            this(baseUrl, apiKey, model, temperature, topP, maxTokens, timeoutSeconds, thinking, GenerationDefaultsSource.SERVICE);
        }
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

    /**
     * 通义千问 DashScope/OpenAI-compatible 模型配置。
     *
     * @param baseUrl        接口基础地址。
     * @param apiKey         API Key。
     * @param model          模型名。
     * @param temperature    采样温度。
     * @param topP           nucleus 采样范围。
     * @param maxTokens      输出 token 上限。
     * @param timeoutSeconds 请求超时秒数。
     * @param generationDefaultsSource 生成参数来源，默认 SERVICE；DEPLOYMENT 不下发服务采样和输出上限。
     */
    public record QwenProperties(
            @DefaultValue("https://dashscope.aliyuncs.com/compatible-mode/v1") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("qwen-plus") String model,
            @DefaultValue("0.3") double temperature,
            @DefaultValue("0.8") double topP,
            @DefaultValue("8192") int maxTokens,
            @DefaultValue("180") long timeoutSeconds,
            @DefaultValue("SERVICE") GenerationDefaultsSource generationDefaultsSource
    ) {
        @ConstructorBinding
        public QwenProperties {
            if (generationDefaultsSource == null) generationDefaultsSource = GenerationDefaultsSource.SERVICE;
        }

        /** 保留旧构造签名；直接调用方未提供来源时继续使用服务参数。 */
        public QwenProperties(String baseUrl, String apiKey, String model, double temperature, double topP,
                              int maxTokens, long timeoutSeconds) {
            this(baseUrl, apiKey, model, temperature, topP, maxTokens, timeoutSeconds, GenerationDefaultsSource.SERVICE);
        }
    }
}
