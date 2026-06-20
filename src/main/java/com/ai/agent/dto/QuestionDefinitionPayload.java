package com.ai.agent.dto;

/**
 * 当前题定义。
 *
 * @param id                题目 ID。
 * @param label             题目标题。
 * @param prompt            题面提示。
 * @param required          是否必填。
 * @param allowsAttachments 是否允许附件。
 */
public record QuestionDefinitionPayload(
        String id,
        String label,
        String prompt,
        boolean required,
        boolean allowsAttachments
) {
}
