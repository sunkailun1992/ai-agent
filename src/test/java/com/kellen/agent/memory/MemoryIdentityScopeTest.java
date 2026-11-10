package com.kellen.agent.memory;

import com.kellen.agent.security.AgentRequestIdentity;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcService;
import org.apache.dubbo.rpc.RpcContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class MemoryIdentityScopeTest {
    @Test void everyRpcBindsVerifiedTenantOnWorkerAndRestoresPriorAttachmentIncludingFailure() throws Exception {
        var worker=Executors.newSingleThreadExecutor();
        try {
            for(String tenant:java.util.List.of("tenant-a","tenant-b"))for(boolean failure:java.util.List.of(false,true)) {
                worker.submit(()->{
                    var client=new DubboMemoryAgentClient();
                    ReflectionTestUtils.setField(client,"retrievalRpcService",(MemoryRetrievalRpcService)request->{
                        assertThat(RpcContext.getClientAttachment().getAttachment("tenantId")).isEqualTo(tenant);
                        if(failure)throw new IllegalStateException("synthetic failure");
                        return new MemoryRetrievalRpcDTO();
                    });
                    RpcContext.getClientAttachment().setAttachment("tenantId","prior-worker");
                    var request=request(tenant);
                    client.retrieve(request,new AgentRequestIdentity("user-a",tenant));
                    assertThat(RpcContext.getClientAttachment().getAttachment("tenantId")).isEqualTo("prior-worker");
                    RpcContext.getClientAttachment().removeAttachment("tenantId");
                    client.retrieve(request,new AgentRequestIdentity("user-a",tenant));
                    assertThat(RpcContext.getClientAttachment().getAttachment("tenantId")).isNull();
                }).get();
            }
        } finally {worker.shutdownNow();}
    }
    @Test void bodyScopeAloneOrMismatchedIdentityNeverCallsProvider() {
        var client=new DubboMemoryAgentClient();var calls=new AtomicInteger();
        ReflectionTestUtils.setField(client,"retrievalRpcService",(MemoryRetrievalRpcService)request->{calls.incrementAndGet();return new MemoryRetrievalRpcDTO();});
        client.retrieve(request("tenant-a"));
        client.retrieve(request("tenant-a"),new AgentRequestIdentity("user-a","tenant-b"));
        client.retrieve(request("tenant-a"),new AgentRequestIdentity("other-user","tenant-a"));
        assertThat(calls).hasValue(0);
    }
    private MemoryRetrievalRpcRequest request(String tenant) {
        var request=new MemoryRetrievalRpcRequest();request.setTenantId(tenant);request.setUserId("user-a");
        request.setPatientId("synthetic-patient");request.setSourceTenantId("different-source");return request;
    }
}
