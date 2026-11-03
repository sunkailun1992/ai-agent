package com.kellen.agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.actuate.endpoint.EndpointId;
import org.springframework.boot.actuate.endpoint.web.PathMappedEndpoints;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletPath;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Actuator 端点的运维令牌边界：health（含探针子路径）公开，其余端点（含目录页与 loggers、refresh、shutdown
 * 等写端点）必须在 actuatorReq 请求头携带 security.actuator.token，否则 401。
 *
 * <p>端点由 Boot 独立的 HandlerMapping 处理，WebMvcConfigurer 拦截器管不到，所以在 Servlet Filter 层拦截；
 * 不引入 Spring Security，避免给业务接口加上全局防火墙和默认账号。保护范围按 Boot 启动时的端点映射计算，
 * 与 Spring Security 的 EndpointRequest 一致：根路径下全部子路径、每个端点路径和目录页，叠加 DispatcherServlet 前缀。
 * 令牌只认请求头，不认网关透传身份，内网直连实例端口同样受限。</p>
 *
 * <p>令牌未配置一律拒绝；每次请求读取当前 Environment，Nacos 刷新轮换令牌无需重启；常量时间比较；日志不记录请求头。
 * 独立管理端口时主端口不提供端点，本实例不拦截，由 {@link ActuatorManagementPortConfiguration} 把管理端口变体挂到子容器。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ActuatorAccessFilter extends OncePerRequestFilter {
    static final String TOKEN_HEADER = "actuatorReq";
    static final String TOKEN_PROPERTY = "security.actuator.token";
    private static final Logger log = LoggerFactory.getLogger(ActuatorAccessFilter.class);
    private static final EndpointId HEALTH = EndpointId.of("health");

    private final Environment environment;
    private final ObjectProvider<PathMappedEndpoints> endpointsProvider;
    private final ObjectProvider<DispatcherServletPath> dispatcherServletPath;
    private final boolean managementServer;
    private final boolean servesEndpoints;
    private final AtomicBoolean managementServerBridged;
    private volatile EndpointArea area;

    @Autowired
    public ActuatorAccessFilter(Environment environment, ObjectProvider<PathMappedEndpoints> endpointsProvider,
                                ObjectProvider<DispatcherServletPath> dispatcherServletPath) {
        this(environment, endpointsProvider, dispatcherServletPath, false,
                ManagementPortType.get(environment) == ManagementPortType.SAME, new AtomicBoolean());
        if (configuredToken() == null) {
            log.warn("security.actuator.token 未配置，除 health 外的 Actuator 端点一律返回 401");
        }
    }

    private ActuatorAccessFilter(Environment environment, ObjectProvider<PathMappedEndpoints> endpointsProvider,
                                 ObjectProvider<DispatcherServletPath> dispatcherServletPath, boolean managementServer,
                                 boolean servesEndpoints, AtomicBoolean managementServerBridged) {
        this.environment = environment;
        this.endpointsProvider = endpointsProvider;
        this.dispatcherServletPath = dispatcherServletPath;
        this.managementServer = managementServer;
        this.servesEndpoints = servesEndpoints;
        this.managementServerBridged = managementServerBridged;
    }

    /** 管理子容器整台服务器都在提供端点，DispatcherServlet 固定挂在 /；共用主上下文的 Environment，令牌轮换同样即时生效。 */
    ActuatorAccessFilter forManagementServer() {
        managementServerBridged.set(true);
        return new ActuatorAccessFilter(environment, endpointsProvider, dispatcherServletPath, true, true,
                managementServerBridged);
    }

    /** 独立管理端口却没有挂上子容器（例如以展开的 classpath 启动，读不到 jar 根目录的 imports 文件）时，管理端口上的端点没有保护。 */
    @EventListener(ApplicationReadyEvent.class)
    public void verifyManagementServerProtected() {
        if (!managementServer && ManagementPortType.get(environment) == ManagementPortType.DIFFERENT
                && !managementServerBridged.get()) {
            log.error("独立管理端口上的 Actuator 未挂上令牌保护：请用 java -jar 启动，确认 classpath 上有 "
                    + "META-INF/spring/org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration.imports");
        }
    }

    /**
     * 同一请求在 Tomcat 与 Spring MVC 眼里可能是两个路径：Tomcat 解码并消去点段，MVC 按原始路径逐段匹配、保留点段。
     * 任一视角落在端点范围内都算端点请求，避免 /actuator/x/../.. 这类写法在 Tomcat 看来已离开端点范围、MVC 却仍匹配到端点。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        EndpointArea endpoints = servesEndpoints ? area() : null;
        if (endpoints == null) {
            return true;
        }
        String path = containerPath(request);
        return !endpoints.contains(path) && (canonical(request, path) || !endpoints.contains(springMvcPath(request)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (publicHealth(request) || tokenMatches(request.getHeader(TOKEN_HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("拒绝未携带有效令牌的 Actuator 请求：{} {}", request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"ACTUATOR_AUTH_REQUIRED\"}");
    }

    /** 只有原样书写的 health 路径公开：编码、分号参数、点段或多斜线写法都按受保护端点处理。 */
    private boolean publicHealth(HttpServletRequest request) {
        EndpointArea endpoints = area();
        String path = containerPath(request);
        return endpoints != null && canonical(request, path) && endpoints.isHealth(path);
    }

    /** Tomcat 解码、去掉分号参数并消去点段后的应用内路径，即 servletPath 加 pathInfo。 */
    private static String containerPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }

    /** 原始 URI 与 Tomcat 规范化后的路径逐字一致时，Tomcat 与 Spring MVC 看到的是同一个路径。 */
    private static boolean canonical(HttpServletRequest request, String containerPath) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return uri.startsWith(contextPath) && uri.substring(contextPath.length()).equals(containerPath);
    }

    /** Spring MVC 匹配时使用的应用内路径：逐段解码、去掉分号参数，但保留点段。 */
    private static String springMvcPath(HttpServletRequest request) {
        StringBuilder path = new StringBuilder();
        for (PathContainer.Element element : RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication().elements()) {
            path.append(element instanceof PathContainer.PathSegment segment ? segment.valueToMatch() : element.value());
        }
        return path.toString();
    }

    private boolean tokenMatches(String providedToken) {
        String expectedToken = configuredToken();
        return expectedToken != null && providedToken != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), providedToken.getBytes(StandardCharsets.UTF_8));
    }

    /** 未配置、空白或占位符无法解析都按未配置处理，失败关闭。 */
    private String configuredToken() {
        try {
            String token = environment.getProperty(TOKEN_PROPERTY);
            return StringUtils.hasText(token) ? token : null;
        } catch (IllegalArgumentException unresolvedPlaceholder) {
            return null;
        }
    }

    /** 首次请求时才计算端点范围，避免过滤器在 Web 容器创建阶段提前初始化全部端点；端点映射启动后固定，算一次即可。 */
    private EndpointArea area() {
        EndpointArea current = area;
        if (current == null) {
            PathMappedEndpoints endpoints = endpointsProvider.getIfAvailable();
            if (endpoints == null) {
                return null;
            }
            DispatcherServletPath servletPath = managementServer ? null : dispatcherServletPath.getIfAvailable();
            current = EndpointArea.of(endpoints, servletPath == null ? "" : servletPath.getPrefix(), managementServer);
            area = current;
        }
        return current;
    }

    /**
     * 端点在本服务器上的应用内路径范围。目录页规则与 EndpointRequest 一致：根路径非空时就是根路径；
     * 根路径为空时只有独立管理端口在 / 提供目录页。
     */
    private record EndpointArea(String basePath, String linksPath, List<String> endpointPaths, String healthPath) {
        static EndpointArea of(PathMappedEndpoints endpoints, String prefix, boolean managementServer) {
            String basePath = endpoints.getBasePath().isEmpty() ? null : prefix + endpoints.getBasePath();
            String linksPath = basePath != null ? basePath : managementServer ? prefix + "/" : null;
            String healthPath = endpoints.getPath(HEALTH);
            return new EndpointArea(basePath, linksPath,
                    endpoints.getAllPaths().stream().map(path -> prefix + path).toList(),
                    healthPath == null ? null : prefix + healthPath);
        }

        /** 根路径非空时目录页和每个端点都在根路径下，业务请求只比一次；根路径为空时逐个端点匹配。 */
        boolean contains(String path) {
            if (basePath != null) {
                return within(path, basePath);
            }
            return path.equals(linksPath) || endpointPaths.stream().anyMatch(endpointPath -> within(path, endpointPath));
        }

        boolean isHealth(String path) {
            return healthPath != null && (healthPath.equals("/") ? path.equals("/") : within(path, healthPath));
        }

        private static boolean within(String path, String prefix) {
            return prefix.equals("/") || path.equals(prefix) || path.startsWith(prefix + "/");
        }
    }
}
