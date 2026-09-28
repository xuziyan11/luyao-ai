package com.companion.controller;

import com.companion.service.TtsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final TtsService ttsService;
    private final com.companion.service.ApiLogService apiLogService;

    public TtsController(TtsService ttsService, com.companion.service.ApiLogService apiLogService) {
        this.ttsService = ttsService;
        this.apiLogService = apiLogService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok().body(Map.of(
                "available", ttsService.isAvailable(),
                "defaultVoice", ttsService.getDefaultVoice(),
                "volcanoConfigured", ttsService.isVolcanoConfigured()
        ));
    }

    @GetMapping("/synthesize")
    public ResponseEntity<InputStreamResource> synthesize(
            @RequestParam String text,
            @RequestParam(required = false) String voice,
            @RequestParam(required = false) Double speed) {

        if (!ttsService.isAvailable()) {
            return ResponseEntity.status(503).build();
        }

        long startedAt = System.currentTimeMillis();
        byte[] audio;
        try {
            audio = ttsService.synthesize(text, voice, speed == null ? 1.0 : speed);
        } catch (Exception e) {
            apiLogService.log(com.companion.service.ApiLogService.TYPE_TTS, null, false,
                    e.getMessage(), System.currentTimeMillis() - startedAt);
            return ResponseEntity.status(500).build();
        }
        if (audio == null || audio.length == 0) {
            apiLogService.log(com.companion.service.ApiLogService.TYPE_TTS, null, false,
                    "合成结果为空", System.currentTimeMillis() - startedAt);
            return ResponseEntity.status(500).build();
        }
        apiLogService.log(com.companion.service.ApiLogService.TYPE_TTS, null, true, null,
                System.currentTimeMillis() - startedAt, 0, text == null ? 0 : text.length());

        ByteArrayInputStream bis = new ByteArrayInputStream(audio);
        String filename = URLEncoder.encode("speech.mp3", StandardCharsets.UTF_8);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentLength(audio.length)
                .body(new InputStreamResource(bis));
    }

    @GetMapping("/voices")
    public ResponseEntity<?> voices() {
        List<Map<String, String>> voiceList = new ArrayList<>();

        // 始终显示豆包小何音色（配置了V3 API Key后自动启用火山引擎，否则降级到Edge TTS）
        voiceList.add(Map.of("name", "zh_female_xiaohe_uranus_bigtts", "label", "豆包小何 (女声，甜美)"));

        return ResponseEntity.ok().body(voiceList);
    }

    /**
     * 声音复刻 - 上传音频训练自定义音色
     */
    @PostMapping("/voice-clone")
    public ResponseEntity<?> voiceClone(
            @RequestParam("audio") MultipartFile audioFile,
            @RequestParam("speakerId") String speakerId,
            @RequestParam(value = "language", defaultValue = "0") int language,
            @RequestParam(value = "modelType", defaultValue = "4") int modelType) {

        if (!ttsService.isVolcanoConfigured()) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "火山引擎 TTS 未配置，无法使用声音复刻功能"
            ));
        }

        if (audioFile.isEmpty() || speakerId == null || speakerId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "音频文件和 speakerId 不能为空"
            ));
        }

        try {
            byte[] audioBytes = audioFile.getBytes();
            String format = getFileExtension(audioFile.getOriginalFilename());
            if (format == null) format = "wav";

            String result = ttsService.cloneVoice(speakerId, audioBytes, format, language, modelType);

            boolean success = result.startsWith("success");
            return ResponseEntity.ok().body(Map.of(
                    "success", success,
                    "message", result,
                    "speakerId", speakerId
            ));
        } catch (Exception e) {
            log.error("声音复刻失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "声音复刻失败: " + e.getMessage()
            ));
        }
    }

    private String getFileExtension(String filename) {
        if (filename == null) return null;
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return null;
        return filename.substring(dot + 1).toLowerCase();
    }
}
