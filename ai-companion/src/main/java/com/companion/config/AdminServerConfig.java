package com.companion.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;

/**
 * 管理后台独立端口：在主端口（8083，路瑶网站）之外再监听一个管理端口（默认 8084）。
 * 管理页面 /admin 与管理接口 /api/admin 只接受来自管理端口的请求（见 AdminAuthFilter），
 * 两个站点共享同一个 Spring 上下文与数据库，因此后台可直接读取/修改主站运行数据与动态配置。
 */
@Configuration
public class AdminServerConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Value("${companion.admin.port:8084}")
    private int adminPort;

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setPort(adminPort);
        factory.addAdditionalTomcatConnectors(connector);
    }
}
