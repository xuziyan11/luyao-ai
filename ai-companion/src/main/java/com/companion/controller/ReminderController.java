package com.companion.controller;

import com.companion.service.ReminderService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 定时主动陪伴提醒的设置接口：预设（早安/晚安/吃饭等）+ 自定义时间 + 纪念日。
 * 提醒内容在到点后由 ReminderService 定时扫描触发，仅网页在线时推送。
 */
@RestController
@RequestMapping("/api/reminders")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping
    public Map<String, Object> list(HttpSession session) {
        Long accountId = accountId(session);
        if (accountId == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        return Map.of("ok", true, "reminders", reminderService.list(accountId));
    }

    @PostMapping
    public Map<String, Object> add(@RequestParam String type,
                                   @RequestParam String title,
                                   @RequestParam(required = false) String timeOfDay,
                                   @RequestParam(required = false) String monthDay,
                                   @RequestParam(required = false) String hint,
                                   HttpSession session) {
        Long accountId = accountId(session);
        if (accountId == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        try {
            long id = reminderService.add(accountId, type, title, timeOfDay, monthDay, hint);
            return Map.of("ok", true, "id", id);
        } catch (IllegalArgumentException e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable long id, @RequestParam boolean enabled,
                                      HttpSession session) {
        Long accountId = accountId(session);
        if (accountId == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        reminderService.setEnabled(id, accountId, enabled);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id, HttpSession session) {
        Long accountId = accountId(session);
        if (accountId == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        reminderService.delete(id, accountId);
        return Map.of("ok", true);
    }

    private Long accountId(HttpSession session) {
        return session == null ? null : (Long) session.getAttribute("accountId");
    }
}
