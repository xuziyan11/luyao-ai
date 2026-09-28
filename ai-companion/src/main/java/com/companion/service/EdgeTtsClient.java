package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Edge TTS 客户端 - 通过 edge-tts 命令行工具合成语音
 * 使用微软免费的 Neural 语音（不需要 API key）
 */
@Slf4j
@Component
public class EdgeTtsClient {

    private static final String EDGE_TTS_BIN = findEdgeTtsBin();

    /**
     * 查找 edge-tts 可执行文件
     */
    private static String findEdgeTtsBin() {
        String[] candidates = {
                "/Users/xzy/Library/Python/3.9/bin/edge-tts",
                "/usr/local/bin/edge-tts",
                "/opt/homebrew/bin/edge-tts"
        };
        for (String path : candidates) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return "edge-tts";
    }

    /**
     * 合成语音，返回 MP3 格式音频数据
     */
    public byte[] synthesize(String text, String voice) {
        return synthesize(text, voice, 1.0);
    }

    /**
     * 合成语音（带语速）：speedRatio 1.0 为正常，映射 edge-tts 的 --rate 百分比。
     */
    public byte[] synthesize(String text, String voice, double speedRatio) {
        if (text == null || text.isBlank()) return null;

        String useVoice = (voice != null && !voice.isBlank()) ? voice : "zh-CN-XiaoyiNeural";
        int ratePct = (int) Math.round(Math.max(-50, Math.min(100, (speedRatio - 1.0) * 100)));
        String rateArg = (ratePct >= 0 ? "+" : "") + ratePct + "%";

        Path tempFile = null;
        try {
            // 创建临时文件
            tempFile = Files.createTempFile("edge-tts-", ".mp3");

            // 调用 edge-tts 命令
            ProcessBuilder pb = new ProcessBuilder(
                    EDGE_TTS_BIN,
                    "--voice", useVoice,
                    "--rate", rateArg,
                    "--text", text,
                    "--write-media", tempFile.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 等待命令完成
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                String error = new String(process.getInputStream().readAllBytes());
                log.error("edge-tts 命令失败 (exit {}): {}", exitCode, error);
                return null;
            }

            // 读取生成的音频文件
            byte[] audio = Files.readAllBytes(tempFile);
            if (audio.length == 0) {
                log.warn("edge-tts 生成了空文件");
                return null;
            }

            return audio;
        } catch (Exception e) {
            log.error("Edge TTS 合成失败: {}", e.getMessage());
            return null;
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (Exception ignored) {}
            }
        }
    }
}
