package com.ai.agent.dto;

/**
 * 问卷答案解释响应。
 *
 * @param provider         实际 Provider。
 * @param model            实际模型。
 * @param rawText          用户原始输入。
 * @param selectedCode     标准选项编码。
 * @param selectedText     标准化答案文本。
 * @param value            保留细节的答案值。
 * @param confidence       置信度。
 * @param needFollowUp     是否需要追问。
 * @param followUpQuestion 追问文案。
 */
public record QuestionnaireInterpretResponse(
        String provider,
        String model,
        String rawText,
        String selectedCode,
        String selectedText,
        String value,
        double confidence,
        boolean needFollowUp,
        String followUpQuestion
) {
}
