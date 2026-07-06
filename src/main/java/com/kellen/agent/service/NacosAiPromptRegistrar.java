package com.kellen.agent.service;

import com.kellen.agent.config.PromptSyncProperties;
import com.kellen.agent.service.NacosAiPromptAdminClient.RemotePromptSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nacos AI Prompt 启动同步器。
 */
@Service
public class NacosAiPromptRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NacosAiPromptRegistrar.class);

    private final PromptSyncProperties properties;
    private final PromptDefinitionLoader loader;
    private final NacosAiPromptAdminClient adminClient;
    private final AtomicBoolean executed = new AtomicBoolean(false);

    public NacosAiPromptRegistrar(PromptSyncProperties properties,
                                  PromptDefinitionLoader loader,
                                  NacosAiPromptAdminClient adminClient) {
        this.properties = properties;
        this.loader = loader;
        this.adminClient = adminClient;
    }

    /**
     * 容器启动后同步 Prompt 到 Nacos AI Prompt Registry。
     *
     * @param event Spring 上下文刷新事件。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        if (executed.compareAndSet(false, true)) {
            sync();
        }
    }

    /**
     * 执行一次同步。
     */
    public void sync() {
        if (!properties.isEnabled()) {
            log.info("Nacos AI Prompt 自动同步未启用，跳过。");
            return;
        }
        if (!adminClient.available()) {
            log.warn("Nacos AI Prompt 自动同步缺少可用注册中心配置，跳过。");
            return;
        }
        List<PromptDefinition> definitions = loader.load();
        if (definitions.isEmpty()) {
            log.warn("Nacos AI Prompt 自动同步未发现本地发布定义，sourcePattern={}", properties.getSourcePattern());
            return;
        }
        int published = 0;
        int skipped = 0;
        int failed = 0;
        for (PromptDefinition definition : definitions) {
            SyncResult result = syncOne(definition);
            switch (result) {
                case PUBLISHED -> published++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
            }
        }
        log.info("Nacos AI Prompt 自动同步完成，published={}, skipped={}, failed={}",
                published, skipped, failed);
    }

    private SyncResult syncOne(PromptDefinition definition) {
        if (!definition.enabled()) {
            audit("skip-disabled", definition, "", "disabled");
            return SyncResult.SKIPPED;
        }
        try {
            Optional<RemotePromptSnapshot> remote = adminClient.version(definition.key(), definition.version());
            if (remote.isEmpty()) {
                if (!properties.isPublishMissing() || !properties.isPublishNewVersion()) {
                    audit("skip-missing", definition, "", "automatic publishing disabled");
                    return SyncResult.SKIPPED;
                }
                publish(definition, "new-version");
                return SyncResult.PUBLISHED;
            }
            RemotePromptSnapshot snapshot = remote.get();
            if (StringUtils.hasText(snapshot.templateHash()) && definition.checksum().equals(snapshot.templateHash())) {
                audit("skip-same-version", definition, snapshot.templateHash(), "remote content is same");
                return SyncResult.SKIPPED;
            }
            audit("skip-version-conflict", definition, snapshot.templateHash(),
                    "same version already exists with different content; bump version or publish manually in Nacos");
            return SyncResult.SKIPPED;
        } catch (RuntimeException exception) {
            audit("failed", definition, "", exception.getMessage());
            if (properties.isFailOnError()) {
                throw exception;
            }
            log.error("同步 Nacos AI Prompt 失败，promptKey={}, version={}, message={}",
                    definition.key(), definition.version(), exception.getMessage());
            return SyncResult.FAILED;
        }
    }

    private void publish(PromptDefinition definition, String reason) {
        if (properties.isDryRun()) {
            audit("dry-run-publish", definition, "", reason);
            return;
        }
        adminClient.createOrUpdateDraft(definition);
        if (properties.isForcePublish()) {
            adminClient.forcePublish(definition, properties.isUpdateLatestLabel());
        } else {
            adminClient.submitDraft(definition);
            adminClient.publishVersion(definition, properties.isUpdateLatestLabel());
        }
        if (properties.isOnlineAfterPublish()) {
            adminClient.online(definition);
        }
        if (properties.isUpdateLabels()) {
            adminClient.updateLabels(definition);
        }
        audit("published", definition, "", reason);
    }

    private void audit(String action, PromptDefinition definition, String remoteChecksum, String reason) {
        log.info("Nacos AI Prompt 同步审计 action={}, promptKey={}, version={}, labels={}, checksum={}, remoteChecksum={}, source={}, reason={}",
                action,
                definition.key(),
                definition.version(),
                definition.labels(),
                definition.checksum(),
                text(remoteChecksum),
                definition.source(),
                text(reason));
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private enum SyncResult {
        PUBLISHED,
        SKIPPED,
        FAILED
    }
}
