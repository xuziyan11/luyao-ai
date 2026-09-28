package com.companion.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

/**
 * 聊天头像上传与读取。
 * 路瑶头像：/avatar/ai
 * 用户头像：/avatar/user
 * 文件落在 static/avatars/ 下，前端通过 /avatars/{role}.png?v=timestamp 访问。
 */
@RestController
public class AvatarController {

    private final Path avatarDir;

    public AvatarController(@Value("${spring.web.resources.static-locations:classpath:/static/}") String staticLocations) {
        // 默认存到运行目录下 ./static/avatars/，会被 Spring 当作静态资源对外提供
        Path dir = Paths.get("static", "avatars");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
        this.avatarDir = dir;
    }

    @PostMapping("/avatar/{role}")
    public ResponseEntity<?> upload(@PathVariable("role") String role,
                                    @RequestParam("file") MultipartFile file) {
        if (!role.equals("ai") && !role.equals("user")) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "role 只能是 ai 或 user"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "文件为空"));
        }
        // 保留原后缀，前端加载时用同一后缀；默认 png
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = ".png";
        int dot = original.lastIndexOf('.');
        if (dot > 0) {
            String e = original.substring(dot).toLowerCase();
            if (e.matches("\\.(png|jpg|jpeg|gif|webp)")) ext = e;
        }
        try {
            Path target = avatarDir.resolve(role + ext);
            // 清理同 role 其他后缀的旧文件
            for (String old : new String[]{".png", ".jpg", ".jpeg", ".gif", ".webp"}) {
                if (!old.equals(ext)) {
                    Files.deleteIfExists(avatarDir.resolve(role + old));
                }
            }
            Files.write(target, file.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String url = "/avatars/" + role + ext + "?v=" + System.currentTimeMillis();
            return ResponseEntity.ok(Map.of("success", true, "url", url, "role", role));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("success", false, "message", "保存失败: " + e.getMessage()));
        }
    }

    /** 兜底：直接 GET /avatar/{role}/file 也返回图片 */
    @GetMapping("/avatar/{role}/file")
    public ResponseEntity<Resource> get(@PathVariable("role") String role) {
        for (String ext : new String[]{".png", ".jpg", ".jpeg", ".gif", ".webp"}) {
            Path p = avatarDir.resolve(role + ext);
            if (Files.exists(p)) {
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE)
                        .body(new FileSystemResource(p));
            }
        }
        return ResponseEntity.notFound().build();
    }
}
