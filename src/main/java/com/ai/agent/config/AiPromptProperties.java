package com.ai.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AI Prompt 配置中心绑定。
 *
 * <p>作为 Nacos AI Prompt Registry 的兜底来源：Registry 不可用或未发布时，
 * Agent 仍可以从 YAML 配置读取 Prompt。</p>
 */
@ConfigurationProperties(prefix = "ai.prompts")
public class AiPromptProperties {

    private boolean enabled = true;
    private String activeLabel = "stable";
    private Map<String, PromptDefinition> definitions = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getActiveLabel() {
        return activeLabel;
    }

    public void setActiveLabel(String activeLabel) {
        this.activeLabel = activeLabel;
    }

    public Map<String, PromptDefinition> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(Map<String, PromptDefinition> definitions) {
        this.definitions = definitions == null ? new LinkedHashMap<>() : definitions;
    }

    public String normalizeKey(String key) {
        return StringUtils.hasText(key) ? key.trim().toLowerCase(Locale.ROOT) : "";
    }

    /**
     * 单个 Prompt 定义。
     */
    public static class PromptDefinition {

        private boolean enabled = true;
        private String label = "stable";
        private String text = "";
        private String version = "";
        private String description = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }
}
