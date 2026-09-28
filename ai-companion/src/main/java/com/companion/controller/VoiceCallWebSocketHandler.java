package com.companion.controller;

import com.companion.service.DoubaoRealtimeClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.ByteBuffer;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音通话 WebSocket 处理器。
 *
 * 现在使用豆包 Realtime 端到端大模型（Duplex 3.0 / Seeduplex）。
 * 架构：浏览器 → 后端代理 → 豆包 Realtime WebSocket
 *   （后端代理：API Key 不暴露给前端 + 注入人设 prompt + 主动问候）
 *
 * 浏览器 ↔ 后端 协议（不变）：
 *   文本 {type:"start", sessionId} 开启通话
 *   二进制 16kHz 单声道 Int16 PCM 音频帧
 *   文本 {type:"interrupt"} 用户打断
 *   文本 {type:"mute"/"unmute"} 静音 / 取消静音
 *   文本 {type:"stop"} 挂断
 *
 * 后端 ↔ 浏览器 协议：
 *   文本 {type:"ready"} 会话就绪（含问候已播放完）
 *   二进制 24kHz 单声道 Int16 PCM 音频分片（豆包直接返回 PCM）
 *   文本 {type:"asr_partial", text} 用户说话的 ASR 中间结果
 *   文本 {type:"asr_final", text} 用户一句话判停
 *   文本 {type:"ai_text", text} AI 回复文本
 *   文本 {type:"ai_done"} AI 本轮回复结束
 *   文本 {type:"error", message}
 */
@Slf4j
@Component
public class VoiceCallWebSocketHandler extends AbstractWebSocketHandler {

    private final DoubaoRealtimeClient doubaoClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 每个浏览器会话关联的通话上下文 */
    private final Map<String, CallContext> calls = new ConcurrentHashMap<>();

    /** 管理后台读取当前在线语音通话连接。 */
    public List<Map<String, Object>> onlineUsers() {
        List<Map<String, Object>> list = new ArrayList<>();
        calls.forEach((wsSessionId, ctx) -> {
            Map<String, Object> item = new HashMap<>();
            item.put("sessionId", ctx.sessionId);
            item.put("connectedAt", ctx.connectedAt);
            item.put("open", ctx.session != null);
            item.put("type", "voice");
            list.add(item);
        });
        list.sort((a, b) -> Long.compare((Long) b.get("connectedAt"), (Long) a.get("connectedAt")));
        return list;
    }

    public int onlineCount() {
        return calls.size();
    }

