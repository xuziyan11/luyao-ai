package com.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 火山引擎 TTS 客户端
 * 支持: V1 HTTP(小模型) + V3 API(豆包大模型2.0) + 声音复刻
 * 文档: https://www.volcengine.com/docs/6561/1257584
 */
@Slf4j
@Component
public class VolcanoTtsClient {

    @Value("${tts.volcano.appid:}")
    private String appid;

    @Value("${tts.volcano.access-token:}")
    private String accessToken;

    @Value("${tts.volcano.api-key:}")
    private String apiKey;

    @Value("${tts.volcano.cluster:volcano_tts}")
    private String cluster;

    @Value("${tts.volcano.voice:BV001_streaming}")
    private String defaultVoice;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String V1_TTS_URL = "https://openspeech.bytedance.com/api/v1/tts";
    private static final String V3_TTS_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional";
    private static final String VOICE_CLONE_URL = "https://openspeech.bytedance.com/api/v1/mega_tts/audio/upload";

    /**
     * V1 API 是否可用（小模型音色 BV001_streaming 等）
     */
    public boolean isV1Configured() {
        return appid != null && appid.matches("\\d+")
                && accessToken != null && !accessToken.isBlank();
    }

    /**
     * V3 API 是否可用（豆包大模型2.0音色 _bigtts 结尾）
     */
    public boolean isV3Configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 是否有任何火山引擎配置
     */
    public boolean isConfigured() {
        return isV1Configured() || isV3Configured();
    }

    /**
     * 语音合成 - 自动选择 V1 或 V3
     */
    public byte[] synthesize(String text, String voice) {
        return synthesize(text, voice, 1.0);
    }

    /**
     * 语音合成（带语速）：speedRatio 1.0 为正常语速，
     * V1 映射 speed_ratio，V3 映射 speech_rate（-50~100 的百分比）。
     */
    public byte[] synthesize(String text, String voice, double speedRatio) {
        String useVoice = (voice != null && !voice.isBlank()) ? voice : defaultVoice;

        // _bigtts 结尾的音色用 V3 API
        if (useVoice.endsWith("_bigtts")) {
            if (isV3Configured()) {
                return synthesizeV3(text, useVoice, speedRatio);
            }
            log.warn("音色 {} 需要 V3 API，但未配置 api-key，将降级", useVoice);
            return null;
        }

        // 其他音色用 V1 API
        if (isV1Configured()) {
            return synthesizeV1(text, useVoice, speedRatio);
        }
        log.warn("音色 {} 需要 V1 API，但未配置 appid/token，将降级", useVoice);
        return null;
    }

