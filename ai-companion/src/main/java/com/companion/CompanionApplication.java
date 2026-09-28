package com.companion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Paths;

/**
 * 路瑶 AI 启动类。
 * 运行后默认监听 8083 端口，访问 http://localhost:8083/ 打开聊天界面。
 */
@SpringBootApplication
@EnableScheduling
public class CompanionApplication {

    public static void main(String[] args) {
        SpringApplication.run(CompanionApplication.class, args);
    }

    /** 把运行目录下的 ./static/avatars/ 暴露为 /avatars/** 静态资源 */
    @Configuration
    public static class AvatarResourceConfig implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            String loc = Paths.get("static", "avatars").toAbsolutePath().toUri().toString();
            registry.addResourceHandler("/avatars/**").addResourceLocations(loc);
        }
    }
}
