package com.ai.agent.dto;

import java.util.List;

/**
 * 问卷答案解释请求。
 *
 * @param provider          可选模型供应商覆盖。
 * @param definition        问卷定义。
 * @param currentQuestion   当前题。
 * @param previousAnswers   已采集答案。
 * @param rawMessage        用户原始输入。
 * @param attachmentSummary 附件摘要。
 */
public record QuestionnaireInterpretRequest(
        String provider,
        QuestionnaireDefinitionPayload definition,
        QuestionDefinitionPayload currentQuestion,
        List<QuestionnaireAnswerPayload> previousAnswers,
        String rawMessage,
        String attachmentSummary
) {
    public List<QuestionnaireAnswerPayload> effectivePreviousAnswers() {
        return previousAnswers == null ? List.of() : previousAnswers;
    }
}
