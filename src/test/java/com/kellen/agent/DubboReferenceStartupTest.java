package com.kellen.agent;

import com.kellen.agent.memory.DubboMemoryAgentClient;
import com.kellen.agent.security.AgentRequestIdentity;
import com.kellen.agent.security.AgentSessionVerifier;
import com.kellen.agent.security.AgentSignatureVerifier;
import com.kellen.rpc.auth.AuthOpenApiRpcService;
import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.kellen.rpc.auth.AuthSessionRpcService;
import com.kellen.rpc.auth.OpenApiSignatureVerifyRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcService;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.spring.ServiceBean;
import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.apache.dubbo.config.spring.reference.ReferenceBeanManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DubboReferenceStartupTest {
    @Test
    void startupDefersAllReferencesAndFirstCallsKeepTheirContracts() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("gatewayMode",
                    Map.of("ai.agent.registry.public-base-url", "http://127.0.0.1")));
            context.register(LocalDubboConfiguration.class);
            context.refresh();

            Map<Class<?>, ReferenceConfig<?>> references = context.getBean(ReferenceBeanManager.class)
                    .getReferences().stream()
                    .collect(Collectors.toMap(bean -> bean.getObjectType(), bean -> bean.getReferenceConfig()));
            assertThat(references).containsOnlyKeys(AuthOpenApiRpcService.class, AuthSessionRpcService.class,
                    MemoryRetrievalRpcService.class);
            assertThat(references.entrySet()).allSatisfy(entry ->
                    assertThat(entry.getValue().configInitialized()).as(entry.getKey().getName()).isFalse());
            assertThat(references.get(AuthOpenApiRpcService.class).getParameters())
                    .containsEntry("traffic.canary.enabled", "true");
            assertThat(references.get(MemoryRetrievalRpcService.class).getParameters())
                    .containsEntry("traffic.canary.enabled", "true");
            assertThat(references.get(AuthSessionRpcService.class).getParameters())
                    .doesNotContainKey("traffic.canary.enabled");

            var signatureVerifier = context.getBean(AgentSignatureVerifier.class);
            var signature = new OpenApiSignatureVerifyRpcRequest();
            assertThatThrownBy(() -> signatureVerifier.verify(signature)).isInstanceOf(SecurityException.class);
            signature.setClientId("sample-client");
            signatureVerifier.verify(signature);

            var sessionVerifier = context.getBean(AgentSessionVerifier.class);
            assertThatThrownBy(() -> sessionVerifier.verify("rejected-sample")).isInstanceOf(SecurityException.class);
            AgentRequestIdentity identity = sessionVerifier.verify("accepted-sample");
            assertThat(identity).isEqualTo(new AgentRequestIdentity("sample-user", "sample-tenant"));

            var request = new MemoryRetrievalRpcRequest();
            request.setPatientId("sample-patient");
            request.setTenantId(identity.tenantId());
            request.setSourceTenantId(identity.tenantId());
            request.setUserId(identity.userId());
            var memory = context.getBean(DubboMemoryAgentClient.class);
            assertThat(memory.retrieve(request).isEnabled()).isFalse();
            assertThat(references.get(MemoryRetrievalRpcService.class).configInitialized()).isFalse();
            assertThat(memory.retrieve(request, identity).isEnabled()).isTrue();

            assertThat(references.entrySet()).allSatisfy(entry ->
                    assertThat(entry.getValue().configInitialized()).as(entry.getKey().getName()).isTrue());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableDubbo(scanBasePackages = "com.kellen.agent.dubbotest")
    @Import({AgentSignatureVerifier.class, AgentSessionVerifier.class, DubboMemoryAgentClient.class})
    static class LocalDubboConfiguration {
        @Bean
        ApplicationConfig dubboApplication() {
            var application = new ApplicationConfig("personal-ai-agent-dubbo-startup-test");
            application.setQosEnable(false);
            return application;
        }

        @Bean
        RegistryConfig dubboRegistry() {
            return new RegistryConfig(RegistryConfig.NO_AVAILABLE);
        }

        @Bean
        ProtocolConfig dubboProtocol() {
            return new ProtocolConfig("injvm");
        }

        @Bean
        ServiceBean<AuthOpenApiRpcService> authProvider(ApplicationContext context) {
            return localService(context, AuthOpenApiRpcService.class, new AuthOpenApiRpcService() {
                @Override
                public void verifyOpenApiSignature(OpenApiSignatureVerifyRpcRequest request) {
                    throw new AssertionError("Signature verification must use the required-signature RPC");
                }

                @Override
                public boolean verifyRequiredOpenApiSignature(OpenApiSignatureVerifyRpcRequest request) {
                    return "sample-client".equals(request.getClientId());
                }
            });
        }

        @Bean
        ServiceBean<AuthSessionRpcService> sessionProvider(ApplicationContext context) {
            return localService(context, AuthSessionRpcService.class, token -> {
                if (!"accepted-sample".equals(token)) {
                    return null;
                }
                var identity = new AuthSessionIdentityRpcDTO();
                identity.setUserId("sample-user");
                identity.setTenantId("sample-tenant");
                return identity;
            });
        }

        @Bean
        ServiceBean<MemoryRetrievalRpcService> memoryProvider(ApplicationContext context) {
            return localService(context, MemoryRetrievalRpcService.class, request -> {
                var result = new MemoryRetrievalRpcDTO();
                result.setEnabled("sample-patient".equals(request.getPatientId()));
                return result;
            });
        }

        private <T> ServiceBean<T> localService(ApplicationContext context, Class<T> service, T provider) {
            var serviceBean = new ServiceBean<T>(context);
            serviceBean.setInterface(service);
            serviceBean.setScope("local");
            serviceBean.setRef(provider);
            return serviceBean;
        }
    }
}
