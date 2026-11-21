package com.kellen.agent.security;

import com.ai.agent.TestAiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 令牌写成引用但被引用的键不存在：与其他服务一样由 utils 的 Actuator 运维令牌链在启动时解析，解析不了服务就起不来（失败即关闭），
 * 不会带着照字面的占位符对外开放管理端点。
 */
class ActuatorUnresolvedTokenHttpTest {

    @Test
    void unresolvablePlaceholderFailsClosed() {
        SpringApplicationBuilder application = new SpringApplicationBuilder(TestAiAgentApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties("spring.cloud.nacos.config.enabled=false",
                        "spring.cloud.nacos.discovery.enabled=false",
                        "spring.ai.google.genai.api-key=test-key",
                        "logging.file.path=build/logs",
                        "logging.file.name=build/logs/ai-agent-actuator-test.log",
                        "server.address=127.0.0.1",
                        "server.port=0",
                        "management.endpoints.web.exposure.include=*",
                        "security.actuator.token=${missing.actuator.token.source}");
        assertThatThrownBy(() -> application.run().close()).hasStackTraceContaining("missing.actuator.token.source");
    }
}
