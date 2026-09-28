package com.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 火山引擎大模型流式语音识别（ASR）客户端。
 * 协议: wss://openspeech.bytedance.com/api/v3/sauc/bigmodel
 * 二进制协议: 4 字节 header + 4 字节 payload_size + payload
 *
 * 鉴权: X-Api-Key + X-Api-Resource-Id + X-Api-Request-Id + X-Api-Sequence
 * 流程: 建连 → 发送 full client request（含配置 JSON）→ 多次发送 audio only → 发送负包结束
 */
@Slf4j
@Component
public class VolcanoAsrClient {

    @Value("${tts.volcano.api-key:}")
    private String apiKey;

    @Value("${tts.volcano.asr.resource-id:volc.bigasr.sauc.duration}")
    private String asrResourceId;

    @Value("${tts.volcano.asr.endpoint:wss://openspeech.bytedance.com/api/v3/sauc/bigmodel}")
    private String asrEndpoint;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    // ===== 协议常量 =====
    private static final byte MSG_FULL_CLIENT_REQUEST = 0x01;
    private static final byte MSG_AUDIO_ONLY_REQUEST = 0x02;
    private static final byte MSG_FULL_SERVER_RESPONSE = 0x09;
    private static final byte MSG_ERROR_RESPONSE = 0x0F;

    private static final byte FLAG_NO_SEQ = 0x00;
    private static final byte FLAG_LAST_PACKET = 0x02;

