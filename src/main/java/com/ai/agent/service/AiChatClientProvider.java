package com.ai.agent.service;

import com.ai.agent.config.AgentProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Agent 执行层模型 Provider 管理器。
 *
 * <p>该服务只构建无记忆 ChatClient；主 AI 服务负责会话记忆和审计，
 * Agent 执行层只消费调用方传入的上下文。</p>
 */
@Service
public class AiChatClientProvider {

    private static final Logger log = LoggerFactory.getLogger(AiChatClientProvider.class);

    private static final String PROVIDER_GOOGLE = "google";
    private static final String PROVIDER_DEEPSEEK = "deepseek";
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(PROVIDER_GOOGLE, PROVIDER_DEEPSEEK);

    private final String defaultProvider;
    private final String googleModel;
    private final String deepSeekModel;
    private final ChatClient googleChatClient;
    private final ChatClient deepSeekChatClient;

    public AiChatClientProvider(ChatClient.Builder googleBuilder,
                                AgentProperties properties,
                                @Value("${spring.ai.google.genai.chat.options.model:gemini-3.1-flash-lite}") String googleModel,
                                @Value("${spring.ai.google.genai.chat.options.maxOutputTokens:8192}") int googleMaxOutputTokens) {
        this.defaultProvider = normalizeProviderOrFallback(properties.provider());
        this.googleModel = googleModel;
        this.deepSeekModel = properties.deepseek().model();
        this.googleChatClient = buildGoogleChatClient(googleBuilder, googleMaxOutputTokens, properties.google().thinking());
        this.deepSeekChatClient = ChatClient.builder(buildDeepSeekChatModel(properties.deepseek())).build();
        log.info("AI Agent Provider 初始化完成: defaultProvider={}, googleModel={}, deepSeekModel={}",
                this.defaultProvider, this.googleModel, this.deepSeekModel);
    }

    /**
     * 获取当前模型选择。
     *
     * @param providerOverride 请求级 Provider 覆盖，可空。
     * @return 当前模型选择。
     */
    public CurrentClient selection(String providerOverride) {
        String provider = normalizeProviderOrFallback(hasText(providerOverride) ? providerOverride : defaultProvider);
        if (PROVIDER_DEEPSEEK.equals(provider)) {
            return new CurrentClient(provider, deepSeekModel, deepSeekChatClient);
        }
        return new CurrentClient(PROVIDER_GOOGLE, googleModel, googleChatClient);
    }

    /**
     * 当前默认模型信息。
     *
     * @return 当前模型选择。
     */
    public CurrentClient defaultSelection() {
        return selection(null);
    }

    private ChatClient buildGoogleChatClient(ChatClient.Builder googleBuilder,
                                             int maxOutputTokens,
                                             AgentProperties.GoogleThinkingProperties thinking) {
        GoogleGenAiChatOptions.Builder options = GoogleGenAiChatOptions.builder()
                .maxOutputTokens(Math.max(maxOutputTokens, 1024));
        if (thinking.enabled()) {
            options.includeThoughts(thinking.includeThoughts());
            options.thinkingLevel(parseGoogleThinkingLevel(thinking.level()));
        }
        return googleBuilder.clone()
                .defaultOptions(options)
                .build();
    }

    private OpenAiChatModel buildDeepSeekChatModel(AgentProperties.DeepSeekProperties deepseek) {
        Duration timeout = Duration.ofSeconds(Math.max(deepseek.timeoutSeconds(), 30));
        List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers = List.of();
        OpenAIClient openAiClient = OpenAiSetup.setupSyncClient(deepseek.baseUrl(), deepseek.apiKey(),
                null, null, null, null, false, false, deepseek.model(), timeout, 0,
                null, null, ObservationRegistry.NOOP, null, httpClientCustomizers);
        OpenAIClientAsync openAiClientAsync = OpenAiSetup.setupAsyncClient(deepseek.baseUrl(), deepseek.apiKey(),
                null, null, null, null, false, false, deepseek.model(), timeout, 0,
                null, null, ObservationRegistry.NOOP, null, httpClientCustomizers);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(deepseek.baseUrl())
                .apiKey(deepseek.apiKey())
                .timeout(timeout)
                .maxRetries(0)
                .model(deepseek.model())
                .temperature(deepseek.temperature())
                .topP(deepseek.topP())
                .maxTokens(Math.max(deepseek.maxTokens(), 1024))
                .reasoningEffort(normalizeReasoningEffort(deepseek.thinking().reasoningEffort()))
                .extraBody(Map.of("thinking", Map.of("type", deepseek.thinking().enabled() ? "enabled" : "disabled")))
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(options)
                .build();
    }

    private GoogleGenAiThinkingLevel parseGoogleThinkingLevel(String level) {
        try {
            return GoogleGenAiThinkingLevel.valueOf(level.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            log.warn("Google thinking level 配置无效: {}，已回退到 MEDIUM", level);
            return GoogleGenAiThinkingLevel.MEDIUM;
        }
    }

    private String normalizeReasoningEffort(String reasoningEffort) {
        if (!hasText(reasoningEffort)) {
            return "medium";
        }
        String normalized = reasoningEffort.trim().toLowerCase(Locale.ROOT);
        if ("max".equals(normalized) || "maximum".equals(normalized)) {
            return "high";
        }
        if (!Set.of("low", "medium", "high").contains(normalized)) {
            log.warn("DeepSeek reasoning-effort 配置无效: {}，已回退到 medium", reasoningEffort);
            return "medium";
        }
        return normalized;
    }

    private String normalizeProviderOrFallback(String provider) {
        if (!hasText(provider)) {
            return PROVIDER_GOOGLE;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(normalized)) {
            log.warn("AI Provider 配置无效: {}，已回退到 google", provider);
            return PROVIDER_GOOGLE;
        }
        return normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 当前 AI 调用选择。
     *
     * @param provider   实际 Provider。
     * @param model      模型名。
     * @param chatClient ChatClient。
     */
    public record CurrentClient(String provider, String model, ChatClient chatClient) {
    }
}
