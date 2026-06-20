package com.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用上下文加载测试。
 */
@SpringBootTest(properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.ai.google.genai.api-key=test-key",
        "logging.file.path=build/logs",
        "logging.file.name=build/logs/ai-agent-test.log"
})
class AiAgentApplicationTests {

    /**
     * 空测试体：上下文启动成功即通过。
     */
    @Test
    void contextLoads() {
    }
}
