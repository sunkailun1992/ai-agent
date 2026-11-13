package com.kellen.agent.memory;

import com.kellen.traffic.CanaryContext;

import com.kellen.rpc.memory.MemoryCandidateRpcRequest;
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

    @DubboReference(init = false, check = false, timeout = 3000, parameters = {"traffic.canary.enabled", "true"})
    private MemoryRetrievalRpcService retrievalRpcService;

    @Override
    public MemoryRetrievalRpcDTO retrieve(MemoryRetrievalRpcRequest request) {
        return empty("memory disabled or missing trusted identity");
    }

    @Override
    public MemoryRetrievalRpcDTO retrieve(MemoryRetrievalRpcRequest request,
                                          com.kellen.agent.security.AgentRequestIdentity identity) {
        MemoryRetrievalRpcDTO fallback = empty("memory disabled or missing scope");
        if (request == null || identity == null || !identity.tenantId().equals(request.getTenantId())
                || !identity.userId().equals(request.getUserId())
                || !StringUtils.hasText(request.getPatientId()) || !StringUtils.hasText(request.getSourceTenantId())) {
            return fallback;
        }
        var attachments = org.apache.dubbo.rpc.RpcContext.getClientAttachment();
        Object previous = attachments.getObjectAttachment("tenantId");
        try {
            // 在实际 RPC 执行线程绑定，不能假定 Servlet ThreadLocal 能跨 Flux 调度器透传。
            attachments.setAttachment("tenantId", identity.tenantId());
            return retrievalRpcService.retrieve(request);
        } catch (RuntimeException exception) {
            if (CanaryContext.get() != null) {
                throw new IllegalStateException("AI memory canary retrieval unavailable", exception);
            }
            log.warn("ai-memory 召回失败，已跳过记忆上下文: {}", safeMessage(exception));
            return empty("memory retrieval failed");
        } finally {
            if (previous == null) attachments.removeAttachment("tenantId");
            else attachments.setObjectAttachment("tenantId", previous);
        }
    }

    @Override
    public MemoryWriteRpcDTO submitCandidate(MemoryCandidateRpcRequest request) {
        // 当前 Agent 只做召回，尚无带可信身份的候选写入入口，不能由 DTO 租户开启写操作。
        return skipped("memory disabled or missing trusted identity");
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
