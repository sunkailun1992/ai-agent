package com.ai.agent;

import com.alibaba.druid.spring.boot4.autoconfigure.DruidDataSourceAutoConfigure;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceEndpointAutoConfiguration;
import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourcePoolMetricsAutoConfiguration;
import com.kellen.agent.config.AgentProperties;
import com.kellen.agent.config.AgentRegistryProperties;
import com.kellen.agent.config.NacosAiPromptProperties;
import com.kellen.agent.config.PromptSyncProperties;
import mybatis.mate.autoconfigure.MpmAutoConfiguration;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.seata.spring.boot.autoconfigure.SeataAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataCoreAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataDataSourceAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataHttpAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataSagaAutoConfiguration;
import org.apache.seata.spring.boot.autoconfigure.SeataSpringFenceAutoConfiguration;
import org.redisson.spring.starter.RedissonAutoConfigurationV2;
import org.redisson.spring.starter.RedissonAutoConfigurationV4;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.PropertySource;

/**
 * AI Agent 测试启动配置。
 *
 * <p>生产启动类完整接入 utils 的动态数据源、Redis、Seata 与 MyBatis-Mate；测试只验证 Agent 的执行与安全边界，
 * 不连数据库与中间件，因此与 ai 服务的测试启动类一样排除 Seata、动态数据源、Flyway、Redisson 与 MyBatis-Mate；
 * 本服务库只有 Seata AT 的 undo_log，测试不建数据源，所以再排除 Spring 默认数据源与 Druid 的自动配置；测试不连 Redis，
 * 排除 Spring Data Redis（业务上下文只用本地缓存，健康检查不因连不上 Redis 变成 DOWN）。资源服务器配置与 Nacos 同形，
 * 见 ai-agent-test-security.properties。
 * 组件扫描与 @SpringBootApplication 一致（根包 com.kellen，utils 的自动配置只按自动配置装配）。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        DruidDataSourceAutoConfigure.class,
        MpmAutoConfiguration.class,
        DynamicDataSourceAutoConfiguration.class,
        DynamicDataSourceEndpointAutoConfiguration.class,
        FlywayAutoConfiguration.class,
        DynamicDataSourcePoolMetricsAutoConfiguration.class,
        SeataAutoConfiguration.class,
        SeataCoreAutoConfiguration.class,
        SeataDataSourceAutoConfiguration.class,
        SeataHttpAutoConfiguration.class,
        SeataSagaAutoConfiguration.class,
        SeataSpringFenceAutoConfiguration.class,
        RedissonAutoConfigurationV2.class,
        RedissonAutoConfigurationV4.class,
        DataRedisAutoConfiguration.class,
        DataRedisReactiveAutoConfiguration.class,
        DataRedisRepositoriesAutoConfiguration.class
})
@ComponentScan(
        basePackages = "com.kellen",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = {
                                "com\\.kellen\\.agent\\.AiAgentApplication",
                                "com\\.kellen\\.config\\.datasource\\..*",
                                "com\\.kellen\\.config\\.redis\\..*"
                        }
                )
        }
)
@EnableDubbo(scanBasePackages = "com.kellen.agent")
@PropertySource("classpath:ai-agent-test-security.properties")
@ConfigurationPropertiesScan(basePackageClasses = {
        AgentProperties.class,
        AgentRegistryProperties.class,
        NacosAiPromptProperties.class,
        PromptSyncProperties.class
})
public class TestAiAgentApplication {
}
