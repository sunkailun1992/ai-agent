package com.kellen.agent.security;

import com.kellen.rpc.auth.AuthSessionRpcService;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/** Agent 独立验证终端身份，不把服务签名或正文 tenant 当作用户授权。 */
@Component
@Conditional(AgentGatewayModeCondition.class)
public class AgentSessionVerifier {
    @DubboReference(init = false, check = false, lazy = true, timeout = 3000, retries = 0)
    private AuthSessionRpcService sessions;
    public AgentSessionVerifier() { }
    public AgentSessionVerifier(AuthSessionRpcService sessions) { this.sessions = sessions; }
    public AgentRequestIdentity verify(String token) {
        var identity = sessions.resolveAccessIdentity(token);
        if (identity == null) throw new SecurityException("AGENT_IDENTITY_REJECTED");
        return new AgentRequestIdentity(identity.getUserId(), identity.getTenantId());
    }
}
