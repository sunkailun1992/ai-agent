package com.ai.agent.dto;

/**
 * 多模态媒体输入。
 *
 * @param url          远程媒体地址。
 * @param mimeType     媒体 MIME。
 * @param fileId       文件 ID。
 * @param fileType     文件类型。
 * @param originalName 原始文件名。
 */
public record MediaInput(
        String url,
        String mimeType,
        Long fileId,
        String fileType,
        String originalName
) {
}
