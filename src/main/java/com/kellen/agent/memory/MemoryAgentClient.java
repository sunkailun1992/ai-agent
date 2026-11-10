package com.kellen.agent.memory;

import com.kellen.rpc.memory.MemoryCandidateRpcRequest;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import com.kellen.rpc.memory.MemoryWriteRpcDTO;

/**
 * Agent 执行层访问 ai-memory 的统一客户端契约。
 */
public interface MemoryAgentClient {

    /**
     * 召回患者记忆。
     *
     * @param request 召回请求
     * @return 召回结果
     */
    MemoryRetrievalRpcDTO retrieve(MemoryRetrievalRpcRequest request);

    /** 经过验证的终端身份单独传入，来源租户只是查询分区。 */
    default MemoryRetrievalRpcDTO retrieve(MemoryRetrievalRpcRequest request,
                                           com.kellen.agent.security.AgentRequestIdentity identity) {
        return retrieve(request);
    }

    /**
     * 提交候选长期记忆。
     *
     * @param request 候选记忆请求
     * @return 写入结果
     */
    MemoryWriteRpcDTO submitCandidate(MemoryCandidateRpcRequest request);
}
