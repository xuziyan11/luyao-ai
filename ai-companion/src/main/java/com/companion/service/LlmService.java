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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * DeepSeek API 调用封装。
 * <p>
 * 端点：https://api.deepseek.com/v1/chat/completions
 * 模型：deepseek-chat
 * 使用 OkHttp 发送 POST 请求，Jackson 解析 JSON。
 */
@Slf4j
@Service
public class LlmService {

    /** 单条对话消息（role: system/user/assistant） */
    public record ChatMessage(String role, String content) {
    }

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    @Value("${companion.deepseek.api-key}")
    private String apiKey;

    @Value("${companion.deepseek.base-url}")
    private String baseUrl;

    @Value("${companion.deepseek.model}")
    private String model;

    @Value("${companion.deepseek.temperature:0.85}")
    private double temperature;

    @Value("${companion.deepseek.max-tokens:500}")
    private int maxTokens;

    @Value("${companion.deepseek.timeout-seconds:60}")
    private int timeoutSeconds;

    private final ObjectMapper mapper = new ObjectMapper();

    private final ConfigService configService;
    private final ApiLogService apiLogService;

    public LlmService(ConfigService configService, ApiLogService apiLogService) {
        this.configService = configService;
        this.apiLogService = apiLogService;
    }

    private OkHttpClient httpClient;

