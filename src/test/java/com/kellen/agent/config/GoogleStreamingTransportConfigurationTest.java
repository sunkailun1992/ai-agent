package com.kellen.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证官方连接属性的既有认证模式选择；不获取 OAuth token、不发送模型请求。 */
class GoogleStreamingTransportConfigurationTest {
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(GoogleGenAiConnectionProperties.class)
    static class BoundProperties { }

    private final GoogleStreamingTransportConfiguration configuration = new GoogleStreamingTransportConfiguration();

    @Test void actualSpringBindingUsesTheExistingPropertyPrefix() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(GoogleStreamingTransportConfiguration.class, BoundProperties.class)
                .withPropertyValues("spring.ai.google.genai.api-key=synthetic-fixture")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(GoogleGenAiConnectionProperties.class).getApiKey()).isEqualTo("synthetic-fixture");
                    assertThat(context.getBean(com.google.genai.Client.class).apiKey()).isEqualTo("synthetic-fixture");
                });
    }

    @Test void incompleteConnectionFailsClosedLikeTheOfficialAutoConfiguration() {
        assertThatThrownBy(() -> configuration.googleGenAiClient(new GoogleGenAiConnectionProperties()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Incomplete Google GenAI configuration");
    }

    @Test void apiKeyWinsOverImplicitVertexConfiguration() throws Exception {
        var properties = new GoogleGenAiConnectionProperties();
        properties.setApiKey("synthetic-fixture"); properties.setProjectId("synthetic-project"); properties.setLocation("synthetic-location");
        try (var client = configuration.googleGenAiClient(properties)) {
            assertThat(client.vertexAI()).isFalse(); assertThat(client.apiKey()).isEqualTo("synthetic-fixture");
        }
    }

    @Test void explicitVertexStillRequiresBothProjectAndLocationEvenWhenAnApiKeyExists() {
        var properties = new GoogleGenAiConnectionProperties(); properties.setVertexAi(true); properties.setApiKey("synthetic-fixture");
        assertThatThrownBy(() -> configuration.googleGenAiClient(properties)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project-id");
        properties.setProjectId("synthetic-project");
        assertThatThrownBy(() -> configuration.googleGenAiClient(properties)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("location");
    }
}
