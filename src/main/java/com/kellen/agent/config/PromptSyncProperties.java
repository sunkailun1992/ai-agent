package com.kellen.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Nacos AI Prompt 启动同步配置。
 */
@ConfigurationProperties(prefix = "ai.prompts.sync")
public class PromptSyncProperties {

    private boolean enabled = true;
    private String sourcePattern = "classpath*:/prompts/*.prompt.json";
    private String defaultLabel = "stable";
    private boolean dryRun = false;
    private boolean failOnError = false;
    private boolean publishMissing = true;
    private boolean publishNewVersion = true;
    private boolean forcePublish = true;
    private boolean updateLatestLabel = true;
    private boolean onlineAfterPublish = true;
    private boolean updateLabels = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSourcePattern() {
        return sourcePattern;
    }

    public void setSourcePattern(String sourcePattern) {
        this.sourcePattern = sourcePattern;
    }

    public String getDefaultLabel() {
        return defaultLabel;
    }

    public void setDefaultLabel(String defaultLabel) {
        this.defaultLabel = defaultLabel;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public boolean isFailOnError() {
        return failOnError;
    }

    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    public boolean isPublishMissing() {
        return publishMissing;
    }

    public void setPublishMissing(boolean publishMissing) {
        this.publishMissing = publishMissing;
    }

    public boolean isPublishNewVersion() {
        return publishNewVersion;
    }

    public void setPublishNewVersion(boolean publishNewVersion) {
        this.publishNewVersion = publishNewVersion;
    }

    public boolean isForcePublish() {
        return forcePublish;
    }

    public void setForcePublish(boolean forcePublish) {
        this.forcePublish = forcePublish;
    }

    public boolean isUpdateLatestLabel() {
        return updateLatestLabel;
    }

    public void setUpdateLatestLabel(boolean updateLatestLabel) {
        this.updateLatestLabel = updateLatestLabel;
    }

    public boolean isOnlineAfterPublish() {
        return onlineAfterPublish;
    }

    public void setOnlineAfterPublish(boolean onlineAfterPublish) {
        this.onlineAfterPublish = onlineAfterPublish;
    }

    public boolean isUpdateLabels() {
        return updateLabels;
    }

    public void setUpdateLabels(boolean updateLabels) {
        this.updateLabels = updateLabels;
    }
}
