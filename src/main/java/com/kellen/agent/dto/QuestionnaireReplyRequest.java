package com.kellen.agent.dto;

import java.util.List;

/**
 * 问卷自然提问请求。
 *
 * @param provider        可选模型供应商覆盖。
 * @param definition      问卷定义。
 * @param currentQuestion 当前题。
 * @param previousAnswers 已采集答案。
 * @param resumed         是否恢复未完成问卷。
 */
public record QuestionnaireReplyRequest(
        String provider,
        QuestionnaireDefinitionPayload definition,
        QuestionDefinitionPayload currentQuestion,
        List<QuestionnaireAnswerPayload> previousAnswers,
        boolean resumed
) {
    public List<QuestionnaireAnswerPayload> effectivePreviousAnswers() {
        return previousAnswers == null ? List.of() : previousAnswers;
    }
}
