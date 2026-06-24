package com.ai.agent.service;

import com.kellen.agent.config.AgentRegistryProperties;
import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiFactory;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.ai.model.a2a.AgentInterface;
import com.kellen.agent.service.NacosAiAgentCardRegistrar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentCard 自动注册集成验证。
 *
 * <p>需显式设置环境变量 {@code NACOS_A2A_IT=true} 才会运行，默认 {@code ./gradlew test}
 * 不会触发，也不会连接线上 Nacos。可用 {@code NACOS_A2A_ADVERTISE} 指定一个固定地址，
 * 验证注册器把 AgentCard 接口地址写成该地址（模拟实例 IP 变化后的刷新）。
 * Nacos 凭据和地址全部从环境变量读取，不在测试中硬编码。</p>
 */
@EnabledIfEnvironmentVariable(named = "NACOS_A2A_IT", matches = "true")
class NacosAiAgentCardRegistrarIT {

    private static String env(String key, String def) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? def : value;
    }

    private AgentRegistryProperties properties() {
        return new AgentRegistryProperties(
                true,
                env("NACOS_SERVER_ADDR", "127.0.0.1:8848"),
                env("NACOS_NAMESPACE", ""),
                env("NACOS_USERNAME", ""),
                env("NACOS_PASSWORD", ""),
                "/nacos",
                env("NACOS_A2A_ADVERTISE", ""),  // 留空自动探测本机 IP；设值则模拟固定/变化的实例地址
                7310,
                false,
                "1.0",
                "1.0.0",
                "orb-ai",
                "Orb AI");
    }

    @Test
    void registersBothAgentCardsWithRealIpEndpoints() throws Exception {
        AgentRegistryProperties props = properties();
        String expectedHost = env("NACOS_A2A_ADVERTISE", "");
        NacosAiAgentCardRegistrar registrar = new NacosAiAgentCardRegistrar(props, 7310);
        AiService reader = AiFactory.createAiService(readerProps(props));
        try {
            registrar.register();

            String patientUrl = firstInterfaceUrl(reader.getAgentCard("patient-analysis-agent"));
            System.out.println("patient-analysis-agent url = " + patientUrl);
            assertHost(patientUrl, "patient-analysis/stream", expectedHost);

            String qUrl = firstInterfaceUrl(reader.getAgentCard("questionnaire-agent"));
            System.out.println("questionnaire-agent url = " + qUrl);
            assertHost(qUrl, "questionnaire/", expectedHost);
        } finally {
            registrar.shutdown();
            reader.shutdown();
        }
    }

    private void assertHost(String url, String pathFragment, String expectedHost) {
        assertNotNull(url, "AgentCard 接口地址为空");
        assertFalse(url.contains("ai-agent"), "接口地址仍是服务名而非真实 IP: " + url);
        assertTrue(url.contains(":7310/api/agents/" + pathFragment), "接口路径不正确: " + url);
        if (!expectedHost.isBlank()) {
            assertTrue(url.contains("://" + expectedHost + ":"),
                    "接口地址未刷新为期望地址 " + expectedHost + "，实际: " + url);
        } else {
            assertTrue(url.matches("http://\\d+\\.\\d+\\.\\d+\\.\\d+:7310/.*"),
                    "接口地址未解析为真实 IP: " + url);
        }
    }

    private String firstInterfaceUrl(AgentCardDetailInfo card) {
        if (card == null) {
            return null;
        }
        if (card.getSupportedInterfaces() != null && !card.getSupportedInterfaces().isEmpty()) {
            AgentInterface first = card.getSupportedInterfaces().get(0);
            if (first != null && first.getUrl() != null) {
                return first.getUrl();
            }
        }
        return card.getUrl();
    }

    private Properties readerProps(AgentRegistryProperties props) {
        Properties p = new Properties();
        p.setProperty(PropertyKeyConst.SERVER_ADDR, props.serverAddr());
        if (!props.namespace().isBlank()) {
            p.setProperty(PropertyKeyConst.NAMESPACE, props.namespace());
        }
        if (!props.username().isBlank()) {
            p.setProperty(PropertyKeyConst.USERNAME, props.username());
        }
        if (!props.password().isBlank()) {
            p.setProperty(PropertyKeyConst.PASSWORD, props.password());
        }
        p.setProperty(PropertyKeyConst.CONTEXT_PATH, "nacos");
        return p;
    }
}
