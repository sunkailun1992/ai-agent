package com.kellen.agent.dto;

/**
 * 已采集答案摘要。
 *
 * @param questionId        题目 ID。
 * @param selectedText      标准化答案。
 * @param rawText           用户原话。
 * @param attachmentSummary 附件摘要。
 */
public record QuestionnaireAnswerPayload(
        String questionId,
        String selectedText,
        String rawText,
        String attachmentSummary
) {
}
