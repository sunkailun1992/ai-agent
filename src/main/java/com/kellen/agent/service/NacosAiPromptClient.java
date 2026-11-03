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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Nacos AI Prompt Registry 官方 SDK 读取客户端。
 *
 * <p>运行期通过 {@link AiService#subscribePrompt(String, String, String, AbstractNacosPromptListener)}
 * 订阅 Prompt。Nacos 控制台人工调整 label 或发布新版本后，SDK 按
 * {@link AiConstants#AI_PROMPT_CACHE_UPDATE_INTERVAL} 刷新并触发监听，本地不再维护
 * 自定义 TTL 缓存。</p>
 *
 * <p>读不到时自愈，不靠重启：SDK 抛错说明客户端可能坏了，退避 {@link #RETRY_INTERVAL_MILLIS}
 * 后关掉重建；订阅一直是空的（SDK 把 NOT_FOUND 缓存成空订阅，监听器未必再来）就到点重查，
 * 重查仍空也按客户端可疑重建。退避期内不再连 Nacos。重建时已读到的 Prompt 照常供应，
 * 下次读取再改订到新客户端上。连接配置变化时，旧环境的订阅不再复用。</p>
 */
@Service
public class NacosAiPromptClient implements AiPromptRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(NacosAiPromptClient.class);
    private static final long MIN_REFRESH_INTERVAL_MILLIS = 1_000L;
    static final long RETRY_INTERVAL_MILLIS = 30_000L;

    private final NacosAiPromptProperties properties;
    private final AiServiceFactory serviceFactory;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, PromptSubscription> subscriptions = new ConcurrentHashMap<>();
    private volatile AiService aiService;
    private volatile String aiServiceKey = "";
    /** 当前客户端可疑：下次连 Nacos 前先关掉重建。 */
    private volatile boolean suspect;
    /** 上次读取抛错后，这个时刻之前不再连 Nacos。 */
    private volatile long retryNotBefore;

    @Autowired
    public NacosAiPromptClient(NacosAiPromptProperties properties) {
        this(properties, AiFactory::createAiService, System::currentTimeMillis);
    }

    NacosAiPromptClient(NacosAiPromptProperties properties, AiServiceFactory serviceFactory, LongSupplier clock) {
        this.properties = properties;
        this.serviceFactory = serviceFactory;
        this.clock = clock;
    }

    @Override
    public Optional<String> prompt(String promptKey) {
        if (!properties.isEnabled() || !StringUtils.hasText(promptKey) || !StringUtils.hasText(properties.getServerAddr())) {
            return Optional.empty();
        }
        // 连接配置也是订阅身份：namespace / 凭据改变时不能命中上一环境的已读正文。
        String key = serviceKey() + "|" + subscriptionKey(promptKey);
        PromptSubscription current = subscriptions.get(key);
        long now = clock.getAsLong();
        if (current != null) {
            Optional<String> template = current.currentTemplate();
            if (template.isPresent() && current.service() == aiService) {
                return template;
            }
            // 空订阅等重查时间；重建前读到的旧订阅在退避期内照常供应，不急着去新客户端上重订。
            if (now < Math.max(current.retryAt(), retryNotBefore)) {
                return template;
            }
        } else if (now < retryNotBefore) {
            return Optional.empty();
        }
        return refresh(promptKey.trim(), key, current);
    }

    /**
     * 新订阅、重查空订阅或把旧客户端上的订阅改订到新客户端；同一时刻只有一个线程连 Nacos。
     */
    private synchronized Optional<String> refresh(String promptKey, String subscriptionKey, PromptSubscription previous) {
        PromptSubscription latest = subscriptions.get(subscriptionKey);
        if (latest != previous) {
            // 别的线程刚处理过这个 key，用它的结果，不重复连 Nacos。
            return latest == null ? Optional.empty() : latest.currentTemplate();
        }
        Optional<String> earlier = previous == null ? Optional.empty() : previous.currentTemplate();
        String version = text(properties.getVersion());
        String label = text(properties.getLabel());
        long now = clock.getAsLong();
        AiService service;
        try {
            service = aiService();
        } catch (NacosException | RuntimeException exception) {
            fail(promptKey, version, label, exception, now);
            return earlier;
        }
        boolean requery = previous != null && previous.service() == service && earlier.isEmpty();
        if (requery) {
            unsubscribeQuietly(previous);
        }
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
            Prompt prompt = service.subscribePrompt(promptKey, blankToNull(version), blankToNull(label), listener);
            if (prompt != null) {
                template.set(text(prompt.getTemplate()));
            }
        } catch (NacosException | RuntimeException exception) {
            fail(promptKey, version, label, exception, now);
            return earlier;
        }
        if (!StringUtils.hasText(template.get())) {
            // 新客户端上还读不到时，先接着用重建前已读到的正文；监听器日后来了会覆盖。
            earlier.ifPresent(template::set);
        }
        PromptSubscription subscription = new PromptSubscription(promptKey, version, label, listener, template,
                service, now + RETRY_INTERVAL_MILLIS);
        subscriptions.put(subscriptionKey, subscription);
        if (subscription.currentTemplate().isEmpty()) {
            log.warn("Nacos AI Prompt 订阅返回空内容（未发布、未上线或标签未指向上线版本），{} 秒后再查，promptKey={}, version={}, label={}",
                    RETRY_INTERVAL_MILLIS / 1000, promptKey, version, label);
            if (requery) {
                suspect = true;
                log.warn("Nacos AI Prompt 重查后仍为空，下次读取时重建 SDK 客户端，promptKey={}", promptKey);
            }
        }
        return subscription.currentTemplate();
    }

    /** SDK 抛错：客户端可能坏了，退避期内不再连 Nacos，期满后重建再试。 */
    private void fail(String promptKey, String version, String label, Exception exception, long now) {
        suspect = true;
        retryNotBefore = now + RETRY_INTERVAL_MILLIS;
        log.warn("订阅 Nacos AI Prompt 失败，{} 秒后重建客户端再试，promptKey={}, version={}, label={}, type={}, errCode={}, message={}",
                RETRY_INTERVAL_MILLIS / 1000, promptKey, version, label, exception.getClass().getSimpleName(),
                exception instanceof NacosException nacos ? nacos.getErrCode() : "-", exception.getMessage());
    }

    /** 只在 {@link #refresh} 的锁内调用。 */
    private AiService aiService() throws NacosException {
        String currentKey = serviceKey();
        if (aiService != null && currentKey.equals(aiServiceKey) && !suspect) {
            return aiService;
        }
        if (currentKey.equals(aiServiceKey)) {
            // 上次重建时工厂本身抛错的话 aiService 是 null，仍算同一环境，已读到的正文照常保留。
            log.warn("重建 Nacos AI Prompt SDK 客户端：上次读取失败或重查仍为空");
        } else {
            // 连接参数变了（首次创建或配置刷新）：旧环境读到的正文不能再用。
            subscriptions.clear();
        }
        shutdownQuietly(aiService);
        aiService = null;
        aiService = serviceFactory.create(nacosProperties());
        aiServiceKey = currentKey;
        suspect = false;
        return aiService;
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
                text(properties.getPassword()),
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
    public synchronized void shutdown() {
        subscriptions.values().stream()
                .filter(subscription -> subscription.service() == aiService)
                .forEach(this::unsubscribeQuietly);
        subscriptions.clear();
        shutdownQuietly(aiService);
    }

    private void unsubscribeQuietly(PromptSubscription subscription) {
        AiService service = subscription == null ? null : subscription.service();
        if (service == null) {
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

    /**
     * @param service 订阅所在的 SDK 客户端；与当前客户端不同说明客户端已重建，下次读取要改订
     * @param retryAt 订阅为空时，这个时刻之后才重查
     */
    private record PromptSubscription(
            String promptKey,
            String version,
            String label,
            AbstractNacosPromptListener listener,
            AtomicReference<String> template,
            AiService service,
            long retryAt
    ) {

        private Optional<String> currentTemplate() {
            String value = template.get();
            return StringUtils.hasText(value) ? Optional.of(value.trim()) : Optional.empty();
        }
    }

    /** 建 SDK 客户端的入口；测试换成假实现，复现坏掉的客户端。 */
    @FunctionalInterface
    interface AiServiceFactory {
        AiService create(Properties properties) throws NacosException;
    }
}
