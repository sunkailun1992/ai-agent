package com.kellen.agent;

import com.kellen.agent.config.AgentProperties;
import com.kellen.agent.config.AgentRegistryProperties;
import com.kellen.agent.config.NacosAiPromptProperties;
import com.kellen.agent.config.PromptSyncProperties;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI Agent 执行层启动类。
 *
 * <p>本服务只承载模型调用、Prompt 组织、结构化输出等 Agent 执行逻辑；
 * 用户会话、审计、文件 token 和问卷状态仍由主 AI 服务管理。</p>
 */
@SpringBootApplication
@EnableDubbo
@ConfigurationPropertiesScan(basePackageClasses = {
        AgentProperties.class,
        AgentRegistryProperties.class,
        NacosAiPromptProperties.class,
        PromptSyncProperties.class
})
public class AiAgentApplication {

    /**
     * JVM 进程入口。
     *
     * @param args 命令行参数。
     */
    public static void main(String[] args) {
        SpringApplication.run(AiAgentApplication.class, args);
    }
}
