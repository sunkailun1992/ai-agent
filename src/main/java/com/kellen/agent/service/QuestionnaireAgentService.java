package com.kellen.agent.service;

import com.kellen.agent.dto.QuestionDefinitionPayload;
import com.kellen.agent.dto.QuestionnaireAnswerPayload;
import com.kellen.agent.dto.QuestionnaireDefinitionPayload;
import com.kellen.agent.dto.QuestionnaireInterpretRequest;
import com.kellen.agent.dto.QuestionnaireInterpretResponse;
import com.kellen.agent.dto.QuestionnaireReplyRequest;
import com.kellen.agent.dto.QuestionnaireReplyResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 问卷 Agent 执行服务。
 *
 * <p>只负责自然提问生成和答案解释；问卷状态推进、保存、恢复和 outbox
 * 仍由主 AI 服务的状态机控制。</p>
 */
@Service
public class QuestionnaireAgentService {

    private static final Logger log = LoggerFactory.getLogger(QuestionnaireAgentService.class);

    private final AiChatClientProvider aiChatClientProvider;
    private final AiPromptProvider promptProvider;
    private final AiModelCallObserver modelCallObserver;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QuestionnaireAgentService(AiChatClientProvider aiChatClientProvider,
                                     AiPromptProvider promptProvider,
                                     AiModelCallObserver modelCallObserver) {
        this.aiChatClientProvider = aiChatClientProvider;
        this.promptProvider = promptProvider;
        this.modelCallObserver = modelCallObserver;
    }

    /**
     * 生成当前题自然提问。
     *
     * @param request 请求上下文。
     * @return 自然提问响应。
     */
    public QuestionnaireReplyResponse reply(QuestionnaireReplyRequest request) {
        AiChatClientProvider.CurrentClient currentClient = aiChatClientProvider.selection(request.provider());
        try {
            String content = modelCallObserver.observeCall(currentClient.provider(), currentClient.model(), "questionnaire_reply",
                    () -> currentClient.chatClient()
                    .prompt()
                    .system(promptProvider.prompt(AiPromptProvider.QUESTIONNAIRE_REPLY_SYSTEM))
                    .user(replyUserPrompt(request))
                    .call()
                    .content());
            String reply = StringUtils.hasText(content) ? content.trim() : fallbackReply(request.currentQuestion(), request.resumed());
            return new QuestionnaireReplyResponse(currentClient.provider(), currentClient.model(), reply);
        } catch (RuntimeException exception) {
            log.warn("问卷自然提问 Agent 调用失败，已回退题面");
            return new QuestionnaireReplyResponse(currentClient.provider(), currentClient.model(),
                    fallbackReply(request.currentQuestion(), request.resumed()));
        }
    }

    /**
     * 解释当前题回答。
     *
     * @param request 请求上下文。
     * @return 结构化候选答案。
     */
    public QuestionnaireInterpretResponse interpret(QuestionnaireInterpretRequest request) {
        AiChatClientProvider.CurrentClient currentClient = aiChatClientProvider.selection(request.provider());
        try {
            InterpretedCandidate candidate = modelCallObserver.observeCall(currentClient.provider(), currentClient.model(), "questionnaire_interpret",
                    () -> currentClient.chatClient()
                    .prompt()
                    .system(promptProvider.prompt(AiPromptProvider.QUESTIONNAIRE_INTERPRETER_SYSTEM))
                    .user(interpretUserPrompt(request))
                    .call()
                    .entity(InterpretedCandidate.class));
            return normalize(currentClient, candidate, request);
        } catch (RuntimeException exception) {
            log.warn("问卷答案解释 Agent 调用失败，已返回追问");
            return followUp(currentClient, request, retryPrompt(request.currentQuestion()));
        }
    }

    private String replyUserPrompt(QuestionnaireReplyRequest request) {
        return """
                请生成当前题的自然提问。

                场景:
                %s

                当前题:
                %s

                已采集答案:
                %s

                是否恢复未完成问卷: %s

                要求:
                - 只问当前题，不要顺便问后面的题。
                - 如果是恢复未完成问卷，开头自然说明“我们继续刚才的问卷”。
                - 如果已有答案，可以用一句话承接，但不要复述太多。
                - 最多两句话。
                """.formatted(
                safeJson(definitionMap(request.definition())),
                safeJson(questionMap(request.currentQuestion())),
                safeJson(answers(request.effectivePreviousAnswers())),
                request.resumed()
        );
    }

