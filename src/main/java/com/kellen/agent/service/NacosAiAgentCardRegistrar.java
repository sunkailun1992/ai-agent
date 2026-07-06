package com.kellen.agent.service;

import com.kellen.agent.config.AgentRegistryProperties;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.a2a.AgentCapabilities;
import com.alibaba.nacos.api.ai.model.a2a.AgentCard;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.alibaba.nacos.api.ai.model.a2a.AgentProvider;
import com.alibaba.nacos.api.ai.model.a2a.AgentSkill;
import com.alibaba.nacos.api.exception.NacosException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * Nacos AI A2A 注册中心自动注册器。
 *
 * <p>ai-agent 启动后通过 Nacos 官方 {@link AiService}（{@code A2aService}）SDK 自动发布
 * 患者分析、问卷两张 AgentCard（{@code releaseAgentCard}），把卡的可调用接口地址写成
 * 配置的对外地址；进程关闭时释放 SDK 客户端。这样消费方主 {@code ai} 服务读取 AgentCard
 * 即可拿到可达地址，全流程代码驱动，不再依赖在控制台手工维护 AgentCard。</p>
 *
 * <p>接口地址来自 {@code ai.agent.registry.advertise-host}：容器部署建议显式配置为
 * {@code ai} 可达的稳定地址（如 docker 服务名 {@code ai-agent}），卡内容稳定、无需刷新；
 * 留空时自动探测本机站点本地 IP。卡内容（地址、技能等）变更后需递增
 * {@code ai.agent.registry.card-version}，因为同版本重复发布不会覆盖既有卡。</p>
 *
 * <p>遵循 fail-soft 原则：注册失败只记录日志、不阻断 ai-agent 启动。</p>
 */
@Service
public class NacosAiAgentCardRegistrar {

    private static final Logger log = LoggerFactory.getLogger(NacosAiAgentCardRegistrar.class);

    private static final String TRANSPORT_HTTP_JSON = "HTTP+JSON";

    private final AgentRegistryProperties properties;
    private final int serverPort;
    private final List<AgentDefinition> definitions;

    private volatile AiService aiService;

    public NacosAiAgentCardRegistrar(AgentRegistryProperties properties,
                                     @Value("${server.port:7310}") int serverPort) {
        this.properties = properties;
        this.serverPort = serverPort;
        this.definitions = buildDefinitions();
    }

    /**
     * 容器就绪后触发自动注册。
     *
     * @param event 上下文刷新事件，仅取触发时机。
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onContextRefreshed(ContextRefreshedEvent event) {
        register();
    }

    /**
     * 发布全部 AgentCard。
     *
     * <p>由容器刷新事件触发，也可供集成测试直接调用以验证注册往返。</p>
     */
    public void register() {
        if (!properties.enabled() || !StringUtils.hasText(properties.serverAddr())) {
            log.info("Nacos AI A2A 自动注册未启用，跳过 AgentCard 注册。");
            return;
        }
        if (aiService != null) {
            return;
        }
        String host = resolveAdvertiseHost();
        int port = properties.advertisePort() > 0 ? properties.advertisePort() : serverPort;
        try {
            AiService service = AiFactory.createAiService(nacosProperties());
            for (AgentDefinition definition : definitions) {
                service.releaseAgentCard(
                        buildCard(definition, host, port),
                        AiConstants.A2a.A2A_ENDPOINT_TYPE_URL,
                        true
                );
                log.info("已发布 AgentCard: agent={}, url={}", definition.agentName(),
                        interfaceUrl(host, port, definition.paths().get(0)));
            }
            this.aiService = service;
        } catch (NacosException | RuntimeException exception) {
            log.error("Nacos AI A2A 自动注册失败，AgentCard 未发布，message={}", exception.getMessage());
        }
    }

    /**
     * 进程关闭时释放 SDK 客户端。
     */
    @PreDestroy
    public void shutdown() {
        AiService service = aiService;
        if (service == null) {
            return;
        }
        try {
            service.shutdown();
        } catch (NacosException exception) {
            log.debug("关闭 Nacos AI A2A 客户端失败，message={}", exception.getMessage());
        }
    }

    private AgentCard buildCard(AgentDefinition definition, String host, int port) {
        AgentCard card = new AgentCard();
        card.setName(definition.agentName());
        card.setDescription(definition.description());
        card.setVersion(properties.cardVersion());
        card.setProtocolVersion(properties.protocolVersion());

        AgentCapabilities capabilities = new AgentCapabilities();
        capabilities.setStreaming(definition.streaming());
        capabilities.setPushNotifications(false);
        capabilities.setStateTransitionHistory(false);
        card.setCapabilities(capabilities);

        card.setSkills(definition.skills());

        List<AgentInterface> interfaces = new ArrayList<>();
        for (String path : definition.paths()) {
            AgentInterface agentInterface = new AgentInterface();
            agentInterface.setUrl(interfaceUrl(host, port, path));
            agentInterface.setTransport(TRANSPORT_HTTP_JSON);
            agentInterface.setProtocolBinding(TRANSPORT_HTTP_JSON);
            agentInterface.setProtocolVersion(properties.protocolVersion());
            if (StringUtils.hasText(properties.tenant())) {
                agentInterface.setTenant(properties.tenant().trim());
            }
            interfaces.add(agentInterface);
        }
        card.setSupportedInterfaces(interfaces);
        card.setUrl(interfaces.get(0).getUrl());
        card.setPreferredTransport(TRANSPORT_HTTP_JSON);

        AgentProvider provider = new AgentProvider();
        provider.setOrganization(properties.providerOrg());
        card.setProvider(provider);
        return card;
    }

