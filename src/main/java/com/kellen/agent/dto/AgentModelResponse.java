package com.kellen.agent.dto;

import java.util.List;

/**
 * 模型信息响应。
 *
 * @param provider           当前 Provider。
 * @param model              当前模型。
 * @param supportedProviders 支持的 Provider 列表。
 */
public record AgentModelResponse(String provider, String model, List<String> supportedProviders) {
}
