package com.kellen.agent.security;

import com.kellen.agent.config.AgentRegistryProperties;
import com.kellen.agent.config.AgentSecurityProperties;
import com.kellen.agent.dto.AgentChatRequest;
import com.kellen.agent.memory.DubboMemoryAgentClient;
import com.kellen.rpc.auth.AuthSessionIdentityRpcDTO;
import com.kellen.rpc.auth.OpenApiSignatureVerifyRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcService;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** 真实 HTTP 认证边界与真实 Memory adapter；认证中心及 Memory provider使用合成实现。 */
@SpringBootTest(classes=AgentIdentityHttpTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"test.identity.fixture=true","spring.config.name=isolated-agent-identity","spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false","spring.cloud.nacos.config.import-check.enabled=false",
                "ai.agent.registry.public-base-url=https://gateway.example.invalid","ai.agent.security.provider-code=synthetic-agent",
                "ai.agent.security.allowed-client-ids=synthetic-ai"})
class AgentIdentityHttpTest {
    @LocalServerPort int port;
    @Autowired Probe probe;
    final HttpClient http=HttpClient.newHttpClient();
    @BeforeEach void reset() { probe.calls.set(0); probe.tenant=null; probe.sourceTenant=null; }
    HttpResponse<String> send(String path,String token,String user,String tenant) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").header("X-Open-Client-Id","synthetic-ai")
                .header("X-Open-Timestamp","1234567890").header("X-Open-Nonce","synthetic-nonce")
                .header("X-Open-Signature","0".repeat(64));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"synthetic\",\"userId\":\""+user+"\",\"tenantId\":\""+tenant+"\",\"sourceTenantId\":\"source-other\",\"patientId\":\"fixture\"}")).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void serviceSignatureWithoutTerminalIdentityCannotCallScopedMemory() throws Exception {
        assertThat(send("/api/agents/patient-analysis/stream",null,"user-a","tenant-a").statusCode()).isEqualTo(401);
        assertThat(send("/api/agents/patient-analysis/stream/",null,"user-a","tenant-a").statusCode()).isEqualTo(401);
        assertThat(probe.calls).hasValue(0);
    }
    @Test void independentlyVerifiedIdentityMustMatchTheSignedBody() throws Exception {
        assertThat(send("/api/agents/patient-analysis/stream","synthetic-a","user-a","tenant-b").statusCode()).isEqualTo(403);
        assertThat(send("/api/agents/patient-analysis/stream","synthetic-a","user-b","tenant-a").statusCode()).isEqualTo(403);
        assertThat(send("/api/agents/patient-analysis/stream","invalid","user-a","tenant-a").statusCode()).isEqualTo(401);
        assertThat(probe.calls).hasValue(0);
    }
    @Test void duplicateAuthorizationAndSpoofedIdentityHeadersCannotEstablishAuthority() throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/agents/patient-analysis/stream"))
                .header("Content-Type","application/json").header("X-Open-Client-Id","synthetic-ai")
                .header("X-Open-Timestamp","1234567890").header("X-Open-Nonce","synthetic-nonce")
                .header("X-Open-Signature","0".repeat(64))
                .header("Authorization","Bearer synthetic-a").header("Authorization","Bearer synthetic-b");
        assertThat(http.send(request.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        var spoof=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/agents/patient-analysis/stream"))
                .header("Content-Type","application/json").header("X-Open-Client-Id","synthetic-ai")
                .header("X-Open-Timestamp","1234567890").header("X-Open-Nonce","synthetic-nonce")
                .header("X-Open-Signature","0".repeat(64)).header("X-Tenant-Id","tenant-a").header("X-User-Id","user-a");
        assertThat(http.send(spoof.POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(probe.calls).hasValue(0);
    }
    @Test void sourcePartitionDoesNotOverrideTheVerifiedRpcTenantAndRequestsStayIsolated() throws Exception {
        assertThat(send("/api/agents/patient-analysis/stream","synthetic-a","user-a","tenant-a").statusCode()).isEqualTo(200);
        assertThat(probe.tenant).isEqualTo("tenant-a"); assertThat(probe.sourceTenant).isEqualTo("source-other");
        assertThat(send("/api/agents/patient-analysis/stream","synthetic-b","user-b","tenant-b").statusCode()).isEqualTo(200);
        assertThat(probe.tenant).isEqualTo("tenant-b"); assertThat(probe.calls).hasValue(2);
    }
    @RestController static class Probe {
        final DubboMemoryAgentClient memory;
        final AtomicInteger calls=new AtomicInteger(); volatile String tenant,sourceTenant;
        Probe() {
            memory=new DubboMemoryAgentClient();
            ReflectionTestUtils.setField(memory,"retrievalRpcService",(MemoryRetrievalRpcService)request->{
                calls.incrementAndGet();tenant=RpcContext.getClientAttachment().getAttachment("tenantId");
                sourceTenant=request.getSourceTenantId();return new MemoryRetrievalRpcDTO();
            });
        }
        @PostMapping("/api/agents/patient-analysis/stream") String execute(@RequestBody AgentChatRequest body) {
            var identity=AgentRequestIdentity.current(body);
            var request=new MemoryRetrievalRpcRequest();request.setTenantId(body.tenantId());request.setUserId(body.userId());
            request.setPatientId(body.patientId());request.setSourceTenantId(body.sourceTenantId());
            memory.retrieve(request,identity);return "verified";
        }
    }
    @Configuration @ConditionalOnProperty(name="test.identity.fixture",havingValue="true") @EnableConfigurationProperties({AgentRegistryProperties.class,AgentSecurityProperties.class})
    @ImportAutoConfiguration({TomcatServletWebServerAutoConfiguration.class,DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,HttpMessageConvertersAutoConfiguration.class})
    static class Config {
        @Bean Probe probe(){return new Probe();}
        @Bean AgentSignatureVerifier signature(){return new AgentSignatureVerifier(){@Override public void verify(OpenApiSignatureVerifyRpcRequest request){}};}
        @Bean AgentSessionVerifier session(){return new AgentSessionVerifier(token->{
            if(!token.equals("synthetic-a")&&!token.equals("synthetic-b"))throw new SecurityException("synthetic rejected");
            String suffix=token.endsWith("a")?"a":"b";var result=new AuthSessionIdentityRpcDTO();
            result.setUserId("user-"+suffix);result.setTenantId("tenant-"+suffix);return result;
        });}
        @Bean AgentServiceAuthenticationFilter filter(AgentRegistryProperties registry,AgentSecurityProperties security,
                ObjectProvider<AgentSignatureVerifier> signatures,ObjectProvider<AgentSessionVerifier> sessions){
            return new AgentServiceAuthenticationFilter(registry,security,signatures,sessions);
        }
    }
}
