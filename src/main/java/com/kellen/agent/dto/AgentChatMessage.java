package com.kellen.agent.dto;

/**
 * 调用方传入的历史上下文消息。
 *
 * @param role    消息角色，例如 user/assistant/system。
 * @param content 消息内容。
 */
public record AgentChatMessage(String role, String content) {
}
