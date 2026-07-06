package com.kellen.agent.memory;

import com.kellen.rpc.memory.MemoryCandidateRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryWriteRpcDTO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 无注册中心或测试环境下的 ai-memory 降级客户端。
 */
@Component
@ConditionalOnProperty(name = "spring.cloud.nacos.discovery.enabled", havingValue = "false")
public class NoopMemoryAgentClient implements MemoryAgentClient {

    @Override
    public MemoryRetrievalRpcDTO retrieve(MemoryRetrievalRpcRequest request) {
        return DubboMemoryAgentClient.empty("memory client disabled");
    }

    @Override
    public MemoryWriteRpcDTO submitCandidate(MemoryCandidateRpcRequest request) {
        return DubboMemoryAgentClient.skipped("memory client disabled");
    }
}
