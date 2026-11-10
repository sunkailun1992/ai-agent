package com.kellen.agent.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.ClientOptions;
import com.kellen.agent.service.StreamingCallCancellation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.model.SpringAIModels;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.IOException;

/** 使用官方连接属性和认证模式创建 Google SDK Client，只扩展每轮 Call 的取消监听。 */
// Properties 由官方 AutoConfiguration 稍后登记，不能提前 Enable：其 cached-content 条件会在绑定 BPP 前取模型。
@AutoConfiguration(before = GoogleGenAiChatAutoConfiguration.class)
@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = SpringAIModels.GOOGLE_GEN_AI,
        matchIfMissing = true)
public class GoogleStreamingTransportConfiguration {
    private static final Logger log = LoggerFactory.getLogger(GoogleStreamingTransportConfiguration.class);

    /** 保持 Spring AI 2.0.0 官方自动配置的模式选择、空值检查与 credentials-uri 读取语义。 */
    @Bean
    @ConditionalOnMissingBean(Client.class)
    public Client googleGenAiClient(GoogleGenAiConnectionProperties properties) throws IOException {
        Client.Builder builder = Client.builder().clientOptions(ClientOptions.builder().customHttpClient(
                StreamingCallCancellation.googleHttpClient()).build());
        boolean hasApiKey = StringUtils.hasText(properties.getApiKey());
        boolean hasVertexConfig = StringUtils.hasText(properties.getProjectId()) && StringUtils.hasText(properties.getLocation());
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
