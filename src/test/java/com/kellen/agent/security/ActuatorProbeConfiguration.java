package com.kellen.agent.security;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试专用的通配端点：与 health 一样用剩余路径选择器映射 {*path}。
 *
 * <p>Tomcat 会规范化点段，Spring MVC 按原始路径逐段匹配；两者对同一请求得出不同路径时，保护范围不能只看其中一种。</p>
 */
@TestConfiguration(proxyBeanMethods = false)
class ActuatorProbeConfiguration {

    @Bean CatchAllProbeEndpoint catchAllProbeEndpoint() {
        return new CatchAllProbeEndpoint();
    }

    @Endpoint(id = "catchallprobe")
    static class CatchAllProbeEndpoint {
        private final AtomicInteger invocations = new AtomicInteger();

        @ReadOperation String read(@Selector(match = Selector.Match.ALL_REMAINING) String[] path) {
            invocations.incrementAndGet();
            return "probe";
        }

        int invocations() {
            return invocations.get();
        }
    }
}
