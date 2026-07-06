package com.kellen.agent.dto;

import java.util.List;

/**
 * 患者分析 Agent 调用请求。
 *
 * @param conversationId 会话 ID，供日志和调用链追踪使用。
 * @param provider       可选模型供应商覆盖。
 * @param systemPrompt   主服务解析后的角色 Prompt；为空时使用 Prompt Registry。
 * @param message        用户本轮输入。
 * @param instruction    本轮补充要求。
 * @param historyContext 主服务整理后的历史上下文。
 * @param ragContext     主服务或后续 RAG 工具整理的检索上下文。
 * @param attachments    可选远程媒体附件。
 * @param caller         调用方标识。
 * @param traceId        调用链追踪 ID。
 * @param userId         操作用户 ID。
 * @param tenantId       当前业务租户 ID。
 * @param sourceTenantId 来源业务租户 ID。
 * @param patientId      患者 ID。
 */
public record AgentChatRequest(
        String conversationId,
        String provider,
        String systemPrompt,
        String message,
        String instruction,
        String historyContext,
        String ragContext,
        List<MediaInput> attachments,
        String caller,
        String traceId,
        String userId,
        String tenantId,
        String sourceTenantId,
        String patientId
) {
    public List<MediaInput> effectiveAttachments() {
        return attachments == null ? List.of() : attachments;
    }
}
