package com.kellen.agent.service;

import com.kellen.agent.config.NacosAiPromptProperties;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nacos AI Prompt Registry 官方 SDK 读取客户端。
 *
 * <p>本客户端只通过 Nacos 3.x {@link AiService} 读取 Prompt，不再手写
 * HTTP API 路径。Nacos 不可用或 Prompt 未发布时返回空，由上层 Prompt Provider
 * 决定是否使用本地兜底。</p>
 */
@Service
public class NacosAiPromptClient implements AiPromptRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(NacosAiPromptClient.class);

    private final NacosAiPromptProperties properties;
    private final ConcurrentHashMap<String, CachedPrompt> cache = new ConcurrentHashMap<>();
    private volatile AiService aiService;
    private volatile String aiServiceKey = "";

    public NacosAiPromptClient(NacosAiPromptProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<String> prompt(String promptKey) {
        if (!properties.isEnabled() || !StringUtils.hasText(promptKey) || !StringUtils.hasText(properties.getServerAddr())) {
            return Optional.empty();
        }
        String cacheKey = cacheKey(promptKey);
        CachedPrompt cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return Optional.of(cached.template());
        }
        Optional<String> fetched = fetchPrompt(promptKey.trim());
        fetched.ifPresent(template -> cache.put(cacheKey, new CachedPrompt(template, cacheExpireAt())));
        return fetched;
    }

    private Optional<String> fetchPrompt(String promptKey) {
        try {
            Prompt prompt = queryPrompt(promptKey);
            if (prompt == null || !StringUtils.hasText(prompt.getTemplate())) {
                return Optional.empty();
            }
            return Optional.of(prompt.getTemplate().trim());
        } catch (NacosException | RuntimeException exception) {
            log.warn("读取 Nacos AI Prompt 失败，promptKey={}, version={}, label={}, message={}",
                    promptKey, text(properties.getVersion()), text(properties.getLabel()), exception.getMessage());
            return Optional.empty();
        }
    }

    private Prompt queryPrompt(String promptKey) throws NacosException {
        if (StringUtils.hasText(properties.getVersion())) {
            return aiService().getPromptByVersion(promptKey, properties.getVersion().trim());
        }
        if (StringUtils.hasText(properties.getLabel())) {
            return aiService().getPromptByLabel(promptKey, properties.getLabel().trim());
        }
        return aiService().getPrompt(promptKey);
    }

    private AiService aiService() throws NacosException {
        String currentKey = serviceKey();
        AiService current = aiService;
        if (current != null && currentKey.equals(aiServiceKey)) {
            return current;
        }
        synchronized (this) {
            if (aiService != null && currentKey.equals(aiServiceKey)) {
                return aiService;
            }
            shutdownQuietly(aiService);
            aiService = AiFactory.createAiService(nacosProperties());
            aiServiceKey = currentKey;
            cache.clear();
            return aiService;
        }
    }

    private Properties nacosProperties() {
        Properties nacosProperties = new Properties();
        nacosProperties.setProperty(PropertyKeyConst.SERVER_ADDR, normalizeServerAddr(properties.getServerAddr()));
        if (StringUtils.hasText(properties.getNamespace())) {
            nacosProperties.setProperty(PropertyKeyConst.NAMESPACE, properties.getNamespace().trim());
        }
        if (StringUtils.hasText(properties.getUsername())) {
            nacosProperties.setProperty(PropertyKeyConst.USERNAME, properties.getUsername().trim());
        }
        if (StringUtils.hasText(properties.getPassword())) {
            nacosProperties.setProperty(PropertyKeyConst.PASSWORD, properties.getPassword());
        }
        if (StringUtils.hasText(properties.getContextPath())) {
            nacosProperties.setProperty(PropertyKeyConst.CONTEXT_PATH, normalizeContextPath(properties.getContextPath()));
        }
        return nacosProperties;
    }

    private String cacheKey(String promptKey) {
        return String.join("|", List.of(promptKey.trim(), text(properties.getVersion()), text(properties.getLabel())));
    }

    private String serviceKey() {
        return String.join("|", List.of(
                normalizeServerAddr(properties.getServerAddr()),
                text(properties.getNamespace()),
                text(properties.getUsername()),
                text(properties.getContextPath())
        ));
    }

    private Instant cacheExpireAt() {
        return Instant.now().plusSeconds(Math.max(1, properties.getCacheTtlSeconds()));
    }

    private String normalizeServerAddr(String value) {
        String serverAddr = text(value);
        if (!StringUtils.hasText(serverAddr)) {
            return serverAddr;
        }
        if (serverAddr.startsWith("http://") || serverAddr.startsWith("https://")) {
            URI uri = URI.create(serverAddr);
            int port = uri.getPort();
            return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
        }
        return trimTrailingSlash(serverAddr);
    }

    private String normalizeContextPath(String value) {
        String contextPath = text(value);
        while (contextPath.startsWith("/")) {
            contextPath = contextPath.substring(1);
        }
        return trimTrailingSlash(contextPath);
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    @PreDestroy
    public void shutdown() {
        shutdownQuietly(aiService);
    }

    private void shutdownQuietly(AiService service) {
        if (service == null) {
            return;
        }
        try {
            service.shutdown();
        } catch (NacosException exception) {
            log.debug("关闭 Nacos AI Prompt 客户端失败: {}", exception.getMessage());
        }
    }

    private record CachedPrompt(String template, Instant expiresAt) {
    }
}
