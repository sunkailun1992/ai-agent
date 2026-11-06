package com.kellen.agent.service;

import com.kellen.agent.dto.AgentChatRequest;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import com.kellen.agent.dto.MediaInput;
import com.kellen.agent.memory.MemoryAgentClient;
import com.kellen.rpc.memory.MemoryRetrievalRpcDTO;
import com.kellen.rpc.memory.MemoryRetrievalRpcRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;

/**
 * 患者分析 Agent。
 *
 * <p>该 Agent 只负责模型执行。调用方需要提前完成用户鉴权、文件授权、
 * 展示历史和审计落库；患者记忆由本服务通过 ai-memory RPC 召回。</p>
 */
@Service
public class PatientAnalysisAgentService {

    private static final Logger log = LoggerFactory.getLogger(PatientAnalysisAgentService.class);

    private final AiChatClientProvider aiChatClientProvider;
    private final AiPromptProvider promptProvider;
    private final AiModelCallObserver modelCallObserver;
    private final MemoryAgentClient memoryAgentClient;

    public PatientAnalysisAgentService(AiChatClientProvider aiChatClientProvider,
                                       AiPromptProvider promptProvider,
                                       AiModelCallObserver modelCallObserver,
                                       MemoryAgentClient memoryAgentClient) {
        this.aiChatClientProvider = aiChatClientProvider;
        this.promptProvider = promptProvider;
        this.modelCallObserver = modelCallObserver;
        this.memoryAgentClient = memoryAgentClient;
    }

    /**
     * 流式执行患者分析。
     *
     * @param request Agent 请求。
     * @return 模型输出文本流。
     */
    public Flux<String> stream(AgentChatRequest request) {
        Context parent = Context.current();
        return Flux.defer(() -> {
            // 订阅线程可能已属于另一请求，observer 必须在原入口 Context 下装配后立即恢复。
            try (Scope ignored = parent.makeCurrent()) {
                String message = requiredText(request.message(), "message");
                AiChatClientProvider.CurrentClient currentClient = aiChatClientProvider.selection(request.provider());
                return modelCallObserver.observeStream(currentClient.provider(), currentClient.model(), "patient_analysis", () -> {
                    ChatClient.ChatClientRequestSpec spec = currentClient.chatClient()
                            .prompt()
                            .system(systemPrompt(request.systemPrompt()))
                            .user(user -> {
                                user.text(userText(request, message));
                                media(request.effectiveAttachments()).forEach(item -> user.media(item.mimeType(), item.resource()));
                            });
                    return spec.stream().content();
                }).doOnError(exception -> log.warn("患者分析 Agent 调用失败，已返回固定提示"))
                        .onErrorResume(exception -> Flux.just("\n[AI Agent 调用失败] 请稍后重试"));
            }
        });
    }

    private String systemPrompt(String requestedPrompt) {
        if (StringUtils.hasText(requestedPrompt)) {
            return requestedPrompt.trim();
        }
        return promptProvider.prompt(AiPromptProvider.PATIENT_ANALYSIS_SYSTEM);
    }

    private String userText(AgentChatRequest request, String message) {
        StringBuilder builder = new StringBuilder(message);
        MemoryRetrievalRpcDTO memory = retrieveMemory(request, message);
        appendBlock(builder, "患者记忆上下文", memory.getContext());
        appendBlock(builder, "历史上下文", request.historyContext());
        appendBlock(builder, "知识库检索上下文", request.ragContext());
        appendBlock(builder, "本轮补充要求", request.instruction());
        return builder.toString();
    }

    private MemoryRetrievalRpcDTO retrieveMemory(AgentChatRequest request, String message) {
        MemoryRetrievalRpcRequest memoryRequest = new MemoryRetrievalRpcRequest();
        memoryRequest.setCaller(StringUtils.hasText(request.caller()) ? request.caller().trim() : "ai-agent");
        memoryRequest.setPurpose("patient_analysis");
        memoryRequest.setTraceId(request.traceId());
        memoryRequest.setUserId(request.userId());
        memoryRequest.setTenantId(request.tenantId());
        memoryRequest.setSourceTenantId(request.sourceTenantId());
        memoryRequest.setPatientId(request.patientId());
        memoryRequest.setConversationId(request.conversationId());
        memoryRequest.setQuery(message);
        memoryRequest.setMaxItems(8);
        memoryRequest.setMaxTokens(1000);
        memoryRequest.setIncludeArchived(false);
        memoryRequest.setAllowSensitive(false);
        return memoryAgentClient.retrieve(memoryRequest);
    }

    private void appendBlock(StringBuilder builder, String title, String content) {
        if (StringUtils.hasText(content)) {
            builder.append("\n\n[").append(title).append("]\n").append(content.trim());
        }
    }

    private List<AgentMedia> media(List<MediaInput> inputs) {
        return inputs.stream()
                .filter(input -> StringUtils.hasText(input.url()) && StringUtils.hasText(input.mimeType()))
                .map(this::toMedia)
                .toList();
    }

    private AgentMedia toMedia(MediaInput input) {
        try {
            URI uri = URI.create(input.url().trim());
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException("unsupported media url scheme: " + uri.getScheme());
            }
            MimeType mimeType = MimeTypeUtils.parseMimeType(input.mimeType().trim());
            return new AgentMedia(mimeType, new UrlResource(uri.toURL()));
        } catch (MalformedURLException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid media input: " + safeName(input), exception);
        }
    }

    private String safeName(MediaInput input) {
        return StringUtils.hasText(input.originalName()) ? input.originalName().trim() : input.url();
    }

    private String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record AgentMedia(MimeType mimeType, UrlResource resource) {
    }
}
