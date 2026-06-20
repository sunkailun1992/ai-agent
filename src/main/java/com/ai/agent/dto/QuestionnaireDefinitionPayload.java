package com.ai.agent.dto;

/**
 * 问卷定义摘要。
 *
 * @param questionnaireCode 问卷编码。
 * @param version           模板版本。
 * @param name              问卷名称。
 * @param scene             业务场景。
 */
public record QuestionnaireDefinitionPayload(
        String questionnaireCode,
        String version,
        String name,
        String scene
) {
}