    public VoiceCallWebSocketHandler(DoubaoRealtimeClient doubaoClient) {
        this.doubaoClient = doubaoClient;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        CallContext ctx = calls.get(session.getId());
        try {
            var node = mapper.readTree(message.getPayload());
            String type = node.path("type").asText("");
            switch (type) {
                case "start" -> {
                    if (ctx != null && ctx.session != null && !ctx.session.isClosed()) {
                        sendJson(session, "error", Map.of("message", "已有通话进行中"));
                        return;
                    }
                    String sessionId = node.path("sessionId").asText("");
                    if (sessionId.isBlank()) {
                        sendJson(session, "error", Map.of("message", "缺少 sessionId"));
                        return;
                    }
                    startCall(session, sessionId);
                }
                case "interrupt" -> {
                    if (ctx != null && ctx.session != null) {
                        // 打断：豆包 Duplex 3.0 的标准做法是发 response.cancel
                        // 但我们这里用强制判停 + 让豆包继续监听用户说话
                        ctx.session.commitAudio();
                    }
                }
                case "mute" -> {
                    if (ctx != null && ctx.session != null) {
                        ctx.session.mute();
                        ctx.muted.set(true);
                    }
                }
                case "unmute" -> {
                    if (ctx != null && ctx.session != null) {
                        ctx.session.unmute();
                        ctx.muted.set(false);
                        log.info("浏览器麦克风已就绪，已发送 unmute.commit sessionId={}", ctx.sessionId);
                    }
                }
                case "silence_prompt" -> {
                    // 用户 10 秒未说话，路瑶随机说一句轻提醒（每通电话仅一次，由前端控制）
                    if (ctx != null && ctx.session != null && !ctx.session.isClosed()) {
                        String[] prompts = {
                                "你怎么不说话了呀？",
                                "还在吗？我还在等你呢～",
                                "怎么安静啦，是在想我吗？",
                                "喂喂，信号不好吗？",
                                "沉默是今晚的康桥哦，说说话嘛～"
                        };
                        String prompt = prompts[ThreadLocalRandom.current().nextInt(prompts.length)];
                        ctx.session.speakText(prompt);
                        log.info("用户沉默10秒，发送随机沉默提示 sessionId={}", ctx.sessionId);
                    }
                }
                case "stop" -> stopCall(session);
                default -> log.debug("未知消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理文本消息失败: {}", e.getMessage());
            sendJson(session, "error", Map.of("message", "处理失败: " + e.getMessage()));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        CallContext ctx = calls.get(session.getId());
        if (ctx == null || ctx.session == null || ctx.session.isClosed()) {
            return;
        }
        if (ctx.muted.get()) return; // 静音时不上行
        // 转发 PCM 到豆包：必须按 ByteBuffer 的 position/limit 截取有效字节，
        // 直接用 getPayload().array() 会忽略偏移量导致音频字节错位（刺啦声根因之一）
        ByteBuffer buf = message.getPayload();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        ctx.session.sendAudio(data);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        stopCall(session);
    }

    /** 启动通话：建立豆包 Realtime 会话，回调中转发给浏览器。 */
    private void startCall(WebSocketSession session, String sessionId) {
        CallContext ctx = new CallContext();
        ctx.sessionId = sessionId;
        calls.put(session.getId(), ctx);

        DoubaoRealtimeClient.Session doubaoSession = doubaoClient.startSession(
                // onAudioDelta: 豆包返回的 PCM 音频分片 → 浏览器
                audio -> {
                    if (session != null && session.isOpen()) {
                        try {
                            synchronized (session) {
                                if (session.isOpen()) {
                                    session.sendMessage(new BinaryMessage(audio));
                                }
                            }
                        } catch (Exception e) {
                            log.warn("推送音频分片失败: {}", e.getMessage());
                        }
                    }
                },
                // onTextDelta: AI 回复文本增量
                text -> {
                    sendJson(session, "ai_text", Map.of("text", text));
                },
                // onTextDone: 文字完成
                text -> {
                    // 不单独推送 done 事件，等 onDone 统一处理
                },
                // onDone: 本轮回复完成
                () -> {
                    ctx.isUserTextInFlight.set(true); // 回到 ASR 模式
                    sendJson(session, "ai_done", Map.of());
                },
                // onReady: 豆包会话已建立，即将发送问候语，进入 AI 回复模式
                () -> {
                    log.info("豆包 Realtime 会话就绪 sessionId={}", sessionId);
                    ctx.isUserTextInFlight.set(false); // 问候语即将开始，设为 AI 回复模式
                    sendJson(session, "ready", Map.of());
                },
                // onError
                error -> {
                    log.error("豆包 Realtime 错误 sessionId={}: {}", sessionId, error.getMessage());
                    sendJson(session, "error", Map.of("message", "通话错误: " + error.getMessage()));
                },
                // onUserText: 用户说完一句话（豆包 ASR final）
                userText -> {
                    log.info("用户说完: {}", userText);
                    ctx.isUserTextInFlight.set(false); // 进入 AI 回复模式
                    sendJson(session, "asr_final", Map.of("text", userText));
                },
                // onUserTextDelta: 用户 ASR 转写中间结果（AI 回复期间也归用户气泡）
                userDelta -> {
                    sendJson(session, "asr_partial", Map.of("text", userDelta));
                }
        );

        ctx.session = doubaoSession;
        log.info("通话已开启（豆包 Duplex） sessionId={}", sessionId);
    }

    /** 挂断：关闭豆包会话，清理上下文。 */
    private void stopCall(WebSocketSession session) {
        CallContext ctx = calls.remove(session.getId());
        if (ctx != null) {
            if (ctx.session != null) {
                try { ctx.session.close(); } catch (Exception ignored) {}
            }
            log.info("通话已挂断 sessionId={}", ctx.sessionId);
        }
    }

    private void sendJson(WebSocketSession session, String type, Map<String, ?> data) {
        if (session == null || !session.isOpen()) return;
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("type", type);
            payload.putAll(data);
            String json = mapper.writeValueAsString(payload);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.warn("发送 JSON 失败: {}", e.getMessage());
        }
    }

    /** 每个浏览器会话关联的通话上下文。 */
    private static class CallContext {
        String sessionId;
        DoubaoRealtimeClient.Session session;
        /** 接入时间（epoch millis），管理后台在线监控用 */
        long connectedAt = System.currentTimeMillis();
        /** 静音标志：true 时不上行音频 */
        final AtomicBoolean muted = new AtomicBoolean(false);
        /** 当前是 ASR 模式（用户在说话）还是 AI 回复模式（区分文字事件类型） */
        final AtomicBoolean isUserTextInFlight = new AtomicBoolean(false);
    }
}