    private String interpretUserPrompt(QuestionnaireInterpretRequest request) {
        return """
                请解释当前题的患者回答。

                问卷信息:
                %s

                当前题:
                %s

                已采集答案:
                %s

                用户本轮输入:
                %s

                当前题附件摘要:
                %s

                字段语义:
                - selectedCode：可选，若模板没有选项则为空字符串。
                - selectedText：当前题可保存的标准化答案文本。
                - value：保留关键细节的答案值。
                - confidence：0.0 到 1.0 的数字。
                - needFollowUp：当前回答是否不足以保存当前题。
                - followUpQuestion：needFollowUp=true 时给患者的一句追问。
                """.formatted(
                safeJson(definitionMap(request.definition())),
                safeJson(questionMap(request.currentQuestion())),
                safeJson(answers(request.effectivePreviousAnswers())),
                text(request.rawMessage()),
                text(request.attachmentSummary())
        );
    }

    private QuestionnaireInterpretResponse normalize(AiChatClientProvider.CurrentClient currentClient,
                                                     InterpretedCandidate candidate,
                                                     QuestionnaireInterpretRequest request) {
        if (candidate == null) {
            return followUp(currentClient, request, retryPrompt(request.currentQuestion()));
        }
        String selectedText = firstText(candidate.selectedText(), candidate.value());
        String value = firstText(candidate.value(), candidate.selectedText());
        boolean hasAnswer = StringUtils.hasText(selectedText) || StringUtils.hasText(value) || StringUtils.hasText(request.attachmentSummary());
        boolean needFollowUp = Boolean.TRUE.equals(candidate.needFollowUp());
        String followUpQuestion = text(candidate.followUpQuestion());
        if (needFollowUp && !StringUtils.hasText(followUpQuestion)) {
            followUpQuestion = "我还需要再确认一下这个问题，可以再具体说一点吗？";
        }
        String rawText = text(request.rawMessage());
        return new QuestionnaireInterpretResponse(
                currentClient.provider(),
                currentClient.model(),
                rawText,
                text(candidate.selectedCode()),
                StringUtils.hasText(selectedText) ? selectedText : rawText,
                StringUtils.hasText(value) ? value : rawText,
                clamp(candidate.confidence(), hasAnswer ? 0.85 : 0.4),
                needFollowUp,
                followUpQuestion
        );
    }

    private QuestionnaireInterpretResponse followUp(AiChatClientProvider.CurrentClient currentClient,
                                                    QuestionnaireInterpretRequest request,
                                                    String followUpQuestion) {
        String rawText = text(request.rawMessage());
        return new QuestionnaireInterpretResponse(
                currentClient.provider(),
                currentClient.model(),
                rawText,
                "",
                rawText,
                rawText,
                0.4,
                true,
                followUpQuestion
        );
    }

    private String fallbackReply(QuestionDefinitionPayload currentQuestion, boolean resumed) {
        if (resumed) {
            return "我们继续刚才的问卷。" + text(currentQuestion.prompt());
        }
        return text(currentQuestion.prompt());
    }

    private String retryPrompt(QuestionDefinitionPayload currentQuestion) {
        return "我还没有准确确认这个问题。" + text(currentQuestion.prompt());
    }

    private Map<String, Object> definitionMap(QuestionnaireDefinitionPayload definition) {
        return Map.of(
                "questionnaireCode", text(definition.questionnaireCode()),
                "version", text(definition.version()),
                "name", text(definition.name()),
                "scene", text(definition.scene())
        );
    }

    private Map<String, Object> questionMap(QuestionDefinitionPayload question) {
        return Map.of(
                "id", text(question.id()),
                "label", text(question.label()),
                "prompt", text(question.prompt()),
                "required", question.required(),
                "allowsAttachments", question.allowsAttachments()
        );
    }

    private List<Map<String, String>> answers(List<QuestionnaireAnswerPayload> previousAnswers) {
        return previousAnswers.stream()
                .map(answer -> Map.of(
                        "questionId", text(answer.questionId()),
                        "selectedText", text(answer.selectedText()),
                        "rawText", text(answer.rawText()),
                        "attachmentSummary", text(answer.attachmentSummary())
                ))
                .toList();
    }

    private String firstText(String first, String second) {
        String firstValue = text(first);
        return StringUtils.hasText(firstValue) ? firstValue : text(second);
    }

    private double clamp(Double value, double fallback) {
        if (value == null) {
            return fallback;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String safeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize questionnaire agent prompt", exception);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Spring AI 结构化输出候选答案。
     */
    public record InterpretedCandidate(
            String selectedCode,
            String selectedText,
            String value,
            Double confidence,
            Boolean needFollowUp,
            String followUpQuestion
    ) {
    }
}