    private OkHttpClient client() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }

    // ---------- 动态参数：管理后台可在线调节，即时生效，yml 值为兜底默认 ----------

    private double cfgTemperature() {
        return configService.getDouble("deepseek.temperature", temperature);
    }

    private double cfgTopP() {
        return configService.getDouble("deepseek.top_p", 1.0);
    }

    private int cfgMaxTokens() {
        return configService.getInt("deepseek.max_tokens", maxTokens);
    }

    /**
     * 用默认温度与最大 token 数调用 DeepSeek。
     *
     * @param messages 完整消息列表（含 system prompt 在首位）
     * @return assistant 回复的纯文本（可能含 [affinity:+N] 与 ||| 标记）
     */
    public String chat(java.util.List<ChatMessage> messages) {
        return chat(messages, null);
    }

    public String chat(java.util.List<ChatMessage> messages, String sessionId) {
        return chat(messages, cfgTemperature(), cfgMaxTokens(), sessionId);
    }

    /**
     * 调用 DeepSeek Chat Completions 接口。
     */
    public String chat(java.util.List<ChatMessage> messages, double temp, int maxTok) {
        return chat(messages, temp, maxTok, null);
    }

    public String chat(java.util.List<ChatMessage> messages, double temp, int maxTok, String sessionId) {
        long startedAt = System.currentTimeMillis();
        try {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("DeepSeek API Key 未配置，请在环境变量 DEEPSEEK_API_KEY 中设置");
            }

            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temp);
            body.put("top_p", cfgTopP());
            body.put("max_tokens", maxTok);
            ArrayNode arr = body.putArray("messages");
            for (ChatMessage m : messages) {
                ObjectNode msg = arr.addObject();
                msg.put("role", m.role());
                msg.put("content", m.content());
            }

            Request request = new Request.Builder()
                    .url(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            try (Response response = client().newCall(request).execute()) {
                String respStr = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("DeepSeek 调用失败 HTTP {} : {}", response.code(), respStr);
                    apiLogService.log(ApiLogService.TYPE_DEEPSEEK, sessionId, false,
                            "HTTP " + response.code(), elapsed(startedAt));
                    throw new RuntimeException("DeepSeek 调用失败: HTTP " + response.code());
                }
                JsonNode root = mapper.readTree(respStr);
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                JsonNode usage = root.path("usage");
                apiLogService.log(ApiLogService.TYPE_DEEPSEEK, sessionId, true, null,
                        elapsed(startedAt), usage.path("prompt_tokens").asInt(0),
                        usage.path("completion_tokens").asInt(0));
                if (content.isMissingNode() || content.asText().isBlank()) {
                    log.warn("DeepSeek 返回空内容: {}", respStr);
                    return "（路瑶走神了，再说一次嘛）";
                }
                return content.asText();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 DeepSeek 异常", e);
            apiLogService.log(ApiLogService.TYPE_DEEPSEEK, sessionId, false,
                    e.getMessage(), elapsed(startedAt));
            throw new RuntimeException("调用 DeepSeek 异常: " + e.getMessage(), e);
        }
    }

    private static long elapsed(long startedAt) {
        return System.currentTimeMillis() - startedAt;
    }

    /**
     * 流式调用 DeepSeek，逐块回调 delta 内容。
     *
     * @param messages      完整消息列表（含 system prompt 在首位）
     * @param chunkCallback 每个 delta 内容块的回调
     * @return 累积的完整回复文本（含 [affinity:+N] 与 ||| 标记）
     */
    public String chatStream(java.util.List<ChatMessage> messages,
                             Consumer<String> chunkCallback) {
        return chatStream(messages, cfgTemperature(), cfgMaxTokens(), null, chunkCallback);
    }

    public String chatStream(java.util.List<ChatMessage> messages, String sessionId,
                             Consumer<String> chunkCallback) {
        return chatStream(messages, cfgTemperature(), cfgMaxTokens(), sessionId, chunkCallback);
    }

    /**
     * 流式调用 DeepSeek Chat Completions 接口（stream: true）。
     * 通过 SSE 逐行读取 delta content，实时回调。
     * 带 stream_options.include_usage，最后一个数据块携带 token 用量用于统计。
     */
    public String chatStream(java.util.List<ChatMessage> messages, double temp, int maxTok,
                             String sessionId, Consumer<String> chunkCallback) {
        long startedAt = System.currentTimeMillis();
        int promptTokens = 0;
        int completionTokens = 0;
        try {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("DeepSeek API Key 未配置，请在环境变量 DEEPSEEK_API_KEY 中设置");
            }

            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature", temp);
            body.put("top_p", cfgTopP());
            body.put("max_tokens", maxTok);
            body.put("stream", true);
            ObjectNode streamOptions = body.putObject("stream_options");
            streamOptions.put("include_usage", true);
            ArrayNode arr = body.putArray("messages");
            for (ChatMessage m : messages) {
                ObjectNode msg = arr.addObject();
                msg.put("role", m.role());
                msg.put("content", m.content());
            }

            Request request = new Request.Builder()
                    .url(baseUrl)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(body.toString(), JSON))
                    .build();

            StringBuilder fullReply = new StringBuilder();
            try (Response response = client().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String respStr = response.body() != null ? response.body().string() : "";
                    log.error("DeepSeek 流式调用失败 HTTP {} : {}", response.code(), respStr);
                    apiLogService.log(ApiLogService.TYPE_DEEPSEEK, sessionId, false,
                            "HTTP " + response.code(), elapsed(startedAt));
                    throw new RuntimeException("DeepSeek 调用失败: HTTP " + response.code());
                }
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        if (data.isEmpty()) continue;
                        try {
                            JsonNode chunk = mapper.readTree(data);
                            JsonNode usage = chunk.path("usage");
                            if (!usage.isMissingNode() && usage.isObject()) {
                                promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                                completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                            }
                            String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
                            if (!delta.isEmpty()) {
                                fullReply.append(delta);
                                chunkCallback.accept(delta);
                            }
                        } catch (Exception parseEx) {
                            log.debug("跳过无法解析的SSE行: {}", data);
                        }
                    }
                }
            }

            apiLogService.log(ApiLogService.TYPE_DEEPSEEK, sessionId, true, null,
                    elapsed(startedAt), promptTokens, completionTokens);
            if (fullReply.isEmpty()) {
                log.warn("DeepSeek 流式返回空内容");
                return "（路瑶走神了，再说一次嘛）";
            }
            return fullReply.toString();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 DeepSeek 流式异常", e);
            apiLogService.log(ApiLogService.TYPE_DEEPSEEK, sessionId, false,
                    e.getMessage(), elapsed(startedAt));
            throw new RuntimeException("调用 DeepSeek 异常: " + e.getMessage(), e);
        }
    }

    /** 兼容旧签名：流式调用（无 sessionId 统计）。 */
    public String chatStream(java.util.List<ChatMessage> messages, double temp, int maxTok,
                             Consumer<String> chunkCallback) {
        return chatStream(messages, temp, maxTok, null, chunkCallback);
    }
}
