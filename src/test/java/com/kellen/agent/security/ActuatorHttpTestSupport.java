package com.kellen.agent.security;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Actuator 访问保护 HTTP 测试共用的合成令牌与请求工具。
 *
 * <p>令牌是测试专用假值，不对应任何环境；请求全部走真实端口，路径按原样发送，不做客户端规范化。
 * 各测试把服务绑到 127.0.0.1：macOS 上通配绑定的端口号在 IPv4 表里仍可被本机其他程序以 127.0.0.1 再绑定，
 * 连到 127.0.0.1 的请求会被抢走，曾出现整个测试类的管理端口请求偶发 reset/EOF。</p>
 */
abstract class ActuatorHttpTestSupport {
    static final String TOKEN = "synthetic-actuator-token";
    static final String TOKEN_HEADER = "actuatorReq";

    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> send(int port, String method, String path, String json, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> get(int port, String path, String token) throws Exception {
        return send(port, "GET", path, null, token);
    }

    /** 只看状态码，丢弃响应体，避免 heapdump 这类大响应在保护失效时撑爆内存。 */
    int statusOf(int port, String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header(TOKEN_HEADER, token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
