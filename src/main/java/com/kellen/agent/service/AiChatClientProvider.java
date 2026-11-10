package com.kellen.agent.service;

import com.kellen.agent.config.AgentProperties;
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
    private static final String PROVIDER_QWEN = "qwen";
    private static final List<String> SUPPORTED_PROVIDER_LIST = List.of(PROVIDER_GOOGLE, PROVIDER_DEEPSEEK, PROVIDER_QWEN);
    private static final Set<String> SUPPORTED_PROVIDERS = Set.copyOf(SUPPORTED_PROVIDER_LIST);

    private final ObservationRegistry observationRegistry;
    private final String defaultProvider;
    private final String googleModel;
    private final String deepSeekModel;
    private final String qwenModel;
    private final ChatClient googleChatClient;
    private final ChatClient deepSeekChatClient;
    private final ChatClient qwenChatClient;

    public AiChatClientProvider(ChatClient.Builder googleBuilder,
                                AgentProperties properties,
                                ObservationRegistry observationRegistry,
                                @Value("${spring.ai.google.genai.chat.options.model:gemini-3.1-flash-lite}") String googleModel,
                                @Value("${spring.ai.google.genai.chat.options.maxOutputTokens:8192}") int googleMaxOutputTokens) {
        this.observationRegistry = observationRegistry;
        this.defaultProvider = normalizeProviderOrFallback(properties.provider());
        this.googleModel = googleModel;
        this.deepSeekModel = properties.deepseek().model();
        this.qwenModel = properties.qwen().model();
        this.googleChatClient = buildGoogleChatClient(googleBuilder, googleMaxOutputTokens, properties.google().thinking());
        this.deepSeekChatClient = ChatClient.builder(buildDeepSeekChatModel(properties.deepseek())).defaultAdvisors(new StreamingCancellationAdvisor()).build();
        this.qwenChatClient = ChatClient.builder(buildQwenChatModel(properties.qwen())).defaultAdvisors(new StreamingCancellationAdvisor()).build();
        log.info("AI Agent Provider 初始化完成: defaultProvider={}, googleModel={}, deepSeekModel={}, qwenModel={}",
                this.defaultProvider, this.googleModel, this.deepSeekModel, this.qwenModel);
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
        if (PROVIDER_QWEN.equals(provider)) {
            return new CurrentClient(provider, qwenModel, qwenChatClient);
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

    /**
     * 返回当前执行层支持的 Provider 列表。
     *
     * @return Provider 列表，按页面展示顺序排列。
     */
    public List<String> supportedProviders() {
        return SUPPORTED_PROVIDER_LIST;
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
                .defaultAdvisors(new StreamingCancellationAdvisor())
                .build();
    }

    private OpenAiChatModel buildDeepSeekChatModel(AgentProperties.DeepSeekProperties deepseek) {
        Duration timeout = Duration.ofSeconds(Math.max(deepseek.timeoutSeconds(), 30));
        Map<String, Object> extraBody = Map.of("thinking", Map.of("type", deepseek.thinking().enabled() ? "enabled" : "disabled"));
        return buildOpenAiCompatibleChatModel(
                deepseek.baseUrl(),
                deepseek.apiKey(),
                deepseek.model(),
                deepseek.generationDefaultsSource(),
                deepseek.temperature(),
                deepseek.topP(),
                deepseek.maxTokens(),
                timeout,
                normalizeReasoningEffort(deepseek.thinking().reasoningEffort()),
                extraBody
        );
    }

    private OpenAiChatModel buildQwenChatModel(AgentProperties.QwenProperties qwen) {
        Duration timeout = Duration.ofSeconds(Math.max(qwen.timeoutSeconds(), 30));
        return buildOpenAiCompatibleChatModel(
                qwen.baseUrl(),
                qwen.apiKey(),
                qwen.model(),
                qwen.generationDefaultsSource(),
                qwen.temperature(),
                qwen.topP(),
                qwen.maxTokens(),
                timeout,
                null,
                Map.of()
        );
    }

    private OpenAiChatModel buildOpenAiCompatibleChatModel(String baseUrl,
                                                          String apiKey,
                                                          String model,
                                                          AgentProperties.GenerationDefaultsSource generationDefaultsSource,
                                                          double temperature,
                                                          double topP,
                                                          int maxTokens,
                                                          Duration timeout,
                                                          String reasoningEffort,
                                                          Map<String, Object> extraBody) {
        List<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers = List.of(
                builder -> builder.interceptor(StreamingCallCancellation.openAiInterceptor()));
        OpenAIClient openAiClient = OpenAiSetup.setupSyncClient(baseUrl, apiKey,
                null, null, null, null, false, false, model, timeout, 0,
                null, null, observationRegistry, null, httpClientCustomizers);
        OpenAIClientAsync openAiClientAsync = OpenAiSetup.setupAsyncClient(baseUrl, apiKey,
                null, null, null, null, false, false, model, timeout, 0,
                null, null, observationRegistry, null, httpClientCustomizers);
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(timeout)
                .maxRetries(0)
                .model(model);
        // 网关模式只保留传输/协议选项，不能让服务默认值覆盖原生 deployment 配置。
        if (generationDefaultsSource == AgentProperties.GenerationDefaultsSource.SERVICE) {
            optionsBuilder.temperature(temperature).topP(topP).maxTokens(Math.max(maxTokens, 1024));
            if (hasText(reasoningEffort)) {
                optionsBuilder.reasoningEffort(reasoningEffort);
            }
            if (extraBody != null && !extraBody.isEmpty()) {
                optionsBuilder.extraBody(extraBody);
            }
        }
        OpenAiChatOptions options = optionsBuilder.build();
        return OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClientAsync)
                .options(options)
                .observationRegistry(observationRegistry)
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