    private static final byte SERIAL_JSON = 0x01;
    private static final byte SERIAL_NONE = 0x00;
    private static final byte COMPRESS_NONE = 0x00;

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public AsrSession startSession(Consumer<String> onPartial,
                                   Consumer<String> onFinal,
                                   Runnable onComplete,
                                   Consumer<Throwable> onError) {
        if (!isConfigured()) {
            onError.accept(new IllegalStateException("ASR 未配置 api-key"));
            return null;
        }
        String requestId = UUID.randomUUID().toString();
        String connectId = UUID.randomUUID().toString();

        AsrSession session = new AsrSession();
        session.onPartial = onPartial;
        session.onFinal = onFinal;
        session.onComplete = onComplete;
        session.onError = onError;

        URI endpointUri = URI.create(asrEndpoint);

        log.info("ASR 发起连接 endpoint={} requestId={} resourceId={}", asrEndpoint, requestId, asrResourceId);

        httpClient.newWebSocketBuilder()
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", asrResourceId)
                .header("X-Api-Request-Id", requestId)
                .header("X-Api-Connect-Id", connectId)
                .header("X-Api-Sequence", "-1")
                .buildAsync(endpointUri, new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.info("ASR 连接已建立 requestId={}", requestId);
                        session.wsRef.set(webSocket);
                        try {
                            byte[] configPayload = buildConfigPayload().getBytes();
                            byte[] frame = buildFrame(MSG_FULL_CLIENT_REQUEST, FLAG_NO_SEQ,
                                    SERIAL_JSON, COMPRESS_NONE, 0, configPayload);
                            log.info("ASR 发送 full client request: frame_len={} header_hex={}",
                                    frame.length, hexDump(frame, 0, 8));
                            webSocket.sendBinary(ByteBuffer.wrap(frame), true);
                        } catch (Exception e) {
                            log.error("ASR 发送配置失败: {}", e.getMessage());
                            if (onError != null) onError.accept(e);
                        }
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        log.info("ASR 收到二进制消息 len={} hex(first16)={}", bytes.length,
                                hexDump(bytes, 0, Math.min(16, bytes.length)));
                        handleServerFrame(bytes, session);
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        log.info("ASR 连接关闭 code={} reason={}", statusCode, reason);
                        session.closed = true;
                        if (onComplete != null) onComplete.run();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.error("ASR 连接错误: {}", error.getMessage());
                        session.closed = true;
                        if (onError != null) onError.accept(error);
                    }
                }).exceptionally(t -> {
                    log.error("ASR 连接建立失败: {}", t.getMessage());
                    session.closed = true;
                    if (onError != null) onError.accept(t);
                    return null;
                });

        return session;
    }

    private String hexDump(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            sb.append(String.format("%02x ", data[offset + i]));
        }
        return sb.toString().trim();
    }

    private String buildConfigPayload() {
        return "{\"user\":{\"uid\":\"companion_voice_call\"}," +
                "\"audio\":{\"format\":\"pcm\",\"rate\":16000,\"bits\":16,\"channel\":1}," +
                "\"request\":{\"model_name\":\"bigmodel\",\"enable_itn\":true,\"enable_punc\":true," +
                "\"vad_segment\":true,\"end_window_size\":800,\"force_to_speech_time\":1000}}";
    }

    private void handleServerFrame(byte[] data, AsrSession session) {
        if (data.length < 8) {
            log.warn("ASR 响应帧过短: {} bytes", data.length);
            return;
        }
        byte headerSizeDiv4 = (byte) (data[0] & 0x0F);
        int headerBytes = headerSizeDiv4 * 4;
        byte msgType = (byte) ((data[1] >> 4) & 0x0F);
        byte flags = (byte) (data[1] & 0x0F);

        // 尝试两种 payload_size 偏移：按 flag 判定 vs 强制多跳 4 字节 sequence
        for (int trySeq = 0; trySeq <= 1; trySeq++) {
            int psOff = headerBytes + (trySeq == 1 ? 4 : 0);
            if (psOff + 4 > data.length) continue;
            int payloadSize = ((data[psOff] & 0xFF) << 24) | ((data[psOff + 1] & 0xFF) << 16)
                    | ((data[psOff + 2] & 0xFF) << 8) | (data[psOff + 3] & 0xFF);

            if (payloadSize <= 0 || payloadSize > 1_000_000) continue; // 不合理
            int payloadOffset = psOff + 4;
            if (payloadOffset + payloadSize > data.length) continue; // 超出范围

            byte[] payload = new byte[payloadSize];
            System.arraycopy(data, payloadOffset, payload, 0, payloadSize);
            String json = new String(payload);
            try {
                JsonNode root = mapper.readTree(json);
                // 成功解析 JSON，说明偏移正确
                processServerJson(root, msgType, session, json);
                return;
            } catch (Exception e) {
                // JSON 解析失败，尝试下一种偏移
                log.debug("ASR payload JSON 解析失败 (trySeq={}): {}", trySeq, e.getMessage());
            }
        }
        log.warn("ASR 响应帧解析失败 len={} flags={} msgType={} hex={}",
                data.length, flags, msgType, hexDump(data, 0, Math.min(20, data.length)));
    }

    private void processServerJson(JsonNode root, byte msgType, AsrSession session, String rawJson) {
        if (msgType == MSG_ERROR_RESPONSE) {
            String errMsg = root.path("message").asText("未知错误");
            int code = root.path("code").asInt(-1);
            log.error("ASR 服务端错误 code={} msg={} json={}", code, errMsg, rawJson);
            if (session.onError != null) {
                session.onError.accept(new RuntimeException("ASR 错误 code=" + code + " msg=" + errMsg));
            }
            return;
        }
        // MSG_FULL_SERVER_RESPONSE
        String reqId = root.path("req_id").asText("");
        JsonNode result = root.path("result");
        if (result.isArray() && result.size() > 0) {
            StringBuilder sb = new StringBuilder();
            boolean anyDefinite = false;
            for (JsonNode item : result) {
                String text = item.path("text").asText("");
                if (!text.isEmpty()) sb.append(text);
                boolean definite = item.path("definite").asBoolean(false);
                if (definite) anyDefinite = true;
            }
            String text = sb.toString();
            log.info("ASR result(ARRAY) text='{}' definite={}", text, anyDefinite);
            if (!text.isEmpty()) {
                if (anyDefinite) {
                    log.info("ASR final: {}", text);
                    if (session.onFinal != null) session.onFinal.accept(text);
                }
                if (session.onPartial != null) session.onPartial.accept(text);
            }
        } else if (result.isObject()) {
            // 大模型 ASR 返回的 result 是对象
            StringBuilder keys = new StringBuilder("[");
            result.fieldNames().forEachRemaining(f -> keys.append(f).append(","));
            if (keys.length() > 1) keys.setLength(keys.length() - 1);
            keys.append("]");
            log.info("ASR result(OBJECT) keys={}", keys.toString());
            String text = result.path("text").asText("");
            if (text.isEmpty()) text = result.path("transcript").asText("");
            if (text.isEmpty()) text = result.path("final_transcript").asText("");
            // 大模型 utterances 数组
            if (text.isEmpty()) {
                JsonNode utterances = result.path("utterances");
                log.info("ASR utterances nodeType={} asText='{}'", utterances.getNodeType(), utterances.asText("(missing)"));
                if (utterances.isArray() && utterances.size() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode u : utterances) {
                        StringBuilder ks = new StringBuilder("[");
                        u.fieldNames().forEachRemaining(k -> ks.append(k).append(","));
                        if (ks.length() > 1) ks.setLength(ks.length() - 1);
                        ks.append("]");
                        log.info("ASR utterance keys={} text={}", ks.toString(), u.path("text").asText("(empty)"));
                        String t = u.path("text").asText("");
                        if (t.isEmpty()) t = u.path("transcript").asText("");
                        sb.append(t);
                    }
                    text = sb.toString();
                    if (!text.isEmpty()) {
                        boolean definite = false;
                        JsonNode last = utterances.get(utterances.size() - 1);
                        definite = last.path("definite").asBoolean(false);
                        log.info("ASR result(OBJECT) from utterances text='{}' definite={}", text, definite);
                        if (definite) {
                            log.info("ASR final: {}", text);
                            if (session.onFinal != null) session.onFinal.accept(text);
                        }
                        if (session.onPartial != null) session.onPartial.accept(text);
                    }
                }
            } else {
                boolean definite = result.path("definite").asBoolean(false);
                log.info("ASR result(OBJECT) text='{}' definite={}", text, definite);
                if (definite) {
                    log.info("ASR final: {}", text);
                    if (session.onFinal != null) session.onFinal.accept(text);
                }
                if (session.onPartial != null) session.onPartial.accept(text);
            }
        } else {
            // 可能是 final_transcript 或其他字段
            JsonNode transcript = root.path("final_transcript");
            if (!transcript.isMissingNode() && !transcript.asText().isEmpty()) {
                log.info("ASR final_transcript: {}", transcript.asText());
                if (session.onFinal != null) session.onFinal.accept(transcript.asText());
            }
            JsonNode partial = root.path("text");
            if (!partial.isMissingNode() && !partial.asText().isEmpty()) {
                log.info("ASR text: {}", partial.asText());
                if (session.onPartial != null) session.onPartial.accept(partial.asText());
            }
        }
    }

    private byte[] buildFrame(byte msgType, byte flags, byte serialization,
                              byte compression, int sequence, byte[] payload) {
        int totalSize = 8 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) (((byte) 0x01 << 4) | (byte) 0x01)); // version=1, header_size=1 (4 bytes)
        buf.put((byte) (((msgType & 0x0F) << 4) | (flags & 0x0F)));
        buf.put((byte) (((serialization & 0x0F) << 4) | (compression & 0x0F)));
        buf.put((byte) 0x00); // reserved
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    public class AsrSession {
        private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
        private final AtomicReference<CompletableFuture<?>> sendTail = new AtomicReference<>(CompletableFuture.completedFuture(null));
        private volatile boolean finished = false;
        private volatile boolean closed = false;
        Consumer<String> onPartial;
        Consumer<String> onFinal;
        Runnable onComplete;
        Consumer<Throwable> onError;

        public void sendAudio(byte[] pcm) {
            if (closed || wsRef.get() == null) return;
            WebSocket ws = wsRef.get();
            byte[] frame = buildFrame(MSG_AUDIO_ONLY_REQUEST, FLAG_NO_SEQ,
                    SERIAL_NONE, COMPRESS_NONE, 0, pcm);
            ByteBuffer buf = ByteBuffer.wrap(frame);
            sendTail.updateAndGet(prev -> 
                prev.exceptionally(ex -> null).thenCompose(v -> ws.sendBinary(buf, true))
            );
        }

        public void finish() {
            if (finished || closed || wsRef.get() == null) return;
            finished = true;
            WebSocket ws = wsRef.get();
            byte[] frame = buildFrame(MSG_AUDIO_ONLY_REQUEST, FLAG_LAST_PACKET,
                    SERIAL_NONE, COMPRESS_NONE, 0, new byte[0]);
            ByteBuffer buf = ByteBuffer.wrap(frame);
            sendTail.updateAndGet(prev -> 
                prev.exceptionally(ex -> null).thenCompose(v -> {
                    log.info("ASR 已发送负包");
                    return ws.sendBinary(buf, true);
                })
            );
        }

        public synchronized void close() {
            if (closed) return;
            closed = true;
            WebSocket ws = wsRef.get();
            if (ws != null) {
                try { ws.sendClose(1000, "session closed"); } catch (Exception ignored) {}
            }
        }

        public boolean isClosed() { return closed; }
    }
}
