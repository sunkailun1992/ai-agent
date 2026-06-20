package com.ai.agent.dto;

/**
 * 模型信息响应。
 *
 * @param provider 当前 Provider。
 * @param model    当前模型。
 */
public record AgentModelResponse(String provider, String model) {
}
