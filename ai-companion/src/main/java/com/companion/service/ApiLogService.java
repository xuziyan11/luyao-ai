package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 外部 API 调用日志：DeepSeek / TTS 等调用的延迟、Token 用量、成败异步落 api_log 表，
 * 供管理后台的日志监控、失败率告警、Token 统计与延迟分位数分析使用。
 */
@Slf4j
@Service
public class ApiLogService {

    public static final String TYPE_DEEPSEEK = "deepseek";
    public static final String TYPE_TTS = "tts";
    public static final String TYPE_TTS_CLONE = "tts_clone";

    private final JdbcTemplate jdbc;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "api-log-writer");
        t.setDaemon(true);
        return t;
    });

    public ApiLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记录一次调用（异步，不阻塞业务线程）。 */
    public void log(String apiType, String sessionId, boolean ok, String error,
                    long latencyMs, int promptTokens, int completionTokens) {
        String err = error == null ? null : (error.length() > 480 ? error.substring(0, 480) : error);
        writer.submit(() -> {
            try {
                jdbc.update("INSERT INTO api_log(api_type, session_id, ok, error, latency_ms, "
                                + "prompt_tokens, completion_tokens, total_tokens) VALUES (?,?,?,?,?,?,?,?)",
                        apiType, sessionId, ok, err, latencyMs,
                        promptTokens, completionTokens, promptTokens + completionTokens);
            } catch (Exception e) {
                log.warn("api_log 写入失败: {}", e.getMessage());
            }
        });
    }

    public void log(String apiType, String sessionId, boolean ok, String error, long latencyMs) {
        log(apiType, sessionId, ok, error, latencyMs, 0, 0);
    }
}
