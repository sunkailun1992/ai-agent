package com.kellen.agent.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.kellen.agent.service.StreamingCallCancellation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.model.SpringAIModels;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatProperties;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.cache.GoogleGenAiCachedContentService;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;

/** 保留 Google native 认证与取消，支持受控代理入口并将 SDK/模型重试限制为一次尝试。 */
// Properties 由官方 AutoConfiguration 稍后登记，不能提前 Enable：其 cached-content 条件会在绑定 BPP 前取模型。
@AutoConfiguration(before = GoogleGenAiChatAutoConfiguration.class)
@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = SpringAIModels.GOOGLE_GEN_AI,
        matchIfMissing = true)
public class GoogleStreamingTransportConfiguration {
    private static final Logger log = LoggerFactory.getLogger(GoogleStreamingTransportConfiguration.class);

    /** 保持 Spring AI 2.0.0 官方自动配置的模式选择、空值检查与 credentials-uri 读取语义。 */
    @Bean
    @ConditionalOnMissingBean(Client.class)
    public Client googleGenAiClient(GoogleGenAiConnectionProperties properties,
                                   @Value("${ai.agent.google.base-url:}") String baseUrl) throws IOException {
        Client.Builder builder = Client.builder().clientOptions(ClientOptions.builder().customHttpClient(
                StreamingCallCancellation.googleHttpClient()).build());
        boolean hasApiKey = StringUtils.hasText(properties.getApiKey());
        boolean hasVertexConfig = StringUtils.hasText(properties.getProjectId()) && StringUtils.hasText(properties.getLocation());
        HttpOptions.Builder httpOptions = HttpOptions.builder().retryOptions(HttpRetryOptions.builder().attempts(1).build());
        if (StringUtils.hasText(baseUrl)) {
            Assert.isTrue(!properties.isVertexAi() && (hasApiKey || !hasVertexConfig),
                    "ai.agent.google.base-url is supported only in Gemini Developer API mode");
            httpOptions.baseUrl(nativeBaseUrl(baseUrl));
        }
        builder.httpOptions(httpOptions.build());
        if (hasApiKey && hasVertexConfig) {
            if (properties.isVertexAi()) log.info("Both API Key and Vertex AI config detected. Vertex AI mode is explicitly enabled; the API key will be ignored.");
            else log.warn("Both API Key and Vertex AI config detected. Defaulting to Gemini Developer API (API Key). To use Vertex AI instead, set 'spring.ai.google.genai.vertex-ai=true'.");
        }
        if (properties.isVertexAi()) configureVertexAi(builder, properties);
        else if (hasApiKey) builder.apiKey(properties.getApiKey());
        else if (hasVertexConfig) {
            log.debug("Project ID and Location detected. Defaulting to Vertex AI mode.");
            configureVertexAi(builder, properties);
        } else throw new IllegalStateException("Incomplete Google GenAI configuration: Provide 'api-key' for Gemini API or 'project-id' and 'location' for Vertex AI.");
        return builder.build();
    }

    /** 不发布全局 RetryTemplate；只收紧 Google 模型自身的同步调用，流式仍使用 native SDK。 */
    @Bean
    @ConditionalOnMissingBean(GoogleGenAiChatModel.class)
    public GoogleGenAiChatModel agentGoogleGenAiChatModel(Client client, GoogleGenAiChatProperties properties,
            ToolCallingManager toolCallingManager, ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<ChatModelObservationConvention> conventions) {
        var model = GoogleGenAiChatModel.builder().genAiClient(client).options(properties.toOptions())
                .toolCallingManager(toolCallingManager).retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .observationRegistry(observations.getIfUnique(() -> ObservationRegistry.NOOP)).build();
        conventions.ifAvailable(model::setObservationConvention);
        return model;
    }

    // 独立 bean 名避免官方 cached-content 条件在属性绑定 BPP 建立前主动 getBean(model)。
    @Bean
    @ConditionalOnBean(name = "agentGoogleGenAiChatModel")
    @ConditionalOnMissingBean(GoogleGenAiCachedContentService.class)
    @ConditionalOnProperty(prefix = "spring.ai.google.genai.chat", name = "enable-cached-content",
            havingValue = "true", matchIfMissing = true)
    public GoogleGenAiCachedContentService agentGoogleGenAiCachedContentService(GoogleGenAiChatModel model) {
        return model.getCachedContentService();
    }

    private String nativeBaseUrl(String value) {
        String message = "ai.agent.google.base-url must be an absolute HTTP(S) URL without credentials, query or fragment";
        URI uri;
        try { uri = URI.create(value.trim()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(message); }
        Assert.isTrue(("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null && uri.getRawFragment() == null, message);
        return uri.toString().replaceAll("/+$", "");
    }

    private void configureVertexAi(Client.Builder builder, GoogleGenAiConnectionProperties properties) throws IOException {
        Assert.hasText(properties.getProjectId(), "Google GenAI project-id must be set for Vertex AI mode!");
        Assert.hasText(properties.getLocation(), "Google GenAI location must be set for Vertex AI mode!");
        builder.project(properties.getProjectId()).location(properties.getLocation()).vertexAI(true);
        if (properties.getCredentialsUri() != null) {
            try (var input = properties.getCredentialsUri().getInputStream()) {
                builder.credentials(GoogleCredentials.fromStream(input));
            }
        }
    }
}
