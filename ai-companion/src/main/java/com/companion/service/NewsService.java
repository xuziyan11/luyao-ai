package com.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 实时新闻抓取服务：从公开 API 获取近期新闻，筛选暖新闻供 prompt 注入。
 * 缓存 2 小时，避免频繁请求。
 */
@Slf4j
@Service
public class NewsService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private volatile List<String> cachedNews = null;
    private volatile long cacheTime = 0;
    private static final long CACHE_TTL_MS = 2 * 60 * 60 * 1000; // 2小时

    // 暖新闻关键词
    private static final List<String> WARM_KEYWORDS = Arrays.asList(
            "暖", "温暖", "感动", "善", "爱心", "帮", "捐", "志愿", "英雄",
            "见义勇为", "救助", "守护", "关怀", "温情", "善举", "义举",
            "敬老", "孝", "感恩", "希望", "坚强", "励志", "正能量",
            "救", "赠", "献", "关爱", "互助", "好少年", "好市民", "好警察",
            "最美", "暖新闻", "团聚", "重逢", "回家", "团圆"
    );

    // 排除关键词（暴力、惨烈、冲突）
    private static final List<String> EXCLUDE_KEYWORDS = Arrays.asList(
            "死", "杀", "伤", "亡", "暴", "惨", "烈", "冲突", "袭击",
            "爆炸", "枪", "刀", "中毒", "火灾", "地震", "洪水", "台风",
            "腐败", "贪", "骗", "盗", "抢", "毒", "犯罪", "案", "罚",
            "疫情", "确诊", "感染", "隔离", "封控"
    );

    /**
     * 获取暖新闻摘要列表（最多5条）。
     * 外网 API 不稳定，直接返回空列表避免阻塞主流程。
     */
    public List<String> getWarmNews() {
        return List.of();
    }

    private List<String> fetchAndFilter() {
        List<String> allNews = new ArrayList<>();

        // 来源1：60秒读懂世界（每日新闻摘要 JSON API）
        allNews.addAll(fetchFrom60s());

        // 来源2：知乎日报
        allNews.addAll(fetchFromZhihuDaily());

        if (allNews.isEmpty()) {
            log.warn("NewsService: 所有新闻源都获取失败");
            return List.of();
        }

        // 筛选暖新闻
        List<String> warm = new ArrayList<>();
        for (String item : allNews) {
            if (isWarmNews(item) && !isExcluded(item)) {
                warm.add(item);
            }
        }

        // 暖新闻不够时补充普通新闻
        if (warm.size() < 3) {
            for (String item : allNews) {
                if (!warm.contains(item) && !isExcluded(item)) {
                    warm.add(item);
                }
                if (warm.size() >= 3) break;
            }
        }

        return warm.size() > 3 ? warm.subList(0, 3) : warm;
    }

    /** 60秒读懂世界 API */
    private List<String> fetchFrom60s() {
        List<String> result = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url("https://api.vvhan.com/api/60s")
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    JsonNode json = objectMapper.readTree(body);
                    JsonNode data = json.path("data");
                    if (data.isArray()) {
                        for (JsonNode item : data) {
                            String text = item.asText("").trim();
                            if (!text.isEmpty()) {
                                result.add(text);
                            }
                        }
                    }
                    log.info("NewsService: 60s API 获取 {} 条新闻", result.size());
                }
            }
        } catch (Exception e) {
            log.warn("NewsService: 60s API 获取失败: {}", e.getMessage());
        }
        return result;
    }

    /** 知乎日报 API */
    private List<String> fetchFromZhihuDaily() {
        List<String> result = new ArrayList<>();
        try {
            Request request = new Request.Builder()
                    .url("https://news.topurlify.com/api/weibo/list")
                    .header("User-Agent", "Mozilla/5.0")
                    .get()
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String body = response.body().string();
                    JsonNode json = objectMapper.readTree(body);
                    JsonNode data = json.path("data");
                    if (data.isArray()) {
                        for (JsonNode item : data) {
                            String text = item.path("title").asText("").trim();
                            if (!text.isEmpty() && text.length() > 8) {
                                result.add(text);
                            }
                        }
                    }
                    log.info("NewsService: 微博热搜获取 {} 条", result.size());
                }
            }
        } catch (Exception e) {
            log.warn("NewsService: 微博热搜获取失败: {}", e.getMessage());
        }
        return result;
    }

    private boolean isWarmNews(String text) {
        for (String kw : WARM_KEYWORDS) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    private boolean isExcluded(String text) {
        for (String kw : EXCLUDE_KEYWORDS) {
            if (text.contains(kw)) return true;
        }
        return false;
    }
}
