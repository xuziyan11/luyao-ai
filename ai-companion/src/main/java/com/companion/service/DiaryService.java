package com.companion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 情绪日记：每日最后一次对话后（23:55 定时兜底 + 页面手动生成），
 * 汇总当日聊天，调用大模型整理成简短情绪日记：
 * 日期与天气 / 情绪状态（主导情绪 + 波动曲线）/ 关键事件 / 路遥寄语。
 * 日记存 diary_a{账号id}（匿名会话存 diary 主表），支持按日查询、编辑、导出。
 */
@Slf4j
@Service
public class DiaryService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 当日聊天少于该条数不生成日记（内容太少写不出有意义的日记） */
    private static final int MIN_MESSAGES = 3;

    private final JdbcTemplate jdbc;
    private final LlmService llmService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate http;

    public DiaryService(JdbcTemplate jdbc, LlmService llmService) {
        this.jdbc = jdbc;
        this.llmService = llmService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.http = new RestTemplate(factory);
    }

    // ---------- 查询 ----------

    /** 查询某天的日记；不存在返回 null。 */
    public Map<String, Object> get(String sessionId, String date) {
        String table = AccountTables.diary(sessionId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT diary_date, weather, mood, mood_curve, events, blessing, content, updated_at "
                        + "FROM " + table + " WHERE diary_date = ?", date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 有日记的日期列表（倒序，用于日记页日历标记/翻页）。 */
    public List<String> listDates(String sessionId, int limit) {
        String table = AccountTables.diary(sessionId);
        return jdbc.queryForList(
                "SELECT diary_date FROM " + table + " ORDER BY diary_date DESC LIMIT " + Math.min(limit, 90),
                String.class);
    }

    /** 编辑日记正文 / 关键事件 / 寄语（其他字段不动）。 */
    public boolean update(String sessionId, String date, String content, String events, String blessing) {
        String table = AccountTables.diary(sessionId);
        int n = jdbc.update("UPDATE " + table + " SET content = ?, events = ?, blessing = ? WHERE diary_date = ?",
                content, events, blessing, date);
        return n > 0;
    }

    // ---------- 生成 ----------

    /**
     * 生成（或重新生成）某天的日记。当日聊天太少返回 null。
     * 无坐标时由后端按请求真实客户端 IP 兜底定位（夜间定时任务场景无请求上下文，city 为 null）。
     */
    public Map<String, Object> generate(String sessionId, String date) {
        return generate(sessionId, date, null, null, null);
    }

    /**
     * 同上；lat/lon 为浏览器定位的城市级坐标（网站定位，精确到城市），供日记天气使用；
     * 定时任务或用户拒绝定位时传 null，回退服务端按请求真实 IP 解析到的城市。
     */
    public Map<String, Object> generate(String sessionId, String date, Double lat, Double lon) {
        return generate(sessionId, date, lat, lon, null);
    }

    /**
     * 完整生成入口。city 为服务端按请求真实客户端 IP 离线解析到的用户城市（无需授权），
     * 当浏览器未提供坐标（HTTP 访问/拒绝定位/不支持）时作为天气定位兜底，
     * 保证天气始终对应用户所在城市、且全中文。
     */
    public Map<String, Object> generate(String sessionId, String date, Double lat, Double lon, String city) {
        String chatTable = AccountTables.chatHistory(sessionId);
        List<Map<String, Object>> msgs = jdbc.queryForList(
                "SELECT role, content, created_at FROM " + chatTable
                        + " WHERE DATE(created_at) = ? ORDER BY created_at LIMIT 80", date);
        if (msgs.size() < MIN_MESSAGES) {
            return null;
        }

        // 拼当日对话（截断控制 token）
        StringBuilder convo = new StringBuilder();
        for (Map<String, Object> m : msgs) {
            String role = "user".equals(m.get("role")) ? "我" : "路瑶";
            String content = String.valueOf(m.get("content")).replaceAll("\\[affinity:[^\\]]*\\]", "");
            if (content.length() > 60) {
                content = content.substring(0, 60) + "…";
            }
            String time = String.valueOf(m.get("created_at"));
            convo.append('[').append(time.length() >= 16 ? time.substring(11, 16) : time).append("] ")
                    .append(role).append("：").append(content).append('\n');
            if (convo.length() > 3600) {
                convo.append("…（后略）");
                break;
            }
        }

        String sys = """
                你是日记整理助手。根据「我」和 AI 伴侣「路瑶」某一天（%s）的聊天记录，整理一篇简短情绪日记。
                只输出 JSON（不要 markdown 代码块），字段：
                {
                  "mood": "当日主导情绪，2-4个字，如：开心 / 平静 / 低落 / 焦虑 / 期待",
                  "moodCurve": "从早到晚的心情变化轨迹，25字内",
                  "events": ["当天聊到的重要事情或值得记录的瞬间，1-4条，每条15字内"],
                  "blessing": "以路瑶的身份写给对方的一句温暖寄语，作为日记结尾祝福，30字内",
                  "content": "完整日记正文，以「我」的第一人称写，120-180字，口语化、有温度"
                }
                """.formatted(date);
        String raw = llmService.chat(List.of(
                new LlmService.ChatMessage("system", sys),
                new LlmService.ChatMessage("user", "当日聊天记录：\n" + convo)), 0.5, 600, sessionId);

        String mood = "", moodCurve = "", blessing = "", content = "";
        List<String> events = new ArrayList<>();
        try {
            String json = raw == null ? "" : raw.replaceAll("(?s)```json|```", "").trim();
            int s = json.indexOf('{');
            int e = json.lastIndexOf('}');
            JsonNode node = mapper.readTree(json.substring(s, e + 1));
            mood = node.path("mood").asText("");
            moodCurve = node.path("moodCurve").asText("");
            blessing = node.path("blessing").asText("");
            content = node.path("content").asText("");
            for (JsonNode ev : node.path("events")) {
                events.add(ev.asText());
            }
        } catch (Exception ex) {
            log.warn("日记 JSON 解析失败，使用原文兜底: {}", ex.getMessage());
            content = raw == null ? "" : raw.trim();
        }
        if (content.isBlank()) {
            return null;
        }

        String eventsJson;
        try {
            eventsJson = mapper.writeValueAsString(events);
        } catch (Exception ex) {
            eventsJson = "[]";
        }
        String weather = fetchWeather(lat, lon, city);
        String table = AccountTables.diary(sessionId);
        jdbc.update("INSERT INTO " + table
                        + " (diary_date, weather, mood, mood_curve, events, blessing, content) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE weather = VALUES(weather), mood = VALUES(mood), "
                        + "mood_curve = VALUES(mood_curve), events = VALUES(events), "
                        + "blessing = VALUES(blessing), content = VALUES(content)",
                date, weather, mood, moodCurve, eventsJson, blessing, content);
        log.info("日记已生成: sessionId={}, date={}, mood={}", sessionId, date, mood);
        return get(sessionId, date);
    }

    /** 每晚 23:55 兜底：为有当日聊天但还没日记的账号生成日记。 */
    @Scheduled(cron = "0 55 23 * * ?")
    public void nightlyGenerate() {
        String today = LocalDate.now().format(DAY);
        List<Map<String, Object>> accounts = jdbc.queryForList("SELECT id, chat_token FROM account");
        for (Map<String, Object> acc : accounts) {
            try {
                long accountId = ((Number) acc.get("id")).longValue();
                String sessionId = "a" + accountId + "_" + acc.get("chat_token");
                if (get(sessionId, today) != null) {
                    continue;
                }
                generate(sessionId, today);
            } catch (Exception e) {
                log.warn("夜间日记生成失败: {}", e.getMessage());
            }
        }
    }

    /** WMO 天气代码 → 中文描述（Open-Meteo 返回代码，自行映射保证全中文）。 */
    private static final Map<Integer, String> WMO_DESC = new HashMap<>();
    static {
        WMO_DESC.put(0, "晴");
        WMO_DESC.put(1, "晴间多云");
        WMO_DESC.put(2, "多云");
        WMO_DESC.put(3, "阴");
        WMO_DESC.put(45, "雾");
        WMO_DESC.put(48, "雾凇");
        WMO_DESC.put(51, "小毛毛雨");
        WMO_DESC.put(53, "毛毛雨");
        WMO_DESC.put(55, "大毛毛雨");
        WMO_DESC.put(56, "冻毛毛雨");
        WMO_DESC.put(57, "冻毛毛雨");
        WMO_DESC.put(61, "小雨");
        WMO_DESC.put(63, "中雨");
        WMO_DESC.put(65, "大雨");
        WMO_DESC.put(66, "冻雨");
        WMO_DESC.put(67, "冻雨");
        WMO_DESC.put(71, "小雪");
        WMO_DESC.put(73, "中雪");
        WMO_DESC.put(75, "大雪");
        WMO_DESC.put(77, "雪粒");
        WMO_DESC.put(80, "阵雨");
        WMO_DESC.put(81, "阵雨");
        WMO_DESC.put(82, "强阵雨");
        WMO_DESC.put(85, "阵雪");
        WMO_DESC.put(86, "强阵雪");
        WMO_DESC.put(95, "雷雨");
        WMO_DESC.put(96, "雷雨伴冰雹");
        WMO_DESC.put(99, "强雷雨伴冰雹");
    }

    /**
     * 当地天气（全中文）。lat/lon 为浏览器定位的城市级坐标时：Open-Meteo 取温度+天气代码（免 key，
     * 自行映射为中文），BigDataCloud 反向地理编码取中文城市名，拼接成「城市 多云 29°C」。
     * 无坐标（HTTP 访问/拒绝定位/不支持）时：用 fallbackCity（服务端按请求真实客户端 IP 离线解析到的
     * 用户城市，无需授权）经 Open-Meteo 地理编码取经纬度后查中文天气，仍是用户所在城市。
     * 始终只精确到城市级；实在无法定位返回 null，日记里显示为「—」。已彻底弃用 wttr.in。
     */
    private String fetchWeather(Double lat, Double lon, String fallbackCity) {
        try {
            if (lat != null && lon != null) {
                String city = reverseGeocodeCity(lat, lon);
                String condTemp = openMeteoWeather(lat, lon);
                if (city != null && condTemp != null) return city + " " + condTemp;
                if (condTemp != null) return condTemp;
                if (city != null) return city;
                return null;
            }
            // 无浏览器坐标：用服务端按请求真实 IP 解析到的用户城市（离线库，无需授权）→ 中文天气
            if (fallbackCity != null && !fallbackCity.isBlank()) {
                double[] coord = geocodeCity(fallbackCity);
                if (coord != null) {
                    String condTemp = openMeteoWeather(coord[0], coord[1]);
                    if (condTemp != null) return fallbackCity + " " + condTemp;
                }
            }
            return null; // 实在无法定位 → 日记显示「—」
        } catch (Exception e) {
            log.warn("天气获取失败: {}", e.getMessage());
        }
        return null;
    }

    /** 城市名 → 经纬度（Open-Meteo 免费地理编码，无需 key，language=zh）。自动去「市/省」等后缀以匹配。 */
    private double[] geocodeCity(String city) {
        for (String name : stripAdminSuffix(city)) {
            try {
                String url = "https://geocoding-api.open-meteo.com/v1/search?name="
                        + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&language=zh&count=1";
                String r = http.getForObject(url, String.class);
                if (r != null && !r.isBlank()) {
                    JsonNode results = mapper.readTree(r).path("results");
                    if (results.isArray() && results.size() > 0) {
                        JsonNode first = results.get(0);
                        double la = first.path("latitude").asDouble();
                        double lo = first.path("longitude").asDouble();
                        if (la != 0 || lo != 0) return new double[]{la, lo};
                    }
                }
            } catch (Exception e) {
                log.warn("城市地理编码失败 {}: {}", name, e.getMessage());
            }
        }
        return null;
    }

    /** 去除城市名尾部行政区划后缀；优先原名、再尝试去后缀，提升地理编码命中率。 */
    private String[] stripAdminSuffix(String city) {
        if (city == null || city.isEmpty()) return new String[0];
        String stripped = city.replaceAll("(?:特别行政区|自治区|自治州|地区|盟|市|县|区|省)$", "");
        if (!stripped.equals(city) && !stripped.isEmpty()) {
            return new String[]{city, stripped};
        }
        return new String[]{city};
    }

    /** Open-Meteo 免 key：返回「中文天气 温度°C」，如「多云 29°C」。 */
    private String openMeteoWeather(double lat, double lon) {
        try {
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat
                    + "&longitude=" + lon + "&current=temperature_2m,weather_code";
            String r = http.getForObject(url, String.class);
            if (r == null || r.isBlank()) return null;
            JsonNode node = mapper.readTree(r).path("current");
            double temp = node.path("temperature_2m").asDouble();
            int code = node.path("weather_code").asInt();
            return WMO_DESC.getOrDefault(code, "未知") + " " + Math.round(temp) + "°C";
        } catch (Exception e) {
            log.warn("Open-Meteo 天气失败: {}", e.getMessage());
            return null;
        }
    }

    /** 反向地理编码取中文城市名（BigDataCloud 免费接口，无需 key，localityLanguage=zh 返回中文）。 */
    private String reverseGeocodeCity(double lat, double lon) {
        try {
            String url = "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=" + lat
                    + "&longitude=" + lon + "&localityLanguage=zh";
            String r = http.getForObject(url, String.class);
            if (r != null && !r.isBlank()) {
                JsonNode node = mapper.readTree(r);
                String city = node.path("city").asText("");
                if (city.isBlank()) city = node.path("locality").asText("");
                if (city.isBlank()) city = node.path("principalSubdivision").asText("");
                if (!city.isBlank() && city.length() <= 20) return city;
            }
        } catch (Exception e) {
            log.warn("反向地理编码失败: {}", e.getMessage());
        }
        return null;
    }
}
