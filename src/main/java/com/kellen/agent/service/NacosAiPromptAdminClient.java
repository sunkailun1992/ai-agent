package com.kellen.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kellen.agent.config.NacosAiPromptProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Nacos AI Prompt 管理端客户端。
 *
 * <p>Nacos 3.2.2 与 3.3.0 的 Java AI SDK 均未暴露 Prompt 发布接口（3.3.0 的 PromptService 只有读取与订阅），因此启动同步通过
 * Nacos 3.x Admin API 执行受控发布。</p>
 */
@Component
public class NacosAiPromptAdminClient {

    private static final Logger log = LoggerFactory.getLogger(NacosAiPromptAdminClient.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String CONTENT_TYPE_FORM = "application/x-www-form-urlencoded;charset=UTF-8";

    private final NacosAiPromptProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private volatile String accessToken;

    public NacosAiPromptAdminClient(NacosAiPromptProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * 是否具备连接 Nacos Prompt Registry 的必要配置。
     *
     * @return true 表示可尝试同步。
     */
    public boolean available() {
        return properties.isEnabled() && StringUtils.hasText(properties.getServerAddr());
    }

    /**
     * 查询指定 Prompt 版本。
     *
     * @param key     Prompt Key。
     * @param version 版本。
     * @return 远端快照。
     */
    public Optional<RemotePromptSnapshot> version(String key, String version) {
        Map<String, String> params = baseParams();
        params.put("promptKey", key);
        params.put("version", version);
        try {
            JsonNode data = request("GET", "/v3/admin/ai/prompt/version", params, true);
            if (data == null || data.isMissingNode() || data.isNull()) {
                return Optional.empty();
            }
            String template = findText(data, "template");
            String md5 = findText(data, "md5");
            return Optional.of(new RemotePromptSnapshot(
                    key,
                    firstText(findText(data, "version"), version),
                    template,
                    md5,
                    StringUtils.hasText(template) ? sha256(template.strip()) : ""
            ));
        } catch (IOException | InterruptedException exception) {
            restoreInterrupt(exception);
            throw new PromptAdminException("查询 Nacos AI Prompt 版本失败: " + key + "@" + version, exception);
        }
    }

    /**
     * 创建或更新草稿。
     *
     * @param definition Prompt 定义。
     */
    public void createOrUpdateDraft(PromptDefinition definition) {
        try {
            createDraft(definition);
        } catch (PromptAdminException exception) {
            log.debug("创建 Prompt 草稿失败，尝试更新现有草稿，promptKey={}, version={}, message={}",
                    definition.key(), definition.version(), exception.getMessage());
            updateDraft(definition);
        }
    }

    /**
     * 提交草稿。
     *
     * @param definition Prompt 定义。
     */
    public void submitDraft(PromptDefinition definition) {
        Map<String, String> form = baseParams();
        form.put("promptKey", definition.key());
        form.put("version", definition.version());
        postForm("/v3/admin/ai/prompt/submit", form);
    }

    /**
     * 发布 Prompt 版本。
     *
     * @param definition        Prompt 定义。
     * @param updateLatestLabel 是否更新 latest 标签。
     */
    public void publishVersion(PromptDefinition definition, boolean updateLatestLabel) {
        Map<String, String> form = baseParams();
        form.put("promptKey", definition.key());
        form.put("version", definition.version());
        form.put("updateLatestLabel", Boolean.toString(updateLatestLabel));
        postForm("/v3/admin/ai/prompt/publish", form);
    }

    /**
     * 强制发布 Prompt 版本。该接口仅用于显式开启的应急发布场景。
     *
     * @param definition        Prompt 定义。
     * @param updateLatestLabel 是否更新 latest 标签。
     */
    public void forcePublish(PromptDefinition definition, boolean updateLatestLabel) {
        Map<String, String> form = baseParams();
        form.put("promptKey", definition.key());
        form.put("version", definition.version());
        form.put("updateLatestLabel", Boolean.toString(updateLatestLabel));
        postForm("/v3/admin/ai/prompt/force-publish", form);
    }

    /**
     * 上线 Prompt 版本。
     *
     * @param definition Prompt 定义。
     */
    public void online(PromptDefinition definition) {
        Map<String, String> form = baseParams();
        form.put("promptKey", definition.key());
        form.put("version", definition.version());
        postForm("/v3/admin/ai/prompt/online", form);
    }

    /**
     * 更新 Prompt 标签。
     *
     * @param definition Prompt 定义。
     */
    public void updateLabels(PromptDefinition definition) {
        if (definition.labels().isEmpty()) {
            return;
        }
        ObjectNode labels = objectMapper.createObjectNode();
        definition.labels().forEach(labels::put);
        Map<String, String> form = baseParams();
        form.put("promptKey", definition.key());
        form.put("labels", labels.toString());
        requestForm("PUT", "/v3/admin/ai/prompt/labels", form);
    }

    private void createDraft(PromptDefinition definition) {
        Map<String, String> form = draftForm(definition);
        form.put("targetVersion", definition.version());
        postForm("/v3/admin/ai/prompt/draft", form);
    }

    private void updateDraft(PromptDefinition definition) {
        requestForm("PUT", "/v3/admin/ai/prompt/draft", draftForm(definition));
    }

    private Map<String, String> draftForm(PromptDefinition definition) {
        Map<String, String> form = baseParams();
        form.put("promptKey", definition.key());
        form.put("template", definition.template());
        form.put("variables", definition.variablesJson());
        form.put("commitMsg", commitMsg(definition));
        form.put("description", definition.description());
        form.put("bizTags", String.join(",", definition.bizTags()));
        return form;
    }

    private String commitMsg(PromptDefinition definition) {
        String message = StringUtils.hasText(definition.commitMsg())
                ? definition.commitMsg().trim()
                : "sync prompt from ai-agent resource";
        return message + " | version=" + definition.version() + " | sha256=" + definition.checksum();
    }

    private void postForm(String path, Map<String, String> form) {
        requestForm("POST", path, form);
    }

    private void requestForm(String method, String path, Map<String, String> form) {
        try {
            request(method, path, form, false);
        } catch (IOException | InterruptedException exception) {
            restoreInterrupt(exception);
            throw new PromptAdminException("调用 Nacos AI Prompt 管理接口失败: " + path, exception);
        }
    }

    private JsonNode request(String method, String path, Map<String, String> params, boolean allowNotFound)
            throws IOException, InterruptedException {
        HttpRequest request = buildRequest(method, path, params);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 401 && StringUtils.hasText(properties.getUsername())) {
            accessToken = null;
            request = buildRequest(method, path, params);
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        if (allowNotFound && isNotFound(response)) {
            return null;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PromptAdminException("Nacos AI Prompt 管理接口 HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        JsonNode root = parse(response.body());
        if (root == null || root.isMissingNode() || root.isNull()) {
            return objectMapper.nullNode();
        }
        if (root.has("code") && root.path("code").asInt(0) != 0) {
            if (allowNotFound && isNotFound(root)) {
                return null;
            }
            throw new PromptAdminException("Nacos AI Prompt 管理接口返回失败: " + abbreviate(response.body()));
        }
        return root.has("data") ? root.path("data") : root;
    }

    private HttpRequest buildRequest(String method, String path, Map<String, String> params) {
        HttpRequest.Builder builder;
        if ("GET".equalsIgnoreCase(method)) {
            builder = HttpRequest.newBuilder(URI.create(endpoint(path) + "?" + form(params))).GET();
        } else {
            builder = HttpRequest.newBuilder(URI.create(endpoint(path)))
                    .method(method, HttpRequest.BodyPublishers.ofString(form(params), StandardCharsets.UTF_8))
                    .header("Content-Type", CONTENT_TYPE_FORM);
        }
        String token = token();
        if (StringUtils.hasText(token)) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.timeout(REQUEST_TIMEOUT).build();
    }

    private String token() {
        if (!StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(properties.getPassword())) {
            return "";
        }
        String current = accessToken;
        if (StringUtils.hasText(current)) {
            return current;
        }
        synchronized (this) {
            if (StringUtils.hasText(accessToken)) {
                return accessToken;
            }
            accessToken = login();
            return accessToken;
        }
    }

    private String login() {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("username", properties.getUsername());
        form.put("password", properties.getPassword());
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint("/v3/auth/user/login")))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", CONTENT_TYPE_FORM)
                .POST(HttpRequest.BodyPublishers.ofString(form(form), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new PromptAdminException("Nacos 登录失败，HTTP " + response.statusCode());
            }
            String token = findText(parse(response.body()), "accessToken");
            if (!StringUtils.hasText(token)) {
                throw new PromptAdminException("Nacos 登录响应缺少 accessToken: " + abbreviate(response.body()));
            }
            return token;
        } catch (IOException | InterruptedException exception) {
            restoreInterrupt(exception);
            throw new PromptAdminException("Nacos 登录失败", exception);
        }
    }

    private Map<String, String> baseParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("namespaceId", text(properties.getNamespace()));
        return params;
    }

    private String endpoint(String path) {
        return baseUrl() + normalizePath(path);
    }

    private String baseUrl() {
        String serverAddr = text(properties.getServerAddr());
        if (!serverAddr.startsWith("http://") && !serverAddr.startsWith("https://")) {
            serverAddr = "http://" + serverAddr;
        }
        while (serverAddr.endsWith("/")) {
            serverAddr = serverAddr.substring(0, serverAddr.length() - 1);
        }
        String contextPath = normalizePath(properties.getContextPath());
        if ("/".equals(contextPath)) {
            return serverAddr;
        }
        return serverAddr + contextPath;
    }

    private String normalizePath(String value) {
        String result = text(value);
        if (!StringUtils.hasText(result)) {
            return "/";
        }
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String form(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (builder.length() > 0) {
                builder.append('&');
            }
            builder.append(encode(entry.getKey()))
                    .append('=')
                    .append(encode(entry.getValue()));
        }
        return builder.toString();
    }

    private String encode(String value) {
        return URLEncoder.encode(text(value), StandardCharsets.UTF_8);
    }

    private JsonNode parse(String body) throws JsonProcessingException {
        if (!StringUtils.hasText(body)) {
            return objectMapper.nullNode();
        }
        return objectMapper.readTree(body);
    }

    private boolean isNotFound(HttpResponse<String> response) {
        return response.statusCode() == 404 || isNotFoundBody(response.body());
    }

    private boolean isNotFound(JsonNode root) {
        return isNotFoundBody(root == null ? "" : root.toString());
    }

    private boolean isNotFoundBody(String body) {
        String text = text(body).toLowerCase();
        return text.contains("not found") || text.contains("not_found") || text.contains("resource not found");
    }

    private String findText(JsonNode node, String fieldName) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
        JsonNode direct = node.get(fieldName);
        if (direct != null && direct.isValueNode()) {
            return direct.asText("");
        }
        if (node.isObject() || node.isArray()) {
            for (JsonNode child : node) {
                String value = findText(child, fieldName);
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first.trim() : text(second);
    }

    private String sha256(String value) {
        return PromptChecksums.sha256(value);
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String abbreviate(String value) {
        String text = text(value);
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    private void restoreInterrupt(Exception exception) {
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        accessToken = null;
    }

    /**
     * 远端 Prompt 快照。
     *
     * @param key          Prompt Key。
     * @param version      版本。
     * @param template     模板内容。
     * @param md5          Nacos 发布指纹。
     * @param templateHash 模板 SHA-256。
     */
    public record RemotePromptSnapshot(String key, String version, String template, String md5, String templateHash) {
    }

    public static class PromptAdminException extends RuntimeException {

        public PromptAdminException(String message) {
            super(message);
        }

        public PromptAdminException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
