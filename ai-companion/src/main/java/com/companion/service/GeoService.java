package com.companion.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP 城市级定位（ip2region 离线库，数据文件 ip2region.xdb 约 11MB 随 jar 分发）。
 * 无需外部 API、无需用户授权弹窗；仅用于展示用户所在城市，精确到市一级。
 */
@Slf4j
@Service
public class GeoService {

    /** region 字符串以 | 分隔：国家|区域|省份|城市|ISP */
    private static final int REGION_CITY_INDEX = 3;

    private volatile Searcher searcher;
    private volatile boolean loadFailed = false;

    /** 简单的 IP -> 城市缓存，避免重复解析；超量时整体清空（定位结果几乎不变） */
    private final Map<String, String> cache = new ConcurrentHashMap<>();
    private static final int CACHE_MAX = 20_000;

    /** 解析请求的客户端 IP，返回城市名（如 "深圳市"）；内网/解析失败返回 null。 */
    public String cityOf(HttpServletRequest request) {
        String ip = clientIp(request);
        if (ip == null) {
            return null;
        }
        return cityOfIp(ip);
    }

    /** 解析指定 IP 的城市；仅对公网 IPv4 生效。 */
    public String cityOfIp(String ip) {
        if (isPrivateOrLocal(ip)) {
            return null;
        }
        String cached = cache.get(ip);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String city = resolve(ip);
        if (cache.size() > CACHE_MAX) {
            cache.clear();
        }
        cache.put(ip, city == null ? "" : city);
        return city;
    }

    private synchronized String resolve(String ip) {
        Searcher s = searcher();
        if (s == null) {
            return null;
        }
        try {
            String region = s.search(ip);
            if (region == null || region.isBlank()) {
                return null;
            }
            String[] parts = region.split("\\|");
            String city = parts.length > REGION_CITY_INDEX ? parts[REGION_CITY_INDEX] : "";
            if (city.isBlank() || "0".equals(city)) {
                // 直辖市/境外等场景取省份或国家
                String province = parts.length > 2 ? parts[2] : "";
                city = !province.isBlank() && !"0".equals(province) ? province : parts[0];
            }
            return "0".equals(city) || city.isBlank() ? null : city;
        } catch (Exception e) {
            log.debug("IP 定位失败 {}: {}", ip, e.getMessage());
            return null;
        }
    }

    private Searcher searcher() {
        if (searcher != null || loadFailed) {
            return searcher;
        }
        synchronized (this) {
            if (searcher != null || loadFailed) {
                return searcher;
            }
            try {
                byte[] buf = new ClassPathResource("ip2region.xdb").getInputStream().readAllBytes();
                searcher = Searcher.newWithBuffer(buf);
                log.info("ip2region 离线库已加载（{} MB）", buf.length / 1048576);
            } catch (Exception e) {
                loadFailed = true;
                log.warn("加载 ip2region.xdb 失败，城市定位不可用: {}", e.getMessage());
            }
            return searcher;
        }
    }

    /** 提取真实客户端 IP：优先反向代理头，其次直连地址。 */
    static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            String first = comma > 0 ? xff.substring(0, comma) : xff;
            return first.trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean isPrivateOrLocal(String ip) {
        if (ip == null) return true;
        return ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1")
                || ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")
                || ip.matches("172\\.(1[6-9]|2\\d|3[01])\\..*")
                || ip.startsWith("169.254.") || ip.contains(":");
    }
}
