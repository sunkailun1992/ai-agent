package com.kellen.agent.security;

import com.kellen.agent.dto.AgentChatRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 只有成功验签通道及 User JWT 二次验证才能建立的本轮身份。 */
public record AgentRequestIdentity(String userId, String tenantId) {
    static final String ATTRIBUTE = AgentRequestIdentity.class.getName();
    public AgentRequestIdentity {
        if (userId == null || !userId.matches("[A-Za-z0-9_.:@-]{1,128}")
                || tenantId == null || !tenantId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new SecurityException("AGENT_IDENTITY_REJECTED");
        }
    }

    public static AgentRequestIdentity current(AgentChatRequest body) {
        var attributes = RequestContextHolder.getRequestAttributes();
        var identity = attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getAttribute(ATTRIBUTE) : null;
        if (!(identity instanceof AgentRequestIdentity verified)) return null;
        if (!verified.userId.equals(body.userId()) || !verified.tenantId.equals(body.tenantId())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "AGENT_IDENTITY_MISMATCH");
        }
        return verified;
    }
}
