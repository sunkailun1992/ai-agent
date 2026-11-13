package com.kellen.agent.config;

import com.google.genai.Client;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiChatAutoConfiguration;
import org.springframework.ai.model.google.genai.autoconfigure.chat.GoogleGenAiConnectionProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 服务配置独立绑定真实自动配置；不加载测试 application.yml、不连接 Nacos、不发送模型请求。 */
class NacosModelConfigurationTest {
    private static final String KEY = "synthetic-service-scoped-key";
    private static final String MODEL = "synthetic-service-native-model";

    @ParameterizedTest
    @ValueSource(strings = {"dev", "test", "prod"})
    void profileImportsRemoveSharedModelSecretsWithoutReorderingOtherSources(String profile) throws IOException {
        var environment = new StandardEnvironment();
        for (var source : new YamlPropertySourceLoader().load("production-profile",
                new FileSystemResource("src/main/resources/application-" + profile + ".yml"))) {
            environment.getPropertySources().addFirst(source);
        }
        var imports = new ArrayList<>(List.of(
                        "optional:nacos:logging.yml?refreshEnabled=true",
                        "optional:nacos:reuse-configuration.yaml?refreshEnabled=true",
                        "optional:nacos:traffic-governance.yaml?refreshEnabled=true",
                        "nacos:actuator.yaml?refreshEnabled=true",
                        "nacos:security-jwt.yaml?refreshEnabled=true",
                        "optional:nacos:swagger.yaml?refreshEnabled=true",
                        "optional:nacos:dubbo.yaml?refreshEnabled=true",
                        "optional:nacos:ai-agent-spring.yaml?refreshEnabled=true",
                        "optional:nacos:a2a.yaml?refreshEnabled=true"));
        if ("dev".equals(profile)) {
            imports.add("nacos:local-canary.yaml?refreshEnabled=false");
        }
        assertThat(Binder.get(environment).bind("spring.config.import", Bindable.listOf(String.class)).get())
                .containsExactlyElementsOf(imports);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "http://127.0.0.1:1/gemini"})
    void serviceConfigProvidesTheActualClientKeyModelAndOptionalBaseUrl(String baseUrl) {
        serviceContext(KEY, baseUrl).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GoogleGenAiConnectionProperties.class).getApiKey()).isEqualTo(KEY);
            Client client = context.getBean(Client.class);
            assertThat(client.apiKey()).isEqualTo(KEY);
            assertThat(context.getBean(GoogleGenAiChatModel.class).getOptions().getModel()).isEqualTo(MODEL);
            Object configuredBaseUrl = ReflectionTestUtils.invokeMethod(client, "baseUrl");
            assertThat(configuredBaseUrl)
                    .isEqualTo(Optional.of(baseUrl.isEmpty() ? "https://generativelanguage.googleapis.com" : baseUrl));
            assertThat(context.getEnvironment().getPropertySources().contains("systemEnvironment")).isFalse();
            assertThat(context.getEnvironment().getPropertySources().contains("systemProperties")).isFalse();
        });
    }

    @Test
    void missingServiceKeyDoesNotFallBackToSharedSecretsEnvironmentOrTestApplication() {
        serviceContext("", "").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("Incomplete Google GenAI configuration");
        });
    }

    private ApplicationContextRunner serviceContext(String key, String baseUrl) {
        String serviceYaml = """
                spring:
                  ai:
                    model:
                      chat: google-genai
                    google:
                      genai:
                        api-key: '%s'
                        chat:
                          options:
                            model: '%s'
                ai:
                  agent:
                    google:
                      base-url: '%s'
                """.formatted(key, MODEL, baseUrl);
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(GoogleStreamingTransportConfiguration.class,
                        GoogleGenAiChatAutoConfiguration.class))
                .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    try {
                        for (var source : new YamlPropertySourceLoader().load("ai-agent-spring.yaml",
                                new ByteArrayResource(serviceYaml.getBytes(StandardCharsets.UTF_8)))) {
                            sources.addFirst(source);
                        }
                    } catch (IOException error) {
                        throw new IllegalStateException("Synthetic service YAML could not be loaded", error);
                    }
                });
    }
}
