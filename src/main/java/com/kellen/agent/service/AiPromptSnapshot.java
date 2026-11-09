package com.kellen.agent.service;

/** 一次解析得到的 Prompt 正文与版本；日志表示始终排除正文。 */
public record AiPromptSnapshot(String key, String version, String source, String template) {
    public AiPromptSnapshot {
        key = metadata(key);
        version = metadata(version);
        source = metadata(source);
        template = template == null ? "" : template;
    }

    static String metadata(String value) {
        return value != null && value.matches("[A-Za-z0-9_.-]{1,128}") ? value : "unknown";
    }

    static AiPromptSnapshot unknown() {
        return new AiPromptSnapshot("unknown", "unknown", "unknown", "");
    }

    @Override
    public String toString() {
        return "AiPromptSnapshot[key=" + key + ", version=" + version + ", source=" + source + "]";
    }
}
