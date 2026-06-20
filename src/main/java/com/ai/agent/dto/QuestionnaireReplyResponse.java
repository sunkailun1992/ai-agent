package com.ai.agent.dto;

/**
 * 问卷自然提问响应。
 *
 * @param provider 实际 Provider。
 * @param model    实际模型。
 * @param reply    给患者展示的提问文案。
 */
public record QuestionnaireReplyResponse(String provider, String model, String reply) {
}
