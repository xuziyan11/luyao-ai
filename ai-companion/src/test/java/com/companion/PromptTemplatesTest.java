package com.companion;

import com.companion.prompt.PromptTemplates;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PromptTemplatesTest {

    @Test
    void wechatPromptShouldBeMoreNaturalThanWebPrompt() {
        // ConfigService 依赖数据库，测试中用 stub 返回默认值即可
        PromptTemplates templates = new PromptTemplates(new com.companion.service.ConfigService(null) {
            @Override
            public String get(String key, String defaultValue) {
                return defaultValue;
            }
        });

        String webPrompt = templates.buildSystemPrompt(50, List.of("喜欢吃火锅"), List.of("今天是周末"), "web");
        String wechatPrompt = templates.buildSystemPrompt(50, List.of("喜欢吃火锅"), List.of("今天是周末"), "wechat");

        assertNotNull(webPrompt);
        assertNotNull(wechatPrompt);
        assertNotEquals(webPrompt, wechatPrompt);
        assertTrue(webPrompt.contains("你们是亲密朋友"));
        assertTrue(wechatPrompt.contains("你们是亲密朋友"));
        assertTrue(wechatPrompt.contains("与网页端完全一致"));
        assertTrue(wechatPrompt.toLowerCase().contains("微信"));
        assertTrue(wechatPrompt.toLowerCase().contains("自然"));
        assertFalse(wechatPrompt.contains("严格控制在 2-10 个字"));
    }
}
