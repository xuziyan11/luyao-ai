package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态配置服务：键值对存 app_config 表，内存缓存 5 秒。
 * 管理后台修改后立即写库并刷新缓存，业务侧读取时最多 5 秒内生效，无需改 yml、无需重启。
 * <p>
 * 约定键：
 * prompt.custom_persona   后台定制人设/规则（追加进 system prompt）
 * deepseek.temperature    模型温度（覆盖 yml）
 * deepseek.top_p          模型 top_p
 * deepseek.max_tokens     最大输出 token
 * tts.default_voice       TTS 默认音色
 * tts.speed               TTS 语速倍率（预留给前端/合成参数）
 * tts.presets             TTS 预设列表（JSON 数组）
 * alert.fail_rate_pct     接口失败率告警阈值（百分比，默认 20）
 */
@Slf4j
@Service
public class ConfigService {

    private static final long CACHE_TTL_MS = 5000L;

    private final JdbcTemplate jdbc;
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private volatile long cacheLoadedAt = 0L;

    public ConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取字符串配置，未设置返回 defaultValue。 */
    public String get(String key, String defaultValue) {
        ensureCache();
        String v = cache.get(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    public double getDouble(String key, double defaultValue) {
        String v = get(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public int getInt(String key, int defaultValue) {
        String v = get(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 写入配置并立即刷新缓存（即时生效）。 */
    public void set(String key, String value) {
        jdbc.update("INSERT INTO app_config(cfg_key, cfg_value, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                        + "ON DUPLICATE KEY UPDATE cfg_value = VALUES(cfg_value), updated_at = CURRENT_TIMESTAMP",
                key, value == null ? "" : value);
        cache.put(key, value == null ? "" : value);
    }

    /** 全部配置（管理后台展示用）。 */
    public Map<String, String> all() {
        ensureCache();
        return new HashMap<>(cache);
    }

    private void ensureCache() {
        long now = System.currentTimeMillis();
        if (now - cacheLoadedAt < CACHE_TTL_MS) {
            return;
        }
        synchronized (this) {
            if (now - cacheLoadedAt < CACHE_TTL_MS) {
                return;
            }
            try {
                Map<String, String> fresh = new HashMap<>();
                jdbc.query("SELECT cfg_key, cfg_value FROM app_config",
                        rs -> {
                            fresh.put(rs.getString(1), rs.getString(2));
                        });
                cache.clear();
                cache.putAll(fresh);
                cacheLoadedAt = System.currentTimeMillis();
            } catch (Exception e) {
                // 表尚未创建等启动早期场景：沿用旧缓存，不阻断业务
                log.warn("加载 app_config 失败（沿用缓存）: {}", e.getMessage());
                cacheLoadedAt = System.currentTimeMillis();
            }
        }
    }
}
