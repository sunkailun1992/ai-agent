package com.kellen.agent.security;

import com.kellen.rpc.auth.AuthSessionRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Agent 独立验证终端身份，不把服务签名或正文 tenant 当作用户授权。 */
@Component
@Conditional(AgentGatewayModeCondition.class)
public class AgentSessionVerifier {
    // 本机灰度通道内也校验访问令牌：显式参与灰度，携带入站授权只选同标签 user；稳定请求仍走稳定 user。
    @DubboReference(init = false, check = false, lazy = true, timeout = 3000, retries = 0,
            parameters = {"traffic.canary.enabled", "true"})
    private AuthSessionRpcService sessions;
    public AgentSessionVerifier() { }
    public AgentSessionVerifier(AuthSessionRpcService sessions) { this.sessions = sessions; }
    public AgentRequestIdentity verify(String token) {
        var identity = sessions.resolveAccessIdentity(token);
        if (identity == null) throw new SecurityException("AGENT_IDENTITY_REJECTED");
        return new AgentRequestIdentity(identity.getUserId(), identity.getTenantId());
    }
}
