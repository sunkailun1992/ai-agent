package com.kellen.agent.service;

import java.util.List;
import java.util.Map;

/**
 * 本地 Prompt 发布定义。
 *
 * @param key           Prompt Key。
 * @param version       Prompt 版本。
 * @param description   Prompt 描述。
 * @param bizTags       Nacos 控制台业务标签。
 * @param commitMsg     发布审计说明。
 * @param template      Prompt 模板正文。
 * @param variablesJson Nacos Prompt variables JSON。
 * @param labels        Nacos Prompt labels，key 为 label，value 为版本。
 * @param checksum      模板 SHA-256 校验值，仅用于启动审计和漂移识别。
 * @param enabled       是否参与同步。
 * @param source        来源资源。
 */
public record PromptDefinition(
        String key,
        String version,
        String description,
        List<String> bizTags,
        String commitMsg,
        String template,
        String variablesJson,
        Map<String, String> labels,
        String checksum,
        boolean enabled,
        String source
) {
}
