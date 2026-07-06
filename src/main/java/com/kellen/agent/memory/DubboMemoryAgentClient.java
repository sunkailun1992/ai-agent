package com.kellen.agent.memory;

import com.kellen.rpc.memory.MemoryCandidateRpcRequest;
import com.kellen.rpc.memory.MemoryCandidateRpcService;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcService;
import com.kellen.rpc.memory.MemoryWriteRpcDTO;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Dubbo 版 ai-memory 客户端。
 */
@Component
@ConditionalOnProperty(name = "spring.cloud.nacos.discovery.enabled", havingValue = "true", matchIfMissing = true)
public class DubboMemoryAgentClient implements MemoryAgentClient {

    private static final Logger log = LoggerFactory.getLogger(DubboMemoryAgentClient.class);

    @DubboReference(check = false, timeout = 3000)
    private MemoryRetrievalRpcService retrievalRpcService;

    @DubboReference(check = false, timeout = 3000)
    private MemoryCandidateRpcService candidateRpcService;

    @Override
    public MemoryRetrievalRpcDTO retrieve(MemoryRetrievalRpcRequest request) {
        MemoryRetrievalRpcDTO fallback = empty("memory disabled or missing scope");
        if (request == null || !StringUtils.hasText(request.getPatientId()) || !StringUtils.hasText(request.getSourceTenantId())) {
            return fallback;
        }
        try {
            return retrievalRpcService.retrieve(request);
        } catch (RuntimeException exception) {
            log.warn("ai-memory 召回失败，已跳过记忆上下文: {}", safeMessage(exception));
            return empty("memory retrieval failed");
        }
    }

    @Override
    public MemoryWriteRpcDTO submitCandidate(MemoryCandidateRpcRequest request) {
        MemoryWriteRpcDTO fallback = skipped("memory disabled or missing scope");
        if (request == null || !StringUtils.hasText(request.getPatientId()) || !StringUtils.hasText(request.getSourceTenantId())) {
            return fallback;
        }
        try {
            return candidateRpcService.submitCandidate(request);
        } catch (RuntimeException exception) {
            log.warn("ai-memory 候选写入失败，已跳过: {}", safeMessage(exception));
            return skipped("memory write failed");
        }
    }

    static MemoryRetrievalRpcDTO empty(String message) {
        MemoryRetrievalRpcDTO dto = new MemoryRetrievalRpcDTO();
        dto.setEnabled(false);
        dto.setMatched(false);
        dto.setContext("");
        dto.setSourceCount(0);
        dto.setMessage(message);
        return dto;
    }

    static MemoryWriteRpcDTO skipped(String message) {
        MemoryWriteRpcDTO dto = new MemoryWriteRpcDTO();
        dto.setSuccess(false);
        dto.setStatus("SKIPPED");
        dto.setMessage(message);
        return dto;
    }

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return StringUtils.hasText(message) ? message : throwable.getClass().getSimpleName();
    }
}
