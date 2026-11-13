package com.kellen.agent.security;

import com.kellen.agent.config.AgentRegistryProperties;
import com.kellen.observability.fleet.TrafficRouteObservation;
import com.kellen.traffic.CanaryContext;
import com.kellen.traffic.CanaryGrant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;

/** 在 Agent 的业务认证调用 User 之前校验灰度授权。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
@EnableConfigurationProperties(AgentCanaryFilter.Properties.class)
public class AgentCanaryFilter extends OncePerRequestFilter {

    private static final String PATIENT_ANALYSIS_PATH = "/api/agents/patient-analysis/stream";

    private final Properties properties;
    private final AgentRegistryProperties registry;

    public AgentCanaryFilter(Properties properties, AgentRegistryProperties registry) {
        this.properties = properties;
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(isAgentPath(request.getRequestURI()) || isAgentPath(request.getServletPath()));
    }

    private static boolean isAgentPath(String path) {
        return path != null && (path.equals("/api/agents") || path.startsWith("/api/agents/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var tokens = Collections.list(request.getHeaders(CanaryGrant.HEADER));
        CanaryContext.Snapshot previous = CanaryContext.get();
        CanaryContext.clear();
        try {
            if (tokens.isEmpty()) {
                if (properties.requireGrant()) {
                    reject(response);
                } else {
                    chain.doFilter(request, response);
                }
                return;
            }
            String token = tokens.get(0);
            CanaryGrant grant = properties.enabled() && tokens.size() == 1
                    ? CanaryGrant.verify(token, properties.signingKey(), Instant.now()) : null;
            if (grant == null || registry.publicBaseUrl().isBlank()
                    || !"POST".equals(request.getMethod()) || !PATIENT_ANALYSIS_PATH.equals(request.getRequestURI())
                    || !grant.service().equals(properties.service()) || !grant.tag().equals(properties.httpTag())) {
                reject(response);
                return;
            }
            CanaryContext.set(new CanaryContext.Snapshot(grant, token));
            TrafficRouteObservation.record("http_provider", "canary", "selected");
            chain.doFilter(request, response);
        } finally {
            CanaryContext.set(previous);
        }
    }

    private static void reject(HttpServletResponse response) {
        TrafficRouteObservation.record("http_provider", "canary", "rejected");
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @ConfigurationProperties("traffic.governance.canary")
    public record Properties(boolean enabled, boolean requireGrant, String signingKey, String service, String httpTag) {
    }
}
