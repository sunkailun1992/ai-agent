package com.kellen.agent;

import com.kellen.agent.config.AgentProperties;
import com.kellen.agent.config.AgentRegistryProperties;
import com.kellen.agent.config.NacosAiPromptProperties;
import com.kellen.agent.config.PromptSyncProperties;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * AI Agent 执行层启动类。
 *
 * <p>本服务只承载模型调用、Prompt 组织、结构化输出等 Agent 执行逻辑；用户会话、审计和文件 token 仍由主 AI 服务管理。
 * 第5阶段起与其他服务一样作为 fleet 成员完整接入 utils：根包 {@code com.kellen} 组件扫描，复用 Keycloak 资源服务器与业务上下文、
 * Actuator 运维令牌、动态数据源（本服务库只有 Seata AT 的 undo_log）、统一响应与异常等共享能力；数据源由 utils 动态数据源接管，
 * 因此排除 Spring 默认数据源自动配置。</p>
 */
@SpringBootApplication(scanBasePackages = "com.kellen", exclude = {DataSourceAutoConfiguration.class})
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
