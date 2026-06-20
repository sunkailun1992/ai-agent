package com.ai.agent.service;

import com.ai.agent.dto.AgentChatRequest;
import com.ai.agent.dto.MediaInput;
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
 * 会话记忆裁剪、RAG 检索和审计落库。</p>
 */
@Service
public class PatientAnalysisAgentService {

    private static final Logger log = LoggerFactory.getLogger(PatientAnalysisAgentService.class);

    private static final String FALLBACK_SYSTEM_PROMPT = """
            你是一个严谨、克制、面向患者和家属的患者分析助手。
            你可以帮助用户理解报告、病情描述和既往资料，并整理便于和医生沟通的问题。
            你不能替代医生诊断、治疗、用药或急救判断；遇到急危信号必须建议立即就医或联系急救。
            """;

    private final AiChatClientProvider aiChatClientProvider;
    private final AiPromptProvider promptProvider;
    private final AiModelCallObserver modelCallObserver;

    public PatientAnalysisAgentService(AiChatClientProvider aiChatClientProvider,
                                       AiPromptProvider promptProvider,
                                       AiModelCallObserver modelCallObserver) {
        this.aiChatClientProvider = aiChatClientProvider;
        this.promptProvider = promptProvider;
        this.modelCallObserver = modelCallObserver;
    }

    /**
     * 流式执行患者分析。
     *
     * @param request Agent 请求。
     * @return 模型输出文本流。
     */
    public Flux<String> stream(AgentChatRequest request) {
        return Flux.defer(() -> {
            String message = requiredText(request.message(), "message");
            AiChatClientProvider.CurrentClient currentClient = aiChatClientProvider.selection(request.provider());
            long startedAt = modelCallObserver.start();
            ChatClient.ChatClientRequestSpec spec = currentClient.chatClient()
                    .prompt()
                    .system(systemPrompt(request.systemPrompt()))
                    .user(user -> {
                        user.text(userText(request, message));
                        media(request.effectiveAttachments()).forEach(item -> user.media(item.mimeType(), item.resource()));
                    });
            return spec.stream()
                    .content()
                    .doOnComplete(() -> modelCallObserver.success(currentClient.provider(), currentClient.model(), "patient_analysis", startedAt))
                    .doOnError(exception -> {
                        modelCallObserver.failure(currentClient.provider(), currentClient.model(), "patient_analysis", startedAt);
                        log.warn("患者分析 Agent 调用失败: {}", exception.getMessage());
                    })
                    .doOnCancel(() -> modelCallObserver.cancelled(currentClient.provider(), currentClient.model(), "patient_analysis", startedAt))
                    .onErrorResume(exception -> Flux.just("\n[AI Agent 调用失败] " + safeMessage(exception)));
        });
    }

    private String systemPrompt(String requestedPrompt) {
        if (StringUtils.hasText(requestedPrompt)) {
            return requestedPrompt.trim();
        }
        return promptProvider.prompt(AiPromptProvider.PATIENT_ANALYSIS_SYSTEM, FALLBACK_SYSTEM_PROMPT);
    }

    private String userText(AgentChatRequest request, String message) {
        StringBuilder builder = new StringBuilder(message);
        appendBlock(builder, "历史上下文", request.historyContext());
        appendBlock(builder, "知识库检索上下文", request.ragContext());
        appendBlock(builder, "本轮补充要求", request.instruction());
        return builder.toString();
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

    private String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return StringUtils.hasText(message) ? message : throwable.getClass().getSimpleName();
    }

    private record AgentMedia(MimeType mimeType, UrlResource resource) {
    }
}
