package com.kellen.agent.security;

import com.kellen.agent.dto.AgentChatRequest;
import com.kellen.security.SecurityUser;
import com.kellen.security.oauth2.FleetAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * 本轮已验证的身份：资源服务器（utils）校验了 ai 经令牌交换取得的访问令牌（受众 {@code github-ai-agent}、{@code azp=github-ai}），
 * 用户取令牌的 {@code user_id}，活动租户由 user 服务的业务上下文核实（{@code X-Tenant-Id} 只能选用户所属的租户）。
 * 不信任请求正文或任何请求头里的用户、租户。
 *
 * @param userId   业务用户 ID
 * @param tenantId 活动租户 ID
 */
public record AgentRequestIdentity(String userId, String tenantId) {
    public AgentRequestIdentity {
        if (userId == null || !userId.matches("[A-Za-z0-9_.:@-]{1,128}")
                || tenantId == null || !tenantId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new SecurityException("AGENT_IDENTITY_REJECTED");
        }
    }

    /**
     * 当前请求的身份，并核对请求正文里的用户与租户必须与之一致。须在 servlet 请求线程里调用。
     *
     * @param body Agent 请求正文
     * @return 身份；当前线程没有用户身份（非请求线程、机器令牌）时为 null，调用方按无可信身份处理
     * @throws ResponseStatusException 403：正文的用户或租户与已验证身份不一致，或业务上下文的标识不合法
     */
    public static AgentRequestIdentity current(AgentChatRequest body) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof FleetAuthenticationToken token) || !token.isActive() || token.getUser() == null) {
            return null;
        }
        SecurityUser user = token.getUser();
        final AgentRequestIdentity verified;
        try {
            verified = new AgentRequestIdentity(user.getUserId(), user.getTenantId());
        } catch (SecurityException invalid) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AGENT_IDENTITY_REJECTED");
        }
        if (!verified.userId.equals(body.userId()) || !verified.tenantId.equals(body.tenantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AGENT_IDENTITY_MISMATCH");
        }
        return verified;
    }
}
