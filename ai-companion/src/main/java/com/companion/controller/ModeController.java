package com.companion.controller;

import com.companion.service.SceneModeService;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 场景模式一键切换：当前模式 + 预设列表查询、切换。
 * 模式存 account.scene_mode，切换后下一条消息即按新模式的人设与 TTS 语速生效。
 */
@RestController
@RequestMapping("/api/mode")
public class ModeController {

    private final SceneModeService sceneModeService;

    public ModeController(SceneModeService sceneModeService) {
        this.sceneModeService = sceneModeService;
    }

    /** 当前模式 + 全部预设模式。 */
    @GetMapping
    public Map<String, Object> current(HttpSession session) {
        Long accountId = session == null ? null : (Long) session.getAttribute("accountId");
        String current = accountId == null ? "daily" : sceneModeService.currentMode(accountId);
        SceneModeService.SceneMode cur = sceneModeService.byKey(current);
        return Map.of(
                "ok", true,
                "current", current,
                "currentName", cur.name(),
                "currentIcon", cur.icon(),
                "ttsSpeed", cur.ttsSpeed(),
                "modes", sceneModeService.allModes());
    }

    /** 一键切换场景模式。 */
    @PostMapping("/switch")
    public Map<String, Object> switchMode(@RequestParam String mode, HttpSession session) {
        Long accountId = session == null ? null : (Long) session.getAttribute("accountId");
        if (accountId == null) {
            return Map.of("ok", false, "message", "请先登录");
        }
        if (!sceneModeService.isValid(mode)) {
            return Map.of("ok", false, "message", "未知场景模式");
        }
        sceneModeService.switchMode(accountId, mode);
        SceneModeService.SceneMode cur = sceneModeService.byKey(mode);
        return Map.of(
                "ok", true,
                "current", cur.key(),
                "currentName", cur.name(),
                "currentIcon", cur.icon(),
                "ttsSpeed", cur.ttsSpeed(),
                "message", "已切换到「" + cur.name() + "」");
    }
}
