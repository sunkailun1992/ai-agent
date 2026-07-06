package com.kellen.agent.service;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.listener.AbstractNacosPromptListener;
import com.alibaba.nacos.api.ai.listener.NacosPromptEvent;
import com.alibaba.nacos.api.ai.model.prompt.Prompt;
import com.alibaba.nacos.api.exception.NacosException;
import com.kellen.agent.config.NacosAiPromptProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Nacos AI Prompt Registry 官方 SDK 读取客户端。
 *
 * <p>运行期通过 {@link AiService#subscribePrompt(String, String, String, AbstractNacosPromptListener)}
 * 订阅 Prompt。Nacos 控制台人工调整 label 或发布新版本后，SDK 按
 * {@link AiConstants#AI_PROMPT_CACHE_UPDATE_INTERVAL} 刷新并触发监听，本地不再维护
 * 自定义 TTL 缓存。</p>
 */
@Service
public class NacosAiPromptClient implements AiPromptRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(NacosAiPromptClient.class);
    private static final long MIN_REFRESH_INTERVAL_MILLIS = 1_000L;

    private final NacosAiPromptProperties properties;
    private final ConcurrentHashMap<String, PromptSubscription> subscriptions = new ConcurrentHashMap<>();
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
        String key = subscriptionKey(promptKey);
        PromptSubscription subscription = subscriptions.get(key);
        if (subscription != null) {
            return subscription.currentTemplate();
        }
        return subscribe(promptKey.trim(), key);
    }

    private Optional<String> subscribe(String promptKey, String subscriptionKey) {
        String version = text(properties.getVersion());
        String label = text(properties.getLabel());
        AtomicReference<String> template = new AtomicReference<>("");
        AbstractNacosPromptListener listener = new AbstractNacosPromptListener() {
            @Override
            public void onEvent(NacosPromptEvent event) {
                Prompt prompt = event == null ? null : event.getPrompt();
                String latest = prompt == null ? "" : text(prompt.getTemplate());
                template.set(latest);
                if (StringUtils.hasText(latest)) {
                    log.info("Nacos AI Prompt 已刷新，promptKey={}, version={}, label={}", promptKey, version, label);
                } else {
                    log.warn("Nacos AI Prompt 订阅返回空内容，promptKey={}, version={}, label={}", promptKey, version, label);
                }
            }
        };

        try {
            Prompt prompt = aiService().subscribePrompt(promptKey, blankToNull(version), blankToNull(label), listener);
            if (prompt != null) {
                template.set(text(prompt.getTemplate()));
            }
            PromptSubscription subscription = new PromptSubscription(promptKey, version, label, listener, template);
            PromptSubscription existing = subscriptions.putIfAbsent(subscriptionKey, subscription);
            if (existing != null) {
                unsubscribeQuietly(subscription);
                return existing.currentTemplate();
            }
            return subscription.currentTemplate();
        } catch (NacosException | RuntimeException exception) {
            log.warn("订阅 Nacos AI Prompt 失败，promptKey={}, version={}, label={}, message={}",
                    promptKey, version, label, exception.getMessage());
            return Optional.empty();
        }
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
            subscriptions.clear();
            return aiService;
        }
    }

    private Properties nacosProperties() {
        Properties nacosProperties = new Properties();
        nacosProperties.setProperty(PropertyKeyConst.SERVER_ADDR, normalizeServerAddr(properties.getServerAddr()));
        nacosProperties.setProperty(
                AiConstants.AI_PROMPT_CACHE_UPDATE_INTERVAL,
                Long.toString(Math.max(MIN_REFRESH_INTERVAL_MILLIS, properties.getRefreshIntervalMillis()))
        );
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

    private String subscriptionKey(String promptKey) {
        return String.join("|", List.of(promptKey.trim(), text(properties.getVersion()), text(properties.getLabel())));
    }

    private String serviceKey() {
        return String.join("|", List.of(
                normalizeServerAddr(properties.getServerAddr()),
                text(properties.getNamespace()),
                text(properties.getUsername()),
                text(properties.getContextPath()),
                Long.toString(Math.max(MIN_REFRESH_INTERVAL_MILLIS, properties.getRefreshIntervalMillis()))
        ));
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

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    @PreDestroy
    public void shutdown() {
        subscriptions.values().forEach(this::unsubscribeQuietly);
        subscriptions.clear();
        shutdownQuietly(aiService);
    }

    private void unsubscribeQuietly(PromptSubscription subscription) {
        AiService service = aiService;
        if (service == null || subscription == null) {
            return;
        }
        try {
            service.unsubscribePrompt(
                    subscription.promptKey(),
                    blankToNull(subscription.version()),
                    blankToNull(subscription.label()),
                    subscription.listener()
            );
        } catch (NacosException exception) {
            log.debug("取消订阅 Nacos AI Prompt 失败，promptKey={}, message={}",
                    subscription.promptKey(), exception.getMessage());
        }
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

    private record PromptSubscription(
            String promptKey,
            String version,
            String label,
            AbstractNacosPromptListener listener,
            AtomicReference<String> template
    ) {

        private Optional<String> currentTemplate() {
            String value = template.get();
            return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
        }
    }
}
