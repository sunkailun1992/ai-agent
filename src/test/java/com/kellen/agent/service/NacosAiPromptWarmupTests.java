package com.kellen.agent.service;

import com.kellen.agent.config.NacosAiPromptProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 启动预读：上线后立刻知道哪些 Prompt 读不到，而不是等第一份报告失败才发现。
 */
@ExtendWith(OutputCaptureExtension.class)
class NacosAiPromptWarmupTests {

    private final List<String> asked = new ArrayList<>();
    private NacosAiPromptProperties properties;
    private PromptDefinitionLoader loader;

    @BeforeEach
    void setUp() {
        properties = new NacosAiPromptProperties();
        properties.setEnabled(true);
        loader = mock(PromptDefinitionLoader.class);
        when(loader.load()).thenReturn(List.of(
                definition("summary-fixture", true),
                definition("media-fixture", true),
                definition("retired-system", false)));
    }

    @Test
    void readsEveryPublishedPromptAndNamesTheOnesItCannotRead(CapturedOutput output) {
        AiPromptRegistryClient registry = key -> {
            asked.add(key);
            return "media-fixture".equals(key) ? Optional.empty() : Optional.of("提示词正文");
        };

        List<String> missing = new NacosAiPromptWarmup(properties, loader, registry).warmUp();

        assertThat(asked).containsExactly("summary-fixture", "media-fixture");
        assertThat(missing).containsExactly("media-fixture");
        assertThat(output).contains("media-fixture").contains("PROMPT_UNAVAILABLE").doesNotContain("提示词正文");
    }

    @Test
    void aRegistryThatThrowsCountsAsUnreadableWithoutStoppingTheRest() {
        AiPromptRegistryClient registry = key -> {
            asked.add(key);
            if ("summary-fixture".equals(key)) {
                throw new IllegalStateException("boom");
            }
            return Optional.of("提示词正文");
        };

        List<String> missing = new NacosAiPromptWarmup(properties, loader, registry).warmUp();

        assertThat(asked).containsExactly("summary-fixture", "media-fixture");
        assertThat(missing).containsExactly("summary-fixture");
    }

    @Test
    void doesNothingWhenTheRegistryIsDisabled() {
        properties.setEnabled(false);
        AiPromptRegistryClient registry = mock(AiPromptRegistryClient.class);

        assertThat(new NacosAiPromptWarmup(properties, loader, registry).warmUp()).isEmpty();
        verifyNoInteractions(registry, loader);
    }

    private static PromptDefinition definition(String key, boolean enabled) {
        return new PromptDefinition(key, "1.0.0", "", List.of(), "", "正文", "[]", Map.of(), "sha", enabled, key);
    }
}
