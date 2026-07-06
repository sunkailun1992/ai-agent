package com.kellen.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kellen.agent.config.PromptSyncProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * classpath Prompt 发布文件加载器。
 */
@Component
public class PromptDefinitionLoader {

    private final PromptSyncProperties properties;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromptDefinitionLoader(PromptSyncProperties properties) {
        this.properties = properties;
    }

    /**
     * 加载全部 Prompt 发布定义。
     *
     * @return 定义列表，按资源文件名稳定排序。
     */
    public List<PromptDefinition> load() {
        try {
            Resource[] resources = resolver.getResources(properties.getSourcePattern());
            return java.util.Arrays.stream(resources)
                    .sorted(Comparator.comparing(this::resourceName))
                    .map(this::loadOne)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("加载 Prompt 发布定义失败: " + properties.getSourcePattern(), exception);
        }
    }

    private PromptDefinition loadOne(Resource resource) {
        try {
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(content);
            String key = required(root, "promptKey", resource);
            String version = required(root, "version", resource);
            String template = required(root, "template", resource).strip();
            if (!StringUtils.hasText(template)) {
                throw new IllegalArgumentException("Prompt 模板正文不能为空: " + resourceName(resource));
            }
            return new PromptDefinition(
                    key,
                    version,
                    text(root.path("description").asText("")),
                    stringList(root.path("bizTags")),
                    text(root.path("commitMsg").asText("sync prompt from ai-agent resource")),
                    template,
                    variablesJson(root.path("variables")),
                    labels(root, version),
                    PromptChecksums.sha256(template),
                    root.path("enabled").asBoolean(true),
                    resourceName(resource)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("解析 Prompt 发布定义 JSON 失败: " + resourceName(resource), exception);
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Prompt 发布定义失败: " + resourceName(resource), exception);
        }
    }

    private String required(JsonNode root, String field, Resource resource) {
        String value = root.path(field).asText("");
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Prompt 发布定义缺少字段 " + field + ": " + resourceName(resource));
        }
        return value.trim();
    }

    private String variablesJson(JsonNode variables) throws JsonProcessingException {
        if (variables == null || variables.isMissingNode() || variables.isNull()) {
            return "[]";
        }
        return objectMapper.writeValueAsString(variables);
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText("");
                if (StringUtils.hasText(value)) {
                    values.add(value.trim());
                }
            }
            return values;
        }
        String value = node.asText("");
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private Map<String, String> labels(JsonNode root, String version) {
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        JsonNode labelMap = root.path("labels");
        if (labelMap.isObject()) {
            labelMap.properties().forEach(entry -> {
                String label = text(entry.getKey());
                String targetVersion = text(entry.getValue().asText(""));
                if (StringUtils.hasText(label) && StringUtils.hasText(targetVersion)) {
                    labels.put(label, targetVersion);
                }
            });
        }
        String singleLabel = root.path("label").asText("");
        if (StringUtils.hasText(singleLabel)) {
            labels.put(singleLabel.trim(), version);
        }
        if (labels.isEmpty() && StringUtils.hasText(properties.getDefaultLabel())) {
            labels.put(properties.getDefaultLabel().trim(), version);
        }
        return labels;
    }

    private String resourceName(Resource resource) {
        String filename = resource.getFilename();
        return StringUtils.hasText(filename) ? filename : resource.getDescription();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
