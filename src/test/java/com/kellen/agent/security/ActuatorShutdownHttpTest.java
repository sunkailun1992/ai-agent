package com.kellen.agent.security;

import com.kellen.agent.AiAgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.test.annotation.DirtiesContext;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * shutdown 一旦被 Nacos 打开，匿名调用不能让实例下线。
 *
 * <p>保护失效时端点会在约 0.5 秒后真的关闭本测试上下文；这里监听关闭事件并留出余量确认没有发生，用例结束后丢弃上下文。</p>
 */
@SpringBootTest(classes = AiAgentApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.ai.google.genai.api-key=test-key",
                "logging.file.path=build/logs",
                "logging.file.name=build/logs/ai-agent-actuator-test.log",
                "server.address=127.0.0.1",
                "management.endpoints.web.exposure.include=*",
                "management.endpoint.shutdown.access=unrestricted",
                "security.actuator.token=" + ActuatorHttpTestSupport.TOKEN
        })
@DirtiesContext
class ActuatorShutdownHttpTest extends ActuatorHttpTestSupport {
    @LocalServerPort int port;
    @Autowired ConfigurableApplicationContext context;

    @Test void anonymousShutdownIsRejectedAndInstanceKeepsRunning() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        context.addApplicationListener((ApplicationListener<ApplicationEvent>) event -> {
            if (event instanceof ContextClosedEvent) {
                closed.countDown();
            }
        });
        assertThat(send(port, "POST", "/actuator/shutdown", null, null).statusCode()).isEqualTo(401);
        assertThat(closed.await(2, TimeUnit.SECONDS)).isFalse();
        assertThat(context.isActive()).isTrue();
        assertThat(get(port, "/actuator/health", null).statusCode()).isEqualTo(200);
    }
}
