package com.kellen.agent.security;

import com.kellen.rpc.auth.AuthOpenApiRpcService;
import com.kellen.rpc.auth.OpenApiSignatureVerifyRpcRequest;
import org.apache.dubbo.config.annotation.DubboReference;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Conditional;

/** 调用 user 强制签名 RPC；必须返回明确成功，由认证服务校验密钥、时钟与 nonce 防重放。 */
@Component
@Conditional(AgentGatewayModeCondition.class)
public class AgentSignatureVerifier {
    @DubboReference(check = false, lazy = true, timeout = 3000, retries = 0,
            parameters = {"traffic.canary.enabled", "true"})
    private AuthOpenApiRpcService auth;

    public AgentSignatureVerifier() {
    }

    public AgentSignatureVerifier(AuthOpenApiRpcService auth) {
        this.auth = auth;
    }

    public void verify(OpenApiSignatureVerifyRpcRequest request) {
        if (auth == null) {
            throw new IllegalStateException("Agent authentication unavailable");
        }
        if (!auth.verifyRequiredOpenApiSignature(request)) {
            throw new SecurityException("Agent signature verification was not confirmed");
        }
    }
}
