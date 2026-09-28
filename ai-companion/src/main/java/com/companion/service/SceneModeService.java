package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 场景模式一键切换：整套人设 prompt + TTS 语速一次切换。
 * 四种预设：日常闲聊 / 深夜哄睡 / 倾诉吐槽 / 轻松玩笑。
 * 当前模式存 account.scene_mode（默认 daily），切换即时生效无需重启。
 */
@Slf4j
@Service
public class SceneModeService {

    /** 模式定义。promptSegment 会追加到 system prompt 末尾；ttsSpeed 供前端 TTS 请求使用。 */
    public record SceneMode(String key, String name, String icon, String desc,
                            String promptSegment, double ttsSpeed) {
    }

    private static final List<SceneMode> MODES = List.of(
            new SceneMode("daily", "日常闲聊", "☀️", "轻松自然，无话不谈",
                    """
                    
                    【当前场景模式：日常闲聊】
                    - 状态放松自然，像平时一样聊日常、分享小事、开玩笑都可以。
                    - 话题不设限，跟着对方的节奏走，轻松最重要。
                    """,
                    1.0),
            new SceneMode("sleep", "深夜哄睡", "🌙", "语气舒缓语速慢，陪你安心入睡",
                    """
                    
                    【当前场景模式：深夜哄睡】
                    - 对方准备睡觉了，语气放轻放缓，像耳语一样温柔。
                    - 内容以安抚、陪伴、助眠为主：可以轻声数数、描述安静的画面、讲很短很柔的晚安话。
                    - 不聊兴奋的话题，不抛需要思考的问题，不制造情绪波动。
                    - 句子更短更软，结尾自然带上晚安的意思，但不要催对方立刻消失。
                    """,
                    0.85),
            new SceneMode("vent", "倾诉吐槽", "🫂", "耐心倾听，共情优先，不评判",
                    """
                    
                    【当前场景模式：倾诉吐槽】
                    - 对方现在需要的是被听见，不是被教育。共情优先，先接住情绪。
                    - 不评判、不说教、不分析对错、不给"你应该"式建议。
                    - 多用"我懂""这也太委屈了""换我我也气"这类站在对方一边的话。
                    - 对方没问建议就不给建议，只陪着吐槽、顺着情绪走。
                    """,
                    0.95),
            new SceneMode("joke", "轻松玩笑", "😄", "幽默俏皮，活跃气氛",
                    """
                    
                    【当前场景模式：轻松玩笑】
                    - 气氛组上线：幽默、俏皮、会接梗，适度自黑和调侃（不伤人）。
                    - 可以玩文字游戏、夸张比喻、假装吃醋，逗对方开心是第一目标。
                    - 保持分寸，玩笑不碰对方的真实痛处。
                    """,
                    1.05)
    );

    private static final String DEFAULT_MODE = "daily";

    private final JdbcTemplate jdbc;

    public SceneModeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 全部预设模式（含说明，供前端渲染切换面板）。 */
    public List<Map<String, Object>> allModes() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SceneMode m : MODES) {
            list.add(Map.of(
                    "key", m.key(),
                    "name", m.name(),
                    "icon", m.icon(),
                    "desc", m.desc(),
                    "ttsSpeed", m.ttsSpeed()));
        }
        return list;
    }

    /** 账号当前模式 key（查询失败/未设置 → daily）。 */
    public String currentMode(long accountId) {
        try {
            String mode = jdbc.query("SELECT scene_mode FROM account WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : DEFAULT_MODE, accountId);
            return isValid(mode) ? mode : DEFAULT_MODE;
        } catch (Exception e) {
            return DEFAULT_MODE;
        }
    }

    public void switchMode(long accountId, String modeKey) {
        if (!isValid(modeKey)) {
            throw new IllegalArgumentException("未知场景模式: " + modeKey);
        }
        jdbc.update("UPDATE account SET scene_mode = ? WHERE id = ?", modeKey, accountId);
        log.info("场景模式切换: accountId={}, mode={}", accountId, modeKey);
    }

    /** 模式对应的 system prompt 追加段。 */
    public String promptSegment(String modeKey) {
        return byKey(modeKey).promptSegment();
    }

    public SceneMode byKey(String modeKey) {
        for (SceneMode m : MODES) {
            if (m.key().equals(modeKey)) {
                return m;
            }
        }
        return MODES.get(0);
    }

    public boolean isValid(String modeKey) {
        if (modeKey == null) {
            return false;
        }
        for (SceneMode m : MODES) {
            if (m.key().equals(modeKey)) {
                return true;
            }
        }
        return false;
    }
}
