package com.companion.config;

import com.companion.controller.ChatWebSocketHandler;
import com.companion.controller.VoiceCallWebSocketHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatHandler;
    private final VoiceCallWebSocketHandler voiceCallHandler;

    public WebSocketConfig(ChatWebSocketHandler chatHandler,
                           VoiceCallWebSocketHandler voiceCallHandler) {
        this.chatHandler = chatHandler;
        this.voiceCallHandler = voiceCallHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatHandler, "/chat")
                .addInterceptors(new UserHandshakeInterceptor())
                .setAllowedOrigins("*");
        registry.addHandler(voiceCallHandler, "/voice-call")
                .addInterceptors(new UserHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    private static class UserHandshakeInterceptor implements HandshakeInterceptor {
        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            // 每个浏览器用自己的 user 参数作为 sessionId，实现浏览器间聊天记录隔离
            // 绑定微信后，前端会把 user 更新为 ilink_user_id，从而与微信端共享记录
            URI uri = request.getURI();
            String rawQuery = uri.getRawQuery();
            String user = null;
            if (rawQuery != null) {
                for (String pair : rawQuery.split("&")) {
                    if (pair.startsWith("user=")) {
                        user = URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8);
                        break;
                    }
                }
            }
            String sessionId = (user != null && !user.isBlank()) ? user : "anonymous";
            attributes.put("sessionId", sessionId);
            return true;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
