package com.kellen.agent.security;

import com.kellen.agent.config.AgentRegistryProperties;
import com.kellen.agent.config.AgentSecurityProperties;
import com.kellen.rpc.auth.OpenApiSignatureVerifyRpcRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.dubbo.rpc.RpcException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 网关 Agent 接口的服务间边界。完整 raw HTTP 请求被绑定到 user 既有验签 RPC；
 * 不信任 X-User、正文 caller 或模型输出，不记录正文、签名或其哈希。
 * 只有 public-base-url 非空时启用，保留既有院内部署的内网调用模式。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AgentServiceAuthenticationFilter extends OncePerRequestFilter {
    private final AgentRegistryProperties registry;
    private final AgentSecurityProperties security;
    private final ObjectProvider<AgentSignatureVerifier> verifiers;

    public AgentServiceAuthenticationFilter(AgentRegistryProperties registry, AgentSecurityProperties security,
                                             ObjectProvider<AgentSignatureVerifier> verifiers) {
        this.registry = registry;
        this.security = security;
        this.verifiers = verifiers;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return registry.publicBaseUrl().isEmpty()
                || !(protectedPath(request.getRequestURI()) || protectedPath(request.getServletPath()));
    }

    private static boolean protectedPath(String path) {
        return path != null && (path.equals("/api/agents") || path.startsWith("/api/agents/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String rawPath = request.getRequestURI();
        String rawQuery = request.getQueryString() == null ? "" : request.getQueryString();
        // 拒绝容器可能正规化后再选中 Controller 的非规范路径，避免编码、分号和多斜线绕过。
        if (!rawPath.matches("/api/agents(?:/[A-Za-z0-9_-]+)*/?") || rawQuery.length() > 8192
                || rawQuery.indexOf('\r') >= 0 || rawQuery.indexOf('\n') >= 0 || rawQuery.indexOf('\0') >= 0) {
            reject(response, 400, "AGENT_REQUEST_INVALID");
            return;
        }
        if (!security.providerCode().matches("[A-Za-z0-9_-]{1,128}") || security.allowedClientIds().isEmpty()) {
            reject(response, 503, "AGENT_AUTH_UNAVAILABLE");
            return;
        }
        String clientId = header(request, "X-Open-Client-Id", "[A-Za-z0-9._-]{1,128}");
        String timestamp = header(request, "X-Open-Timestamp", "[0-9]{10,19}");
        String nonce = header(request, "X-Open-Nonce", "[A-Za-z0-9_-]{8,128}");
        String signature = header(request, "X-Open-Signature", "[0-9a-f]{64}");
        if (clientId == null || timestamp == null || nonce == null || signature == null) {
            reject(response, 401, "AGENT_AUTH_REQUIRED");
            return;
        }
        if (!security.allowedClientIds().contains(clientId)) {
            reject(response, 403, "AGENT_CLIENT_FORBIDDEN");
            return;
        }
        if (request.getContentLengthLong() > security.maxBodyBytes()) {
            reject(response, 413, "AGENT_BODY_TOO_LARGE");
            return;
        }
        byte[] body;
        try {
            body = request.getInputStream().readNBytes(security.maxBodyBytes() + 1);
        } catch (IOException exception) {
            reject(response, 400, "AGENT_REQUEST_INVALID");
            return;
        }
        if (body.length > security.maxBodyBytes()) {
            reject(response, 413, "AGENT_BODY_TOO_LARGE");
            return;
        }
        OpenApiSignatureVerifyRpcRequest verification = new OpenApiSignatureVerifyRpcRequest();
        verification.setProviderCode(security.providerCode());
        verification.setClientId(clientId);
        verification.setTimestamp(timestamp);
        verification.setNonce(nonce);
        verification.setSignature(signature);
        verification.setBody(envelope(request.getMethod(), rawPath, rawQuery, body));
        try {
            AgentSignatureVerifier verifier = verifiers.getIfAvailable();
            if (verifier == null) {
                reject(response, 503, "AGENT_AUTH_UNAVAILABLE");
                return;
            }
            verifier.verify(verification);
        } catch (RuntimeException exception) {
            reject(response, authenticationRejection(exception) ? 401 : 503,
                    authenticationRejection(exception) ? "AGENT_AUTH_REJECTED" : "AGENT_AUTH_UNAVAILABLE");
            return;
        }
        chain.doFilter(new ReplayableAgentRequest(request, body), response);
    }

    /** 与主 ai 固定同一协议；query 保留编码、顺序和重复项，body 只按原始字节计算。 */
    static String envelope(String method, String path, String query, byte[] body) {
        try {
            return method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + query + "\n"
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String header(HttpServletRequest request, String name, String pattern) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        return values.hasMoreElements() || value == null || value.length() > 128 || !value.matches(pattern)
                ? null : value;
    }

    private static boolean authenticationRejection(RuntimeException exception) {
        if (exception instanceof SecurityException || exception instanceof IllegalArgumentException) {
            return true;
        }
        if (exception instanceof RpcException rpc && rpc.isBiz()) {
            return true;
        }
        Throwable cause = exception;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            // user 的业务异常不属于 rpc-api，不能为错误分类引入整套 utils 自动装配。
            if ("com.kellen.utils.exception.UserException".equals(cause.getClass().getName())) {
                return true;
            }
            if (cause == cause.getCause()) {
                break;
            }
        }
        return false;
    }

    private static void reject(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"" + code + "\"}");
    }
}