    private String interfaceUrl(String host, int port, String path) {
        String scheme = properties.supportTls() ? "https" : "http";
        return scheme + "://" + host + ":" + port + path;
    }

    private String resolveAdvertiseHost() {
        if (StringUtils.hasText(properties.advertiseHost())) {
            return properties.advertiseHost().trim();
        }
        String siteLocal = firstSiteLocalIpv4();
        if (siteLocal != null) {
            return siteLocal;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException exception) {
            log.warn("自动探测本机 IP 失败，回退 127.0.0.1，建议显式配置 ai.agent.registry.advertise-host。");
            return "127.0.0.1";
        }
    }

    private String firstSiteLocalIpv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress() && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException exception) {
            log.debug("枚举网卡失败，回退 InetAddress.getLocalHost，message={}", exception.getMessage());
        }
        return null;
    }

    private Properties nacosProperties() {
        Properties nacosProperties = new Properties();
        nacosProperties.setProperty(PropertyKeyConst.SERVER_ADDR, normalizeServerAddr(properties.serverAddr()));
        if (StringUtils.hasText(properties.namespace())) {
            nacosProperties.setProperty(PropertyKeyConst.NAMESPACE, properties.namespace().trim());
        }
        if (StringUtils.hasText(properties.username())) {
            nacosProperties.setProperty(PropertyKeyConst.USERNAME, properties.username().trim());
        }
        if (StringUtils.hasText(properties.password())) {
            nacosProperties.setProperty(PropertyKeyConst.PASSWORD, properties.password());
        }
        if (StringUtils.hasText(properties.contextPath())) {
            nacosProperties.setProperty(PropertyKeyConst.CONTEXT_PATH, normalizeContextPath(properties.contextPath()));
        }
        return nacosProperties;
    }

    private String normalizeServerAddr(String value) {
        String serverAddr = text(value);
        if (!StringUtils.hasText(serverAddr)) {
            return serverAddr;
        }
        if (serverAddr.startsWith("http://") || serverAddr.startsWith("https://")) {
            URI uri = URI.create(serverAddr);
            int port = uri.getPort();
            return port > 0 ? uri.getHost() + ":" + port : uri.getHost();
        }
        return trimTrailingSlash(serverAddr);
    }

    private String normalizeContextPath(String value) {
        String contextPath = text(value);
        while (contextPath.startsWith("/")) {
            contextPath = contextPath.substring(1);
        }
        return trimTrailingSlash(contextPath);
    }

    private String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/") && result.length() > 1) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private List<AgentDefinition> buildDefinitions() {
        AgentSkill patientAnalysis = skill("patient-analysis", "患者分析",
                "理解患者症状、病情描述和既往资料，整理风险提示和就医沟通建议。",
                List.of("medical", "patient", "chat"),
                List.of("我最近胸闷，帮我分析一下可能需要关注什么", "帮我整理一下去医院要问医生的问题"));
        AgentSkill reportAnalysis = skill("report-analysis", "报告解读",
                "解读检查报告、体检报告、图片或 PDF 资料，整理关注点和就医建议。",
                List.of("medical", "report", "multimodal"),
                List.of("帮我看看这份体检报告", "这张化验单有哪些指标需要注意"));
        AgentDefinition patient = new AgentDefinition(
                "patient-analysis-agent",
                "面向患者和家属的报告解读、病情资料整理、就医沟通助手。",
                true,
                List.of(patientAnalysis, reportAnalysis),
                List.of("/api/agents/patient-analysis/stream"));

        AgentSkill conversational = skill("conversational-questionnaire", "对话式问卷",
                "按后端问卷模板进行自然语言问答采集。",
                List.of("questionnaire", "form", "medical"),
                List.of("开始症状初筛问卷", "继续刚才没有完成的问卷"));
        AgentSkill interpretation = skill("answer-interpretation", "答案解释",
                "把患者自然语言回答解析为结构化候选答案，并在需要时给出追问建议。",
                List.of("questionnaire", "nlp", "medical"),
                List.of("把我的回答整理成标准选项"));
        AgentDefinition questionnaire = new AgentDefinition(
                "questionnaire-agent",
                "对话式问卷采集 Agent，负责自然提问、答案解释、未完成问卷恢复和结构化保存。",
                false,
                List.of(conversational, interpretation),
                List.of("/api/agents/questionnaire/reply", "/api/agents/questionnaire/interpret"));

        return List.of(patient, questionnaire);
    }

    private AgentSkill skill(String id, String name, String description,
                             List<String> tags, List<String> examples) {
        AgentSkill skill = new AgentSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setDescription(description);
        skill.setTags(tags);
        skill.setExamples(examples);
        return skill;
    }

    private record AgentDefinition(
            String agentName,
            String description,
            boolean streaming,
            List<AgentSkill> skills,
            List<String> paths
    ) {
    }
}