    /**
     * V1 HTTP 非流式合成（小模型音色）
     */
    private byte[] synthesizeV1(String text, String voice, double speedRatio) {
        String useVoice = (voice != null && !voice.isBlank()) ? voice : defaultVoice;
        String reqid = UUID.randomUUID().toString();

        // 自动识别 cluster
        String useCluster;
        if (useVoice.startsWith("S_") || useVoice.startsWith("ICL_") || useVoice.startsWith("saturn_")) {
            useCluster = "volcano_icl";
        } else {
            useCluster = cluster;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer;" + accessToken);

            Map<String, Object> body = Map.of(
                    "app", Map.of("appid", appid, "token", "access_token", "cluster", useCluster),
                    "user", Map.of("uid", "companion_user"),
                    "audio", Map.of(
                            "voice_type", useVoice,
                            "encoding", "mp3",
                            "speed_ratio", 1.0,
                            "volume_ratio", 1.0,
                            "pitch_ratio", 1.0
                    ),
                    "request", Map.of(
                            "reqid", reqid,
                            "text", text,
                            "text_type", "plain",
                            "operation", "query"
                    )
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    V1_TTS_URL, HttpMethod.POST, entity, String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("火山引擎 V1 TTS 返回: {}", response.getStatusCode());
                return null;
            }

            JsonNode json = objectMapper.readTree(response.getBody());
            int code = json.path("code").asInt(-1);
            if (code != 3000) {
                log.error("火山引擎 V1 TTS 错误 code={} msg={}", code, json.path("message").asText());
                return null;
            }

            String base64Audio = json.path("data").asText("");
            if (base64Audio.isBlank()) {
                log.warn("火山引擎 V1 TTS 返回空音频");
                return null;
            }

            return Base64.getDecoder().decode(base64Audio);
        } catch (Exception e) {
            log.error("火山引擎 V1 TTS 合成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * V3 API 合成（豆包大模型2.0音色）
     * V3 返回 HTTP/2 chunked 流：多个 JSON 对象拼接（NDJSON风格），
     * 每个分片含 base64 音频段，必须完整读到 EOF 再合并，否则只读第一个字。
     */
    private byte[] synthesizeV3(String text, String speaker, double speedRatio) {
        String requestId = UUID.randomUUID().toString();
        // speech_rate：-50（最慢）~ 100（最快）的百分比，0 为正常语速
        int speechRate = (int) Math.round(Math.max(-50, Math.min(100, (speedRatio - 1.0) * 100)));

        try {
            // 使用 ofInputStream + readAllBytes，确保 chunked 流完整读到 EOF
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            String bodyJson = objectMapper.writeValueAsString(Map.of(
                    "req_params", Map.of(
                            "text", text,
                            "speaker", speaker,
                            "audio_params", Map.of(
                                    "format", "mp3",
                                    "sample_rate", 24000,
                                    "speech_rate", speechRate
                            )
                    )
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(V3_TTS_URL))
                    .header("X-Api-Key", apiKey)
                    .header("X-Api-Resource-Id", "seed-tts-2.0")
                    .header("X-Api-Request-Id", requestId)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                byte[] errBody = response.body().readAllBytes();
                String errStr = errBody != null ? new String(errBody) : "no body";
                log.error("火山引擎 V3 TTS 错误 HTTP {} body={}", response.statusCode(),
                        errStr.length() > 200 ? errStr.substring(0, 200) : errStr);
                return null;
            }

            // 完整读取全部响应体直到 EOF，保证所有多分片都拿到
            byte[] rawBody;
            try (var in = response.body()) {
                rawBody = in.readAllBytes();
            }

            if (rawBody == null || rawBody.length == 0) {
                log.warn("火山引擎 V3 TTS 返回空响应体");
                return null;
            }

            if (rawBody[0] == (byte) '{') {
                return parseV3JsonResponse(rawBody);
            }

            log.info("火山引擎 V3 TTS 成功(原始二进制)，音频大小: {} bytes", rawBody.length);
            return rawBody;
        } catch (Exception e) {
            log.error("火山引擎 V3 TTS 合成失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 V3 NDJSON（多个 JSON 直接拼接）逐分片合并 base64 音频
     * 关键：必须先合并所有分片的 data，不能只解析第一个 JSON 就返回
     */
    private byte[] parseV3JsonResponse(byte[] jsonData) {
        try {
            String jsonStr = new String(jsonData);
            java.io.ByteArrayOutputStream audioBuffer = new java.io.ByteArrayOutputStream();

            // 优先走多分片合并：以 "{" 边界拆分 JSON，提取每个分片的 data/audio 字段累加
            String[] chunks = jsonStr.split("(?=\\{)");
            for (String chunk : chunks) {
                if (chunk == null || chunk.isBlank()) continue;
                try {
                    JsonNode node = objectMapper.readTree(chunk);
                    String audio = node.path("data").asText("");
                    if (audio.isBlank()) {
                        audio = node.path("audio").asText("");
                    }
                    if (!audio.isBlank()) {
                        audioBuffer.write(Base64.getDecoder().decode(audio));
                    }
                } catch (Exception ignored) {}
            }

            if (audioBuffer.size() > 0) {
                byte[] merged = audioBuffer.toByteArray();
                log.info("火山引擎 V3 TTS 合并成功，分片数={} 总音频={} bytes", chunks.length, merged.length);
                return merged;
            }

            // 兜底：单 JSON 对象解析
            JsonNode root = objectMapper.readTree(jsonStr);
            if (root.has("data")) {
                String base64Audio = root.path("data").asText("");
                if (!base64Audio.isBlank()) {
                    return Base64.getDecoder().decode(base64Audio);
                }
            }
            if (root.has("audio")) {
                String base64Audio = root.path("audio").asText("");
                if (!base64Audio.isBlank()) {
                    return Base64.getDecoder().decode(base64Audio);
                }
            }

            log.error("V3 响应无法解析音频数据: {}",
                    jsonStr.length() > 200 ? jsonStr.substring(0, 200) : jsonStr);
            return null;
        } catch (Exception e) {
            log.error("V3 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 声音复刻 - 上传音频训练自定义音色
     */
    public String uploadVoice(String speakerId, byte[] audioBytes, String format, int language, int modelType) {
        if (!isV1Configured()) {
            return "error: 火山引擎未配置 appid/token";
        }

        try {
            String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
            String resourceId = modelType >= 4 ? "seed-icl-2.0" : "seed-icl-1.0";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer;" + accessToken);
            headers.set("Resource-Id", resourceId);

            Map<String, Object> body = Map.of(
                    "speaker_id", speakerId,
                    "appid", appid,
                    "audios", java.util.List.of(Map.of(
                            "audio_bytes", base64Audio,
                            "audio_format", format
                    )),
                    "source", 2,
                    "language", language,
                    "model_type", modelType
            );

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(
                    VOICE_CLONE_URL, HttpMethod.POST, entity, String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return "error: HTTP " + response.getStatusCode();
            }

            JsonNode json = objectMapper.readTree(response.getBody());
            int code = json.path("code").asInt(-1);
            if (code == 3000) {
                return "success: 音色训练已提交，speaker_id=" + speakerId;
            }
            return "error: code=" + code + " msg=" + json.path("message").asText();
        } catch (Exception e) {
            log.error("声音复刻上传失败: {}", e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * V3 流式合成：边从 HTTP 响应读分片，边通过 chunkConsumer 回调推送，
     * 让 VoiceCall 后端可以收到一片就转发给浏览器播放，降低首字延迟。
     * 失败时回调不会被调用，调用方可通过返回值 false 判断。
     *
     * @return 是否成功开始流式（false 表示建连或首个分片前就失败）
     */
    public boolean streamSynthesize(String text, String speaker, double speedRatio,
                                    java.util.function.Consumer<byte[]> chunkConsumer) {
        if (!isV3Configured()) {
            log.warn("流式 TTS 需要 V3 API Key，未配置");
            return false;
        }
        String useSpeaker = (speaker == null || speaker.isBlank()) ? defaultVoice : speaker;
        if (!useSpeaker.endsWith("_bigtts")) {
            // 非 _bigtts 音色退化为非流式 V1
            byte[] full = synthesizeV1(text, useSpeaker, speedRatio);
            if (full == null || full.length == 0) return false;
            chunkConsumer.accept(full);
            return true;
        }
        String requestId = UUID.randomUUID().toString();
        int speechRate = (int) Math.round(Math.max(-50, Math.min(100, (speedRatio - 1.0) * 100)));
        try {
            String bodyJson = objectMapper.writeValueAsString(Map.of(
                    "req_params", Map.of(
                            "text", text,
                            "speaker", useSpeaker,
                            "audio_params", Map.of(
                                    "format", "mp3",
                                    "sample_rate", 24000,
                                    "speech_rate", speechRate
                            )
                    )
            ));
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(V3_TTS_URL))
                    .header("X-Api-Key", apiKey)
                    .header("X-Api-Resource-Id", "seed-tts-2.0")
                    .header("X-Api-Request-Id", requestId)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                byte[] errBody = response.body().readAllBytes();
                String errStr = errBody != null ? new String(errBody) : "no body";
                log.error("V3 流式 TTS 错误 HTTP {} body={}", response.statusCode(),
                        errStr.length() > 200 ? errStr.substring(0, 200) : errStr);
                return false;
            }
            // 读流，按 "{" 边界切分 NDJSON 分片，每解析到一个 data/audio 字段就回调
            java.io.ByteArrayOutputStream audioBuf = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int totalChunks = 0;
            try (var in = response.body()) {
                int n;
                while ((n = in.read(buffer)) != -1) {
                    audioBuf.write(buffer, 0, n);
                    // 尝试解析已读到的完整 JSON 分片
                    while (true) {
                        byte[] cur = audioBuf.toByteArray();
                        int startIdx = -1;
                        for (int i = 0; i < cur.length; i++) {
                            if (cur[i] == '{') { startIdx = i; break; }
                        }
                        if (startIdx < 0) break;
                        // 找到从 startIdx 开始的完整 JSON（最简匹配，不处理嵌套大括号在字符串内的情况）
                        int depth = 0; int endIdx = -1; boolean inStr = false; char prev = 0;
                        for (int i = startIdx; i < cur.length; i++) {
                            char c = (char) cur[i];
                            if (inStr) {
                                if (c == '"' && prev != '\\') inStr = false;
                            } else {
                                if (c == '"') inStr = true;
                                else if (c == '{') depth++;
                                else if (c == '}') {
                                    depth--;
                                    if (depth == 0) { endIdx = i; break; }
                                }
                            }
                            prev = c;
                        }
                        if (endIdx < 0) break; // 还不完整，继续读
                        String chunk = new String(cur, startIdx, endIdx - startIdx + 1);
                        try {
                            JsonNode node = objectMapper.readTree(chunk);
                            String audio = node.path("data").asText("");
                            if (audio.isBlank()) audio = node.path("audio").asText("");
                            if (!audio.isBlank()) {
                                byte[] pcm = Base64.getDecoder().decode(audio);
                                chunkConsumer.accept(pcm);
                                totalChunks++;
                            }
                        } catch (Exception ignored) {}
                        // 移除已消费的部分
                        audioBuf.reset();
                        if (endIdx + 1 < cur.length) {
                            audioBuf.write(cur, endIdx + 1, cur.length - endIdx - 1);
                        }
                    }
                }
            }
            // 处理剩余
            byte[] tail = audioBuf.toByteArray();
            if (tail.length > 0) {
                String tailStr = new String(tail).trim();
                for (String chunk : tailStr.split("(?=\\{")) {
                    if (chunk.isBlank()) continue;
                    try {
                        JsonNode node = objectMapper.readTree(chunk);
                        String audio = node.path("data").asText("");
                        if (audio.isBlank()) audio = node.path("audio").asText("");
                        if (!audio.isBlank()) {
                            chunkConsumer.accept(Base64.getDecoder().decode(audio));
                            totalChunks++;
                        }
                    } catch (Exception ignored) {}
                }
            }
            log.info("V3 流式 TTS 完成，共 {} 个分片", totalChunks);
            return totalChunks > 0;
        } catch (Exception e) {
            log.error("V3 流式 TTS 失败: {}", e.getMessage());
            return false;
        }
    }

    public String getDefaultVoice() {
        return defaultVoice;
    }
}
