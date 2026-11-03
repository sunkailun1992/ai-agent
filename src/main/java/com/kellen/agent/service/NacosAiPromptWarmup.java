package com.kellen.agent.service;

import com.kellen.agent.config.NacosAiPromptProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动后把本制品发布的 Prompt 预读一遍：订阅提前建好，读不到的上线时就写进日志。
 *
 * <p>预读放在后台线程，提前发现不可读取的 Prompt，
 * Nacos 慢或不可达时不拖慢启动；结果只进日志，读取本身仍由 {@link AiPromptRegistryClient} 负责，
 * 读不到时它自己会退避重试。</p>
 */
@Component
public class NacosAiPromptWarmup {

    private static final Logger log = LoggerFactory.getLogger(NacosAiPromptWarmup.class);

    private final NacosAiPromptProperties properties;
    private final PromptDefinitionLoader loader;
    private final AiPromptRegistryClient registryClient;

    public NacosAiPromptWarmup(NacosAiPromptProperties properties, PromptDefinitionLoader loader,
                               AiPromptRegistryClient registryClient) {
        this.properties = properties;
        this.loader = loader;
        this.registryClient = registryClient;
    }

    /**
     * 应用就绪后在后台预读；此时启动同步（{@link NacosAiPromptRegistrar}）已经跑完。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled()) {
            return;
        }
        Thread worker = new Thread(this::warmUp, "nacos-ai-prompt-warmup");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 逐个读取启用的 Prompt。
     *
     * @return 读不到的 Prompt Key
     */
    List<String> warmUp() {
        if (!properties.isEnabled()) {
            return List.of();
        }
        List<String> keys;
        try {
            keys = loader.load().stream()
                    .filter(PromptDefinition::enabled)
                    .map(PromptDefinition::key)
                    .distinct()
                    .toList();
        } catch (RuntimeException exception) {
            log.warn("Nacos AI Prompt 启动预读跳过：本地 Prompt 定义读取失败，type={}", exception.getClass().getSimpleName());
            return List.of();
        }
        List<String> unreadable = new ArrayList<>();
        for (String key : keys) {
            boolean readable;
            try {
                readable = registryClient.prompt(key).isPresent();
            } catch (RuntimeException exception) {
                readable = false;
            }
            if (!readable) {
                unreadable.add(key);
            }
        }
        if (unreadable.isEmpty()) {
            log.info("Nacos AI Prompt 启动预读完成，{} 个全部可读", keys.size());
        } else {
            log.warn("Nacos AI Prompt 启动预读有 {}/{} 个读不到，用到它们的 Agent 会返回 PROMPT_UNAVAILABLE，promptKeys={}",
                    unreadable.size(), keys.size(), unreadable);
        }
        return unreadable;
    }
}
