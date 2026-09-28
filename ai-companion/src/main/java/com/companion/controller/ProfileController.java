package com.companion.controller;

import com.companion.service.AffinityService;
import com.companion.service.GeoService;
import com.companion.service.MemoryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人档案 REST API：为「好感度档案 / 记忆档案 / 我的」页面提供真实数据。
 * <p>
 * sessionId 与 WebSocket 一致，取前端 localStorage 中的 user 标识。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class ProfileController {

    private final AffinityService affinityService;
    private final MemoryService memoryService;
    private final GeoService geoService;

    public ProfileController(AffinityService affinityService, MemoryService memoryService,
                             GeoService geoService) {
        this.affinityService = affinityService;
        this.memoryService = memoryService;
        this.geoService = geoService;
    }

    /**
     * 档案总览：好感度、阶段、累计对话、长期记忆数、深夜聊天次数、加入天数。
     */
    @GetMapping("/profile")
    public Map<String, Object> profile(@RequestParam("user") String user) {
        String sessionId = sanitize(user);
        Map<String, Object> result = new HashMap<>();
        int affinity = affinityService.getAffinity(sessionId);
        result.put("affinity", affinity);
        result.put("stage", "朋友");
        result.put("totalMessages", memoryService.countMessages(sessionId));
        List<Map<String, Object>> memories = memoryService.getLongTermMemoryList(sessionId);
        result.put("memoryCount", memories.size());
        result.put("nightChats", memoryService.countNightMessages(sessionId));
        Long first = memoryService.getFirstMessageTime(sessionId);
        long joinDays = first == null ? 1 : Math.max(1, (System.currentTimeMillis() - first) / 86400000L + 1);
        result.put("joinDays", joinDays);
        result.put("dailyCounts", memoryService.getDailyMessageCounts(sessionId, 7));
        return result;
    }

    /**
     * 长期记忆列表（记忆档案页 + 好感度页「她记住的事」）。
     */
    @GetMapping("/memories")
    public List<Map<String, Object>> memories(@RequestParam("user") String user) {
        return memoryService.getLongTermMemoryList(sanitize(user));
    }

    /**
     * 手动添加一条长期记忆（记忆档案页右下角 + 按钮）。
     */
    @PostMapping("/memories/add")
    public Map<String, Object> addMemory(@RequestParam("fact") String fact,
                                         @RequestParam("user") String user) {
        Map<String, Object> result = new HashMap<>();
        if (fact == null || fact.isBlank() || fact.trim().length() > 200) {
            result.put("success", false);
            result.put("message", "内容为空或过长（最多 200 字）");
            return result;
        }
        memoryService.saveLongTermFact(sanitize(user), fact);
        result.put("success", true);
        return result;
    }

    /**
     * 删除一条长期记忆。
     */
    @PostMapping("/memories/delete")
    public Map<String, Object> deleteMemory(@RequestParam("id") long id,
                                            @RequestParam("user") String user) {
        boolean ok = memoryService.deleteLongTermFact(id, sanitize(user));
        Map<String, Object> result = new HashMap<>();
        result.put("success", ok);
        if (!ok) {
            result.put("message", "记忆不存在或不属于当前用户");
        }
        return result;
    }

    /**
     * 当前访问者所在城市（IP 定位，精确到市一级；内网/无法识别时 city 为 null）。
     */
    @GetMapping("/geo")
    public Map<String, Object> geo(HttpServletRequest request) {
        String city = geoService.cityOf(request);
        Map<String, Object> result = new HashMap<>();
        result.put("city", city);
        return result;
    }

    private String sanitize(String user) {
        if (user == null || user.isBlank()) {
            return "anonymous";
        }
        return user.trim();
    }
}
