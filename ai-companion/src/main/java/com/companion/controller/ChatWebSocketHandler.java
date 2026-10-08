package com.companion.controller;

import com.companion.service.AffinityService;
import com.companion.service.ChatService;
import com.companion.service.LlmService;
import com.companion.service.MemoryService;
import com.companion.service.MessageSplitter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 聊天 WebSocket 处理器：仅负责 WebSocket 通道的连接/消息收发，
 * 主对话流程交给 {@link ChatService}，便于 iLink 等其他通道复用。
 */
@Slf4j
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ChatService chatService;
    private final AffinityService affinityService;
    private final MemoryService memoryService;
    private final MessageSplitter messageSplitter;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 固定线程池处理消息，避免阻塞 WebSocket NIO 线程 */
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /** 在线连接：WebSocket 会话 id -> WebSocketSession（一个用户多标签页会对应多条连接） */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /** WebSocket 会话 id -> 业务会话标识（同一账号多标签页各自独立） */
    private final Map<String, String> wsUser = new ConcurrentHashMap<>();

    /** 在线连接接入时间：WebSocket 会话 id -> epochMillis（管理后台在线监控用） */
    private final Map<String, Long> connectedAt = new ConcurrentHashMap<>();

    /** 业务会话标识 -> 其下所有在线 WebSocket 会话 id（用于 isOnline / pushMessage 多标签页判定） */
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();

    /** 管理后台读取当前在线连接（sessionId + 接入时间）。 */
    public List<Map<String, Object>> onlineUsers() {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        sessions.forEach((wsId, ws) -> {
            Map<String, Object> item = new java.util.HashMap<>();
            item.put("sessionId", wsUser.getOrDefault(wsId, wsId));
            item.put("connectedAt", connectedAt.getOrDefault(wsId, 0L));
            item.put("open", ws.isOpen());
            list.add(item);
        });
        list.sort((a, b) -> Long.compare((Long) b.get("connectedAt"), (Long) a.get("connectedAt")));
        return list;
    }

    public int onlineCount() {
        return sessions.size();
    }

    /** 指定聊天会话当前是否在线（定时主动提醒只在网页打开时推送）。同一账号多标签页任一在线即算在线。 */
    public boolean isOnline(String sessionId) {
        Set<String> wsIds = userSessions.get(sessionId);
        if (wsIds == null) return false;
        for (String wsId : wsIds) {
            WebSocketSession ws = sessions.get(wsId);
            if (ws != null && ws.isOpen()) return true;
        }
        return false;
    }

    /** 向在线会话推送一条 AI 主动消息（定时提醒/久未问候用），离线自动忽略。同一账号多标签页全部推送。 */
    public void pushMessage(String sessionId, String content) {
        Set<String> wsIds = userSessions.get(sessionId);
        if (wsIds == null) return;
        ObjectNode node = messageNode(content);
        for (String wsId : wsIds) {
            WebSocketSession ws = sessions.get(wsId);
            if (ws != null && ws.isOpen()) {
                sendJson(ws, node);
            }
        }
    }

    public ChatWebSocketHandler(ChatService chatService,
                                AffinityService affinityService,
                                MemoryService memoryService,
                                MessageSplitter messageSplitter) {
        this.chatService = chatService;
        this.affinityService = affinityService;
        this.memoryService = memoryService;
        this.messageSplitter = messageSplitter;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String sessionId = resolveSessionId(session);
        String wsId = session.getId();
        sessions.put(wsId, session);
        wsUser.put(wsId, sessionId);
        connectedAt.put(wsId, System.currentTimeMillis());
        userSessions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(wsId);
        log.info("连接建立: {} (ws={})", sessionId, session.getId());

        List<LlmService.ChatMessage> history = memoryService.getAllMessages(sessionId);
        if (!history.isEmpty()) {
            sendJson(session, historyNode(history));
        }

        // 下发当前好感度与阶段，前端初始化进度条
        int affinity = affinityService.getAffinity(sessionId);
        sendJson(session, infoNode(affinity));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = resolveSessionId(session);
        String payload = message.getPayload();
        if (payload == null || payload.isBlank()) {
            return;
        }
        // 解析请求体：新版前端发 JSON {text, clientTime, clientTzOffset}；兼容旧客户端直接发纯文本
        String userText;
        Long clientTime = null;
        Integer clientTzOffset = null;
        try {
            JsonNode node = mapper.readTree(payload);
            if (node != null && node.isObject() && node.has("text")) {
                userText = node.get("text").asText();
                if (node.has("clientTime") && !node.get("clientTime").isNull()) {
                    clientTime = node.get("clientTime").asLong();
                }
                if (node.has("clientTzOffset") && !node.get("clientTzOffset").isNull()) {
                    clientTzOffset = node.get("clientTzOffset").asInt();
                }
            } else {
                userText = payload;
            }
        } catch (Exception e) {
            // 非 JSON（旧客户端）：原样当正文，时间回退服务端
            userText = payload;
        }
        final String finalText = userText;
        final Long ct = clientTime;
        final Integer cto = clientTzOffset;
        log.info("收到消息: sessionId={}, text={}, clientTime={}, clientTzOffset={}", sessionId, finalText, ct, cto);
        executor.submit(() -> {
            try {
                process(session, sessionId, finalText, ct, cto);
            } catch (Throwable e) {
                log.error("process 异常", e);
            }
        });
    }

    private void process(WebSocketSession session, String sessionId, String userText, Long clientTime, Integer clientTzOffset) {
        try {
            // 按句子逐条发送：缓冲流式文本，遇到句末标点或 ||| 时发送一条独立消息
            final StringBuilder pending = new StringBuilder();
            chatService.chat(sessionId, userText, chunk -> {
                pending.append(chunk);
                // 循环提取所有完整句子
                while (true) {
                    String text = pending.toString();
                    // 过滤完整的 [affinity:xxx] 标记
                    text = text.replaceAll("\\[affinity:[^\\]]*\\]", "");
                    // 过滤完整的 (xxx) / （xxx）括号描述
                    text = text.replaceAll("[（(][^（()）]*[）)]", "");

                    // 末尾不完整标记暂存，等后续 chunk 补全
                    int idxA = text.lastIndexOf("[affinity");
                    int idxP = Math.max(text.lastIndexOf("（"), text.lastIndexOf("("));
                    int idxMark = Math.max(idxA, idxP);
                    String processable;
                    String holdback;
                    if (idxMark != -1) {
                        processable = text.substring(0, idxMark);
                        holdback = text.substring(idxMark);
                    } else {
                        processable = text;
                        holdback = "";
                    }

                    // 找第一个句子结束符（。！？!?~…\n）
                    int sentEnd = -1;
                    for (int i = 0; i < processable.length(); i++) {
                        char c = processable.charAt(i);
                        if (c == '。' || c == '！' || c == '？' || c == '!'
                                || c == '?' || c == '~' || c == '…' || c == '\n') {
                            sentEnd = i;
                            break;
                        }
                    }
                    // 也检查已有的 ||| 分隔符
                    int sepIdx = processable.indexOf("|||");

                    int splitAt = -1;
                    if (sentEnd != -1 && (sepIdx == -1 || sentEnd < sepIdx)) {
                        splitAt = sentEnd + 1;
                    } else if (sepIdx != -1) {
                        splitAt = sepIdx + 3;
                    }

                    if (splitAt == -1) {
                        // 没有完整句子，继续缓冲
                        pending.setLength(0);
                        pending.append(processable);
                        pending.append(holdback);
                        break;
                    }

                    String toSend = processable.substring(0, splitAt).replace("|||", "").trim();
                    String remainder = processable.substring(splitAt);
                    pending.setLength(0);
                    pending.append(remainder);
                    pending.append(holdback);

                    if (!toSend.isEmpty()) {
                        sendJson(session, streamNode(toSend + "|||"));
                        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                    }
                }
            }, "web", clientTime, clientTzOffset);

            // 发送剩余文本（无句末标点的尾部）
            String remaining = pending.toString()
                    .replaceAll("\\[affinity:[^\\]]*\\]", "")
                    .replaceAll("[（(][^（()）]*[）)]", "")
                    .replace("|||", "")
                    .trim();
            if (!remaining.isEmpty()) {
                sendJson(session, streamNode(remaining));
            }

            // 推送好感度更新
            int newAffinity = affinityService.getAffinity(sessionId);
            sendJson(session, affinityNode(newAffinity));

            // 发送完成信号
            sendJson(session, doneNode());

        } catch (Exception e) {
            log.error("处理消息异常: sessionId={}", sessionId, e);
            sendJsonSafe(session, errorNode("路瑶好像走神了，稍等再试试~"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String wsId = session.getId();
        String sessionId = wsUser.remove(wsId);
        sessions.remove(wsId);
        connectedAt.remove(wsId);
        if (sessionId != null) {
            Set<String> wsIds = userSessions.get(sessionId);
            if (wsIds != null) {
                wsIds.remove(wsId);
                if (wsIds.isEmpty()) {
                    userSessions.remove(sessionId);
                }
            }
        }
        log.info("连接关闭: {} (ws={}) 状态={}", sessionId, wsId, status);
    }

    // ---------- 会话标识：优先取 URL 中的 user 参数，否则用 ws session id ----------

    private String resolveSessionId(WebSocketSession session) {
        String fallback = session.getAttributes().containsKey("sessionId")
                ? String.valueOf(session.getAttributes().get("sessionId"))
                : session.getId();
        return fallback;
    }

    public static String resolveSessionIdFromUri(java.net.URI uri, String fallbackId, String user) {
        // 认证后的 WebSocket 必须以服务端会话为准，不能让浏览器随机 user 参数覆盖真实登录会话。
        // 这样可以避免不同手机/无痕页面因为随机 user 造成聊天上下文错乱，且仍保持不同浏览器独立。
        return fallbackId;
    }

    // ---------- JSON 输出节点 ----------

    private String stageName(int affinity) {
        // 只保留"朋友"阶段：统一返回"朋友"，不改变其他逻辑
        return "朋友";
    }

    private ObjectNode infoNode(int affinity) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "info");
        node.put("affinity", affinity);
        node.put("stage", stageName(affinity));
        return node;
    }

    private ObjectNode historyNode(List<LlmService.ChatMessage> history) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "history");
        var messages = node.putArray("messages");
        for (LlmService.ChatMessage msg : history) {
            List<String> parts = messageSplitter.split(msg.content());
            if (parts.isEmpty()) {
                parts = List.of(messageSplitter.stripAffinityMarker(msg.content()));
            }
            for (String part : parts) {
                if (part == null || part.isBlank()) {
                    continue;
                }
                ObjectNode item = mapper.createObjectNode();
                item.put("role", msg.role());
                item.put("content", part);
                if (msg.createdAt() != null) item.put("createdAt", msg.createdAt());
                messages.add(item);
            }
        }
        return node;
    }

    private ObjectNode affinityNode(int affinity) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "affinity");
        node.put("value", affinity);
        node.put("stage", stageName(affinity));
        return node;
    }

    private ObjectNode typingNode() {
        return mapper.createObjectNode().put("type", "typing");
    }

    private ObjectNode streamNode(String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "stream");
        node.put("content", content);
        return node;
    }

    /** AI 主动消息节点：前端按普通左侧气泡渲染并触发语音播报。 */
    private ObjectNode messageNode(String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "message");
        node.put("content", content);
        return node;
    }

    private ObjectNode doneNode() {
        return mapper.createObjectNode().put("type", "done");
    }

    private ObjectNode errorNode(String content) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "error");
        node.put("content", content);
        return node;
    }

    private void sendJson(WebSocketSession session, ObjectNode node) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(node)));
                }
            }
        } catch (Exception e) {
            log.warn("发送消息失败: {}", e.getMessage());
        }
    }

    private void sendJsonSafe(WebSocketSession session, ObjectNode node) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(mapper.writeValueAsString(node)));
            }
        } catch (Exception ignored) {
        }
    }
}
