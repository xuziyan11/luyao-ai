package com.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 微信扫码登录（微信开放平台「网站应用」OAuth 2.0）。
 * <p>
 * 启用条件（缺一不可）：
 * 1. 在 https://open.weixin.qq.com 注册并通过「网站应用」审核（需企业主体 + 300元/年认证）；
 * 2. 授权回调域配置为已 ICP 备案的域名；
 * 3. 环境变量 WECHAT_APPID / WECHAT_SECRET / WECHAT_REDIRECT_URI 配置完整。
 * 未配置时 isConfigured() 返回 false，前端隐藏真实扫码入口。
 */
@Slf4j
@Service
public class WechatOAuthService {

    /** 微信侧返回的用户概要。 */
    public record WechatUser(String openid, String nickname) {
    }

    @Value("${companion.wechat.appid:}")
    private String appid;

    @Value("${companion.wechat.secret:}")
    private String secret;

    @Value("${companion.wechat.redirect-uri:}")
    private String redirectUri;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isConfigured() {
        return appid != null && !appid.isBlank() && secret != null && !secret.isBlank()
                && redirectUri != null && !redirectUri.isBlank();
    }

    /** 生成扫码授权页 URL，state 用于防 CSRF。 */
    public String buildAuthorizeUrl(String state) {
        return "https://open.weixin.qq.com/connect/qrconnect?appid=" + enc(appid)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code&scope=snsapi_login&state=" + enc(state)
                + "#wechat_redirect";
    }

    /** 用授权 code 换取 openid 与昵称，失败返回 null。 */
    public WechatUser fetchUser(String code) {
        try {
            String tokenUrl = "https://api.weixin.qq.com/sns/oauth2/access_token?appid=" + enc(appid)
                    + "&secret=" + enc(secret) + "&code=" + enc(code)
                    + "&grant_type=authorization_code";
            JsonNode tokenJson = getJson(tokenUrl);
            if (tokenJson == null || tokenJson.has("errcode")) {
                log.warn("微信 code 换 token 失败: {}", tokenJson);
                return null;
            }
            String accessToken = tokenJson.get("access_token").asText();
            String openid = tokenJson.get("openid").asText();

            String userUrl = "https://api.weixin.qq.com/sns/userinfo?access_token=" + enc(accessToken)
                    + "&openid=" + enc(openid);
            JsonNode userJson = getJson(userUrl);
            String nickname = (userJson != null && userJson.has("nickname"))
                    ? userJson.get("nickname").asText() : "微信用户";
            return new WechatUser(openid, nickname);
        } catch (Exception e) {
            log.error("微信登录换取用户信息异常", e);
            return null;
        }
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return mapper.readTree(resp.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
