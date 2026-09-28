package com.companion.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 管理后台访问控制：
 * 1. 端口隔离：/admin 页面与 /api/admin 接口只允许通过管理端口（默认 8084）访问，
 *    主站端口（8083）访问一律 404，对外表现为一个独立网站。
 * 2. 会话鉴权：/api/admin/**（登录接口除外）要求会话中存在 adminAuthed 标记，否则 401。
 */
@Component
public class AdminAuthFilter extends OncePerRequestFilter {

    @Value("${companion.admin.port:8084}")
    private int adminPort;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean adminPath = path.startsWith("/admin") || path.startsWith("/api/admin");
        if (!adminPath) {
            chain.doFilter(request, response);
            return;
        }

        // 端口隔离：非管理端口访问管理资源，直接 404
        if (request.getLocalPort() != adminPort) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // 登录接口与静态页面放行（页面本身有登录门），其余 API 需要管理会话
        boolean needAuth = path.startsWith("/api/admin") && !path.equals("/api/admin/login");
        if (needAuth) {
            HttpSession session = request.getSession(false);
            Object authed = session == null ? null : session.getAttribute("adminAuthed");
            if (!Boolean.TRUE.equals(authed)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"ok\":false,\"message\":\"未登录或会话已过期\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
