package com.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 微信 ClawBot iLink 协议客户端。
 * <p>
 * 协议文档（基于 @tencent-weixin/openclaw-weixin 源码逆向 + 社区资料整理）：
 * - 主域名：https://ilinkai.weixin.qq.com
 * - 鉴权：所有 bot 接口需要 Header
 *   Authorization: Bearer {bot_token}
 *   AuthorizationType: ilink_bot_token
 *   X-WECHAT-UIN: base64(随机 uint32)
 * - 路径命名无下划线：getupdates / sendmessage / getuploadurl / getconfig / sendtyping
 */
@Slf4j
@Service
public class IlinkClient {

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${companion.ilink.default-baseurl:https://ilinkai.weixin.qq.com}")
    private String defaultBaseurl;

    @Value("${companion.ilink.poll-timeout-seconds:35}")
    private int pollTimeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http;
    private final SecureRandom random = new SecureRandom();

    public IlinkClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    /** 构造鉴权 Header（X-WECHAT-UIN 每次都重新生成） */
    private Request.Builder authedBuilder(String url, String botToken) {
        byte[] buf = new byte[4];
        random.nextBytes(buf);
        // 转 uint32
        long u32 = ((buf[0] & 0xFFL) << 24)
                | ((buf[1] & 0xFFL) << 16)
                | ((buf[2] & 0xFFL) << 8)
                | (buf[3] & 0xFFL);
        String uin = Base64.getEncoder().encodeToString(
                java.nio.ByteBuffer.allocate(4).putInt((int) u32).array());
        return new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("AuthorizationType", "ilink_bot_token")
                .header("Authorization", "Bearer " + botToken)
                .header("X-WECHAT-UIN", uin);
    }

    /**
     * 拿扫码二维码（GET，无需鉴权）。
     */
    public QrcodeResult getQrcode() throws Exception {
        String url = defaultBaseurl + "/ilink/bot/get_bot_qrcode?bot_type=3";
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException("get_bot_qrcode HTTP " + resp.code() + ": " + body);
            }
            JsonNode root = mapper.readTree(body);
            int ret = root.path("ret").asInt(-1);
            if (ret != 0) {
                throw new RuntimeException("get_bot_qrcode ret=" + ret + " body=" + body);
            }
            return new QrcodeResult(
                    root.path("qrcode").asText(""),
                    root.path("qrcode_img_content").asText(""));
        }
    }

    /**
     * 查询扫码状态（GET，无需鉴权）。
     */
    public QrcodeStatus getQrcodeStatus(String qrcode) throws Exception {
        String url = defaultBaseurl + "/ilink/bot/get_qrcode_status?qrcode=" + qrcode;
        Request req = new Request.Builder().url(url).get()
                .header("iLink-App-ClientVersion", "1")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new RuntimeException("get_qrcode_status HTTP " + resp.code() + ": " + body);
            }
            JsonNode root = mapper.readTree(body);
            return new QrcodeStatus(
                    root.path("status").asText("waiting"),
                    root.path("bot_token").asText(""),
                    root.path("ilink_bot_id").asText(""),
                    root.path("baseurl").asText(defaultBaseurl),
                    root.path("ilink_user_id").asText(""));
        }
    }

    /**
     * 长轮询拉取用户消息。
     *
     * @param baseurl       微信回传的域名
     * @param botToken      鉴权 token
     * @param getUpdatesBuf 上一次返回的翻页符；首次为 null
     */
    public GetUpdatesResult getUpdates(String baseurl, String botToken, String getUpdatesBuf) throws Exception {
        String url = baseurl + "/ilink/bot/getupdates";
        ObjectNode body = mapper.createObjectNode();
        if (getUpdatesBuf != null && !getUpdatesBuf.isBlank()) {
            body.put("get_updates_buf", getUpdatesBuf);
        }

        Request req = authedBuilder(url, botToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        OkHttpClient longPollClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(pollTimeoutSeconds + 5, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();

        try (Response resp = longPollClient.newCall(req).execute()) {
            String respStr = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                log.error("getupdates HTTP {}: {}", resp.code(), respStr);
                throw new RuntimeException("getupdates HTTP " + resp.code() + ": " + respStr);
            }
            log.info("getupdates 响应长度: {}", respStr.length());
            log.info("getupdates 原始响应: {}", respStr.isBlank() ? "(空)" : respStr);
            log.debug("getupdates 原始响应(调试截断): {}", respStr.length() > 500 ? respStr.substring(0, 500) + "..." : respStr);
            JsonNode root = mapper.readTree(respStr);
            ParsedGetUpdates parsed = parseGetUpdatesRoot(root);
            if (parsed.messages().isEmpty()) {
                log.info("getupdates 未命中消息数组，rootKeys={}", root.isObject() ? root.fieldNames().toString() : root.toString());
            } else {
                log.info("getupdates 收到 {} 条消息", parsed.messages().size());
            }
            return new GetUpdatesResult(parsed.messages(), parsed.newBuf());
        }
    }

    public static ParsedGetUpdates parseGetUpdatesRoot(JsonNode root) {
        String newBuf = firstText(root, "get_updates_buf", "next_cursor", "cursor", "next_buf");
        List<InboundMessage> list = new ArrayList<>();
        JsonNode msgs = findMessageArray(root);
        if (msgs == null || !msgs.isArray()) {
            return new ParsedGetUpdates(list, newBuf);
        }
        for (JsonNode m : msgs) {
            if (m == null || m.isNull() || !m.isObject()) {
                continue;
            }
            String contextToken = firstText(m, "context_token", "contextToken");
            String fromUser = firstText(m, "from_user_id", "from_user", "fromUser", "sender_id", "senderId");
            String msgId = firstText(m, "msg_id", "msgId", "client_id", "clientId", "message_id", "messageId");
            int msgTypeCode = firstInt(m, "message_type", "msg_type", "msgType", "type");
            String content = extractText(m);
            if (content == null || content.isBlank()) {
                continue;
            }
            list.add(new InboundMessage(msgId, fromUser, msgTypeCode, content, contextToken));
        }
        return new ParsedGetUpdates(list, newBuf);
    }

    private static JsonNode findMessageArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node;
        }
        if (!node.isObject()) {
            return null;
        }
        for (String key : List.of("msgs", "messages", "updates", "list", "items", "data", "result")) {
            JsonNode child = node.get(key);
            if (child != null) {
                if (child.isArray()) {
                    return child;
                }
                JsonNode nested = findMessageArray(child);
                if (nested != null && nested.isArray()) {
                    return nested;
                }
            }
        }
        for (JsonNode child : node) {
            JsonNode nested = findMessageArray(child);
            if (nested != null && nested.isArray()) {
                return nested;
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                String text = readText(value);
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static int firstInt(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.path(key);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asInt(1);
            }
        }
        return 1;
    }

    private static String readText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isObject()) {
            for (String key : List.of("text", "content", "value", "msg", "message")) {
                String value = readText(node.path(key));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                String value = readText(child);
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    /** 从 inbound 消息节点提取文本（兼容 item_list.text_item.text、content、text 等多种字段名） */
    static String extractText(JsonNode msgNode) {
        JsonNode itemList = msgNode.path("item_list");
        if (itemList.isArray()) {
            for (JsonNode item : itemList) {
                if (item == null || item.isNull()) {
                    continue;
                }
                String text = readText(item.path("text_item"));
                if (text.isBlank()) {
                    text = readText(item.path("content"));
                }
                if (text.isBlank()) {
                    text = readText(item.path("text"));
                }
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        for (String key : List.of("content", "text", "message", "msg")) {
            String value = readText(msgNode.path(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public record ParsedGetUpdates(List<InboundMessage> messages, String newBuf) {}

    /**
     * 发送一条文本消息。
     *
     * @return 微信侧返回的 context_token（用于下次收消息时关联上下文）
     */
    public String sendText(String baseurl, String botToken, String toUser, String text, String contextToken) throws Exception {
        String url = baseurl + "/ilink/bot/sendmessage";

        ObjectNode body = mapper.createObjectNode();
        ObjectNode msg = body.putObject("msg");
        msg.put("from_user_id", "");                     // 固定空字符串
        msg.put("to_user_id", toUser);
        msg.put("client_id", UUID.randomUUID().toString());  // 每条消息唯一 ID
        msg.put("message_type", 2);                      // MessageType.BOT
        msg.put("message_state", 2);                     // MessageState.FINISH
        if (contextToken != null && !contextToken.isBlank()) {
            msg.put("context_token", contextToken);
        }
        ArrayNode itemList = msg.putArray("item_list");
        ObjectNode item = itemList.addObject();
        item.put("type", 1);                             // 1 = text
        item.putObject("text_item").put("text", text);

        Request req = authedBuilder(url, botToken)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        log.debug("sendmessage 请求体: {}", body);
        try (Response resp = http.newCall(req).execute()) {
            String respStr = resp.body() != null ? resp.body().string() : "";
            log.info("sendmessage HTTP {} 响应: {}", resp.code(), respStr.isEmpty() ? "(空body)" : respStr);
            if (!resp.isSuccessful()) {
                throw new RuntimeException("sendmessage HTTP " + resp.code() + ": " + respStr);
            }
            // 微信侧返回 200 + 空 body 也算成功；尝试解析 context_token
            try {
                JsonNode root = mapper.readTree(respStr);
                JsonNode ctx = root.path("context_token");
                if (!ctx.isMissingNode() && !ctx.asText("").isBlank()) {
                    return ctx.asText(contextToken);
                }
            } catch (Exception ignored) {
                // 空 body 解析失败属正常情况
            }
            return contextToken;
        }
    }

    /**
     * 发送"正在输入"状态，配合打字延迟使用效果更自然。
     * 需要先调 getconfig 拿 ticket，再用 ticket 调 sendtyping。
     */
    public void sendTyping(String baseurl, String botToken, String toUser) {
        try {
            // 1. 拿 ticket
            String configUrl = baseurl + "/ilink/bot/getconfig";
            ObjectNode configBody = mapper.createObjectNode();
            configBody.put("to_user_id", toUser);
            Request configReq = authedBuilder(configUrl, botToken)
                    .post(RequestBody.create(configBody.toString(), JSON))
                    .build();
            String ticket;
            try (Response resp = http.newCall(configReq).execute()) {
                String respStr = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.debug("getconfig 失败 HTTP {}: {}", resp.code(), respStr);
                    return;
                }
                JsonNode root = mapper.readTree(respStr);
                ticket = root.path("ticket").asText("");
                if (ticket.isBlank()) {
                    log.debug("getconfig 未返回 ticket，跳过 typing");
                    return;
                }
            }
            // 2. 发 typing
            String typingUrl = baseurl + "/ilink/bot/sendtyping";
            ObjectNode typingBody = mapper.createObjectNode();
            typingBody.put("to_user_id", toUser);
            typingBody.put("ticket", ticket);
            Request typingReq = authedBuilder(typingUrl, botToken)
                    .post(RequestBody.create(typingBody.toString(), JSON))
                    .build();
            try (Response resp = http.newCall(typingReq).execute()) {
                // 失败不影响主流程
                if (!resp.isSuccessful()) {
                    log.debug("sendtyping 失败 HTTP {}", resp.code());
                }
            }
        } catch (Exception e) {
            log.debug("sendTyping 异常（忽略）: {}", e.getMessage());
        }
    }

    // ---------- DTO ----------

    public record QrcodeResult(String qrcode, String qrcodeImgContent) {}
    public record QrcodeStatus(String status, String botToken, String ilinkBotId, String baseurl, String ilinkUserId) {
        public boolean confirmed() { return "confirmed".equalsIgnoreCase(status); }
    }
    public record InboundMessage(String msgId, String fromUser, int msgType, String content, String contextToken) {}
    public record GetUpdatesResult(List<InboundMessage> messages, String getUpdatesBuf) {}
}
