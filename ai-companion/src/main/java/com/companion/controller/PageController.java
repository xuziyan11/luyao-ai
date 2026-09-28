package com.companion.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 页面路由：未登录访问聊天页时重定向到登录页，登录后（手机验证码 / 微信）才允许进入。
 */
@Controller
public class PageController {

    @GetMapping("/")
    public String index(HttpSession session) {
        if (session == null || session.getAttribute("accountId") == null) {
            return "redirect:/login";
        }
        return "forward:/index.html";
    }

    @GetMapping("/login")
    public String login(HttpSession session) {
        // 登录页永远直接展示：不做"已登录自动跳回 /"。
        // 否则当服务端 Session 仍有效而浏览器 sessionStorage 已清空（关闭页面后重进、
        // 或登出请求被导航中断）时，会与 index.html 的未登录跳转形成 / ↔ /login 无限刷新循环。
        return "forward:/login.html";
    }

    /** 管理后台入口（仅管理端口可达，端口隔离见 AdminAuthFilter）：/admin 友好重定向到静态页。 */
    @GetMapping({"/admin", "/admin/"})
    public String admin() {
        return "redirect:/admin/index.html";
    }
}
