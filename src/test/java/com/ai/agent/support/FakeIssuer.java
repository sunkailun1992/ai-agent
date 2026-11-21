package com.ai.agent.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 本机回环上的假签发方：提供 OIDC 发现文档与 JWKS，签出 RS256 访问令牌，让资源服务器按真实流程校验签名、签发方、受众与有效期。
 * 签发方地址形如 Keycloak（{@code /realms/github-dev}）。
 */
public final class FakeIssuer implements AutoCloseable {

    private static final String REALM = "/realms/github-dev";

    private final HttpServer server;

    private final RSAKey key;

    private FakeIssuer() throws IOException, JOSEException {
        key = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(REALM + "/.well-known/openid-configuration", exchange -> json(exchange,
                "{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + issuer() + "/protocol/openid-connect/certs\","
                        + "\"token_endpoint\":\"" + issuer() + "/protocol/openid-connect/token\","
                        + "\"id_token_signing_alg_values_supported\":[\"RS256\"],\"subject_types_supported\":[\"public\"],"
                        + "\"response_types_supported\":[\"code\"]}"));
        server.createContext(REALM + "/protocol/openid-connect/certs", exchange -> json(exchange, new JWKSet(key.toPublicJWK()).toString()));
        server.start();
    }

    /**
     * 启动假签发方。
     *
     * @return 已启动的签发方
     */
    public static FakeIssuer start() {
        try {
            return new FakeIssuer();
        } catch (IOException | JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 签发方地址（资源服务器的 issuer-uri）。
     *
     * @return 地址
     */
    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + REALM;
    }

    /**
     * ai 经令牌交换取得的令牌形状：受众 github-ai-agent、azp github-ai、带 user_id。
     *
     * @param userId 业务用户 ID
     * @return 令牌原文
     */
    public String exchangedToken(String userId) {
        return token(userId, "github-ai-agent", "github-ai");
    }

    /**
     * 指定受众与 azp 的令牌。
     *
     * @param userId   业务用户 ID
     * @param audience 受众
     * @param azp      签发给哪个客户端
     * @return 令牌原文
     */
    public String token(String userId, String audience, String azp) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer()).subject(UUID.randomUUID().toString())
                .audience(List.of(audience)).claim("azp", azp).claim("user_id", userId)
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300))).jwtID(UUID.randomUUID().toString()).build();
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
