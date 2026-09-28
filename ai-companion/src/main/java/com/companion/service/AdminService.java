package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理后台数据服务：用户管理（列表/禁用/恢复码/活跃度）、对话与接口日志、
 * Token 用量与延迟统计、异常告警、系统资源指标（含本地 Ollama/llama 进程）。
 * 所有跨账号统计均按账号独立表（chat_history_a{id} 等）逐一聚合，不破坏数据隔离。
 */
@Slf4j
@Service
public class AdminService {

    private final JdbcTemplate jdbc;
    private final ConfigService configService;

    /** 管理员签发的一次性恢复码：phone -> [code, expireAtMillis] */
    private final Map<String, String[]> recoveryCodes = new ConcurrentHashMap<>();

    public AdminService(JdbcTemplate jdbc, ConfigService configService) {
        this.jdbc = jdbc;
        this.configService = configService;
    }

    // ---------- 用户管理 ----------

    /** 全部账号列表 + 每账号活跃度统计（对话次数/活跃天数/使用时长/好感度/记忆数）。 */
    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> accounts = jdbc.query(
                "SELECT id, login_type, phone, wechat_openid, nickname, created_at, last_login_at, "
                        + "COALESCE(status,'active') AS status FROM account ORDER BY id",
                (rs, n) -> {
                    Map<String, Object> a = new HashMap<>();
                    a.put("id", rs.getLong("id"));
                    a.put("loginType", rs.getString("login_type"));
                    a.put("nickname", rs.getString("nickname"));
                    a.put("phone", AccountService.maskPhone(rs.getString("phone")));
                    a.put("createdAt", rs.getTimestamp("created_at"));
                    a.put("lastLoginAt", rs.getTimestamp("last_login_at"));
                    a.put("status", rs.getString("status"));
                    return a;
                });
        for (Map<String, Object> a : accounts) {
            long id = (Long) a.get("id");
            a.putAll(accountStats(id));
        }
        return accounts;
    }

    /** 单账号活跃度：从该账号独立表聚合。表不存在时返回零值，不影响其他账号。 */
    private Map<String, Object> accountStats(long accountId) {
        Map<String, Object> stats = new HashMap<>();
        String chatTable = "chat_history_a" + accountId;
        String memTable = "long_term_memory_a" + accountId;
        String affTable = "affinity_a" + accountId;
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT COUNT(*) AS msgs, "
                            + "SUM(role = 'user') AS user_msgs, "
                            + "COUNT(DISTINCT DATE(created_at)) AS active_days, "
                            + "MAX(created_at) AS last_active "
                            + "FROM " + chatTable);
            stats.put("messageCount", ((Number) row.get("msgs")).longValue());
            stats.put("chatCount", row.get("user_msgs") == null ? 0L : ((Number) row.get("user_msgs")).longValue());
            stats.put("activeDays", ((Number) row.get("active_days")).longValue());
            stats.put("lastActive", row.get("last_active"));
        } catch (Exception e) {
            stats.put("messageCount", 0L);
            stats.put("chatCount", 0L);
            stats.put("activeDays", 0L);
            stats.put("lastActive", null);
        }
        // 使用时长估算：每天首条到末条消息的跨度累加，每天封顶 60 分钟（排除挂页面不聊天的时段）
        try {
            Long minutes = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(LEAST(dur, 60)),0) FROM ("
                            + "SELECT TIMESTAMPDIFF(MINUTE, MIN(created_at), MAX(created_at)) AS dur "
                            + "FROM " + chatTable + " GROUP BY DATE(created_at)) t",
                    Long.class);
            stats.put("usageMinutes", minutes == null ? 0L : minutes);
        } catch (Exception e) {
            stats.put("usageMinutes", 0L);
        }
        try {
            Long mc = jdbc.queryForObject("SELECT COUNT(*) FROM " + memTable, Long.class);
            stats.put("memoryCount", mc == null ? 0L : mc);
        } catch (Exception e) {
            stats.put("memoryCount", 0L);
        }
        try {
            Integer aff = jdbc.query("SELECT affinity_value FROM " + affTable + " LIMIT 1",
                    rs -> rs.next() ? rs.getInt(1) : 0);
            stats.put("affinity", aff);
        } catch (Exception e) {
            stats.put("affinity", 0);
        }
        return stats;
    }

    /** 禁用/启用账号。 */
    public void setAccountStatus(long accountId, boolean disabled) {
        jdbc.update("UPDATE account SET status = ? WHERE id = ?", disabled ? "disabled" : "active", accountId);
        log.info("管理操作: 账号 {} {}", accountId, disabled ? "已禁用" : "已启用");
    }

    /** 为手机号账号签发一次性恢复码（10 分钟有效），用户忘记验证码时快速恢复登录。 */
    public String issueRecoveryCode(long accountId) {
        List<String> phones = jdbc.query("SELECT phone FROM account WHERE id = ?",
                (rs, n) -> rs.getString(1), accountId);
        if (phones.isEmpty() || phones.get(0) == null || phones.get(0).isBlank()) {
            return null;
        }
        String code = String.valueOf(100000 + (int) (Math.random() * 900000));
        recoveryCodes.put(phones.get(0), new String[]{code, String.valueOf(System.currentTimeMillis() + 600_000L)});
        log.info("管理操作: 为账号 {} 签发恢复码", accountId);
        return code;
    }

    /** 校验一次性恢复码（一次性，验证通过即作废）。 */
    public boolean verifyRecoveryCode(String phone, String code) {
        String[] entry = recoveryCodes.get(phone);
        if (entry == null) {
            return false;
        }
        boolean ok = entry[0].equals(code) && System.currentTimeMillis() < Long.parseLong(entry[1]);
        if (ok) {
            recoveryCodes.remove(phone);
        }
        return ok;
    }

    // ---------- 对话日志 ----------

    /** 某账号的完整对话记录（分页，倒序）。 */
    public Map<String, Object> chatLogs(long accountId, int page, int size) {
        String table = "chat_history_a" + accountId;
        Map<String, Object> result = new HashMap<>();
        try {
            Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT id, role, content, created_at FROM " + table
                            + " ORDER BY id DESC LIMIT ? OFFSET ?",
                    (rs, n) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("id", rs.getLong("id"));
                        m.put("role", rs.getString("role"));
                        m.put("content", rs.getString("content"));
                        m.put("createdAt", rs.getTimestamp("created_at"));
                        return m;
                    }, size, (page - 1) * size);
            result.put("total", total == null ? 0 : total);
            result.put("rows", rows);
        } catch (Exception e) {
            result.put("total", 0);
            result.put("rows", List.of());
        }
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    // ---------- 接口日志 ----------

    /** API 调用日志（可按类型/成败过滤，分页倒序）。 */
    public Map<String, Object> apiLogs(String type, Boolean ok, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            where.append(" AND api_type = ?");
            params.add(type);
        }
        if (ok != null) {
            where.append(" AND ok = ?");
            params.add(ok);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM api_log" + where, Long.class, params.toArray());
        List<Object> rowParams = new ArrayList<>(params);
        rowParams.add(size);
        rowParams.add((page - 1) * size);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id, api_type, session_id, ok, error, latency_ms, prompt_tokens, "
                        + "completion_tokens, total_tokens, created_at FROM api_log" + where
                        + " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("apiType", rs.getString("api_type"));
                    m.put("sessionId", rs.getString("session_id"));
                    m.put("ok", rs.getBoolean("ok"));
                    m.put("error", rs.getString("error"));
                    m.put("latencyMs", rs.getLong("latency_ms"));
                    m.put("promptTokens", rs.getInt("prompt_tokens"));
                    m.put("completionTokens", rs.getInt("completion_tokens"));
                    m.put("totalTokens", rs.getInt("total_tokens"));
                    m.put("createdAt", rs.getTimestamp("created_at"));
                    return m;
                }, rowParams.toArray());
        Map<String, Object> result = new HashMap<>();
        result.put("total", total == null ? 0 : total);
        result.put("rows", rows);
        result.put("page", page);
        result.put("size", size);
        return result;
    }

    // ---------- Token 用量统计 ----------

    /** Token 用量：day=近24小时按小时，week=近7天按天，month=近30天按天。 */
    public Map<String, Object> tokenStats(String range) {
        boolean byHour = "day".equals(range);
        int spanDays = "month".equals(range) ? 30 : ("day".equals(range) ? 1 : 7);
        String groupExpr = byHour ? "DATE_FORMAT(created_at, '%Y-%m-%d %H:00')" : "DATE(created_at)";
        List<Map<String, Object>> series = jdbc.query(
                "SELECT " + groupExpr + " AS bucket, SUM(prompt_tokens) AS prompt, "
                        + "SUM(completion_tokens) AS completion, SUM(total_tokens) AS total, COUNT(*) AS calls "
                        + "FROM api_log WHERE api_type = 'deepseek' "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL " + spanDays + " DAY) "
                        + "GROUP BY bucket ORDER BY bucket",
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("bucket", String.valueOf(rs.getObject("bucket")));
                    m.put("promptTokens", rs.getLong("prompt"));
                    m.put("completionTokens", rs.getLong("completion"));
                    m.put("totalTokens", rs.getLong("total"));
                    m.put("calls", rs.getLong("calls"));
                    return m;
                });
        // 汇总卡片
        Map<String, Object> summary = new HashMap<>();
        for (String label : new String[]{"today", "week", "month"}) {
            int days = "today".equals(label) ? 1 : ("week".equals(label) ? 7 : 30);
            String cond = "today".equals(label)
                    ? "DATE(created_at) = CURDATE()"
                    : "created_at >= DATE_SUB(NOW(), INTERVAL " + days + " DAY)";
            Map<String, Object> agg = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(total_tokens),0) AS tokens, COUNT(*) AS calls FROM api_log "
                            + "WHERE api_type = 'deepseek' AND " + cond);
            summary.put(label + "Tokens", ((Number) agg.get("tokens")).longValue());
            summary.put(label + "Calls", ((Number) agg.get("calls")).longValue());
        }
        // 今日单用户消耗排行（异常检测用）
        List<Map<String, Object>> topUsers = jdbc.query(
                "SELECT session_id, SUM(total_tokens) AS tokens, COUNT(*) AS calls FROM api_log "
                        + "WHERE api_type = 'deepseek' AND DATE(created_at) = CURDATE() AND session_id IS NOT NULL "
                        + "GROUP BY session_id ORDER BY tokens DESC LIMIT 20",
                (rs, n) -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("sessionId", rs.getString("session_id"));
                    m.put("tokens", rs.getLong("tokens"));
                    m.put("calls", rs.getLong("calls"));
                    return m;
                });
        Map<String, Object> result = new HashMap<>();
        result.put("range", range);
        result.put("series", series);
        result.put("summary", summary);
        result.put("topUsers", topUsers);
        return result;
    }

    // ---------- 延迟分位数 ----------

    /** 近 24 小时各类型接口的 P50/P95/P99 延迟。 */
    public List<Map<String, Object>> latencyStats() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String type : new String[]{ApiLogService.TYPE_DEEPSEEK, ApiLogService.TYPE_TTS}) {
            List<Long> latencies = jdbc.query(
                    "SELECT latency_ms FROM api_log WHERE api_type = ? "
                            + "AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY) ORDER BY latency_ms",
                    (rs, n) -> rs.getLong(1), type);
            Map<String, Object> m = new HashMap<>();
            m.put("apiType", type);
            m.put("count", latencies.size());
            m.put("p50", percentile(latencies, 0.50));
            m.put("p95", percentile(latencies, 0.95));
            m.put("p99", percentile(latencies, 0.99));
            result.add(m);
        }
        return result;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    // ---------- 异常告警 ----------

    /** 实时计算的告警列表：失败率超阈值、单用户 Token 消耗异常、P95 延迟过高。 */
    public List<Map<String, Object>> alerts() {
        List<Map<String, Object>> alerts = new ArrayList<>();
        // 1. 近 1 小时接口失败率
        double threshold = configService.getDouble("alert.fail_rate_pct", 20);
        Map<String, Object> fr = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, COALESCE(SUM(ok = 0),0) AS failed FROM api_log "
                        + "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)");
        long total = ((Number) fr.get("total")).longValue();
        long failed = ((Number) fr.get("failed")).longValue();
        double rate = total == 0 ? 0 : failed * 100.0 / total;
        if (total >= 5 && rate > threshold) {
            alerts.add(alert("critical", "接口失败率告警",
                    String.format("近 1 小时 %d 次调用失败 %d 次（%.1f%%），超过阈值 %.0f%%", total, failed, rate, threshold)));
        }
        // 2. 今日单用户 Token 异常（超过人均 3 倍且绝对量可观）
        List<Map<String, Object>> today = jdbc.query(
                "SELECT session_id, SUM(total_tokens) AS tokens FROM api_log "
                        + "WHERE api_type = 'deepseek' AND DATE(created_at) = CURDATE() AND session_id IS NOT NULL "
                        + "GROUP BY session_id",
                (rs, n) -> Map.of("sessionId", (Object) rs.getString(1), "tokens", (Object) rs.getLong(2)));
        if (today.size() >= 2) {
            double avg = today.stream().mapToLong(m -> (Long) m.get("tokens")).average().orElse(0);
            for (Map<String, Object> u : today) {
                long t = (Long) u.get("tokens");
                if (avg > 0 && t > avg * 3 && t > 10000) {
                    alerts.add(alert("warning", "单用户消耗异常",
                            String.format("会话 %s 今日已消耗 %d tokens，为平均值 %.0f 的 %.1f 倍",
                                    u.get("sessionId"), t, avg, t / avg)));
                }
            }
        }
        // 3. P95 延迟过高（DeepSeek 近 24h 超过 30 秒）
        List<Long> lat = jdbc.query(
                "SELECT latency_ms FROM api_log WHERE api_type = 'deepseek' "
                        + "AND created_at >= DATE_SUB(NOW(), INTERVAL 1 DAY) ORDER BY latency_ms",
                (rs, n) -> rs.getLong(1));
        long p95 = percentile(lat, 0.95);
        if (lat.size() >= 5 && p95 > 30000) {
            alerts.add(alert("warning", "模型响应变慢",
                    String.format("DeepSeek 近 24 小时 P95 延迟 %.1f 秒，超过 30 秒", p95 / 1000.0)));
        }
        return alerts;
    }

    private static Map<String, Object> alert(String level, String title, String detail) {
        Map<String, Object> a = new HashMap<>();
        a.put("level", level);
        a.put("title", title);
        a.put("detail", detail);
        a.put("time", System.currentTimeMillis());
        return a;
    }

    // ---------- 系统资源指标 ----------

    /** JVM/系统 CPU 内存 + 本地 Ollama/llama-server 进程占用。 */
    public Map<String, Object> systemMetrics() {
        Map<String, Object> m = new HashMap<>();
        Runtime rt = Runtime.getRuntime();
        m.put("jvmUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        m.put("jvmMaxMb", rt.maxMemory() / 1048576);
        try {
            com.sun.management.OperatingSystemMXBean os =
                    (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            m.put("processCpuPct", Math.round(os.getProcessCpuLoad() * 1000) / 10.0);
            m.put("systemCpuPct", Math.round(os.getCpuLoad() * 1000) / 10.0);
            m.put("systemFreeMemMb", os.getFreeMemorySize() / 1048576);
            m.put("systemTotalMemMb", os.getTotalMemorySize() / 1048576);
        } catch (Exception e) {
            m.put("processCpuPct", -1);
            m.put("systemCpuPct", -1);
        }
        m.put("localModels", localModelProcesses());
        return m;
    }

    /** 扫描本机 Ollama / llama-server 进程的 CPU/内存占用（ps 命令，macOS/Linux 通用）。 */
    private List<Map<String, Object>> localModelProcesses() {
        List<Map<String, Object>> procs = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("ps", "-Ao", "pid=,pcpu=,pmem=,rss=,comm=").start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lower = line.toLowerCase();
                    if (!lower.contains("ollama") && !lower.contains("llama")) {
                        continue;
                    }
                    String[] parts = line.trim().split("\\s+", 5);
                    if (parts.length < 5) {
                        continue;
                    }
                    Map<String, Object> proc = new HashMap<>();
                    proc.put("pid", parts[0]);
                    proc.put("cpuPct", parts[1]);
                    proc.put("memPct", parts[2]);
                    proc.put("rssMb", Long.parseLong(parts[3]) / 1024);
                    proc.put("command", parts[4]);
                    procs.add(proc);
                }
            }
            p.waitFor();
        } catch (Exception e) {
            log.debug("扫描本地模型进程失败: {}", e.getMessage());
        }
        return procs;
    }

    // ---------- 概览 ----------

    /** 仪表盘概览数字。 */
    public Map<String, Object> overview(int onlineUsers) {
        Map<String, Object> m = new HashMap<>();
        Long accounts = jdbc.queryForObject("SELECT COUNT(*) FROM account", Long.class);
        m.put("accountCount", accounts == null ? 0 : accounts);
        m.put("onlineUsers", onlineUsers);
        Map<String, Object> today = jdbc.queryForMap(
                "SELECT COUNT(*) AS calls, COALESCE(SUM(total_tokens),0) AS tokens, "
                        + "COALESCE(SUM(ok = 0),0) AS failed FROM api_log WHERE DATE(created_at) = CURDATE()");
        m.put("todayApiCalls", ((Number) today.get("calls")).longValue());
        m.put("todayTokens", ((Number) today.get("tokens")).longValue());
        m.put("todayFailed", ((Number) today.get("failed")).longValue());
        Map<String, Object> hour = jdbc.queryForMap(
                "SELECT COUNT(*) AS total, COALESCE(SUM(ok = 0),0) AS failed FROM api_log "
                        + "WHERE created_at >= DATE_SUB(NOW(), INTERVAL 1 HOUR)");
        long hourTotal = ((Number) hour.get("total")).longValue();
        long hourFailed = ((Number) hour.get("failed")).longValue();
        m.put("hourFailRate", hourTotal == 0 ? 0 : Math.round(hourFailed * 1000.0 / hourTotal) / 10.0);
        return m;
    }
}
