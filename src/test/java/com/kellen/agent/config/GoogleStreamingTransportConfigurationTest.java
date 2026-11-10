package com.kellen.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证官方连接属性的既有认证模式选择；不获取 OAuth token、不发送模型请求。 */
class GoogleStreamingTransportConfigurationTest {
    private final GoogleStreamingTransportConfiguration configuration = new GoogleStreamingTransportConfiguration();

    @Test void actualSpringBindingUsesTheExistingPropertyPrefix() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        GoogleStreamingTransportConfiguration.class,
                        org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration.class))
                .withBean(org.springframework.ai.model.tool.ToolCallingManager.class,
                        () -> org.springframework.ai.model.tool.ToolCallingManager.builder().build())
                .withPropertyValues("spring.ai.google.genai.api-key=synthetic-fixture")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GoogleGenAiConnectionProperties.class).getApiKey()).isEqualTo("synthetic-fixture");
                    assertThat(context.getBean(com.google.genai.Client.class).apiKey()).isEqualTo("synthetic-fixture");
                    assertThat(context).hasSingleBean(org.springframework.ai.google.genai.GoogleGenAiChatModel.class)
                            .hasSingleBean(org.springframework.ai.google.genai.cache.GoogleGenAiCachedContentService.class);
                    assertThat(context.getBeansOfType(org.springframework.core.retry.RetryTemplate.class)).isEmpty();
                });
    }

    @Test void incompleteConnectionFailsClosedLikeTheOfficialAutoConfiguration() {
        assertThatThrownBy(() -> configuration.googleGenAiClient(new GoogleGenAiConnectionProperties(), ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Incomplete Google GenAI configuration");
    }

    @Test void apiKeyWinsOverImplicitVertexConfiguration() throws Exception {
        var properties = new GoogleGenAiConnectionProperties();
        properties.setApiKey("synthetic-fixture"); properties.setProjectId("synthetic-project"); properties.setLocation("synthetic-location");
        try (var client = configuration.googleGenAiClient(properties, "")) {
            assertThat(client.vertexAI()).isFalse(); assertThat(client.apiKey()).isEqualTo("synthetic-fixture");
        }
    }

    @Test void explicitVertexStillRequiresBothProjectAndLocationEvenWhenAnApiKeyExists() {
        var properties = new GoogleGenAiConnectionProperties(); properties.setVertexAi(true); properties.setApiKey("synthetic-fixture");
        assertThatThrownBy(() -> configuration.googleGenAiClient(properties, "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-id");
        properties.setProjectId("synthetic-project");
        assertThatThrownBy(() -> configuration.googleGenAiClient(properties, "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("location");
    }

    @Test void blankBaseUrlKeepsTheOfficialDeveloperEndpointAndProductionTransport() throws Exception {
        var properties = new GoogleGenAiConnectionProperties(); properties.setApiKey("synthetic-fixture");
        try (var client = configuration.googleGenAiClient(properties, "  ")) {
            Object baseUrl = org.springframework.test.util.ReflectionTestUtils.invokeMethod(client, "baseUrl");
            assertThat(baseUrl).isEqualTo(java.util.Optional.of("https://generativelanguage.googleapis.com"));
            var api = org.springframework.test.util.ReflectionTestUtils.getField(client, "apiClient");
            var http = (okhttp3.OkHttpClient) org.springframework.test.util.ReflectionTestUtils.getField(api, "httpClient");
            assertThat(http.retryOnConnectionFailure()).isFalse();
            assertThat(http.connectTimeoutMillis()).isZero();
            assertThat(http.readTimeoutMillis()).isZero();
            assertThat(http.writeTimeoutMillis()).isZero();
        }
    }

    @Test void malformedOrCredentialBearingBaseUrlsFailWithoutEchoingTheirValue() {
        var properties = new GoogleGenAiConnectionProperties(); properties.setApiKey("synthetic-fixture");
        for (String base : java.util.List.of("file:///synthetic", "/relative", "https://synthetic:private@invalid.test",
                "https://invalid.test/gemini?key=synthetic-private-query", "https://invalid.test/gemini#private", "http://[invalid")) {
            assertThatThrownBy(() -> configuration.googleGenAiClient(properties, base))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute HTTP(S)")
                    .hasMessageNotContaining("synthetic-private-query").hasMessageNotContaining("synthetic:private");
        }
    }

    @Test void aDeveloperProxyCannotRedirectVertexCredentials() {
        var properties = new GoogleGenAiConnectionProperties(); properties.setVertexAi(true);
        properties.setProjectId("synthetic-project"); properties.setLocation("synthetic-location");
        properties.setCredentialsUri(new org.springframework.core.io.AbstractResource() {
            @Override public String getDescription() { return "synthetic resource must not be opened"; }
            @Override public java.io.InputStream getInputStream() { throw new AssertionError("Credentials resource was read"); }
        });
        assertThatThrownBy(() -> configuration.googleGenAiClient(properties, "http://127.0.0.1:12345/gemini"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only in Gemini Developer API mode");
    }
}
