package com.companion.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class TtsService {

    @Value("${tts.azure.endpoint:}")
    private String endpoint;

    @Value("${tts.azure.key:}")
    private String apiKey;

    @Value("${tts.azure.voice:zh-CN-XiaoyiNeural}")
    private String azureDefaultVoice;

    private final RestTemplate restTemplate = new RestTemplate();

    private final EdgeTtsClient edgeTtsClient;
    private final VolcanoTtsClient volcanoTtsClient;
    private final ConfigService configService;

    public TtsService(EdgeTtsClient edgeTtsClient, VolcanoTtsClient volcanoTtsClient,
                      ConfigService configService) {
        this.edgeTtsClient = edgeTtsClient;
        this.volcanoTtsClient = volcanoTtsClient;
        this.configService = configService;
    }

    /**
     * TTS 是否可用（火山引擎或 Edge TTS 免费可用，始终返回 true）
     */
    public boolean isAvailable() {
        return true;
    }

    /**
     * 判断是否使用火山引擎
     */
    public boolean isVolcanoConfigured() {
        return volcanoTtsClient.isConfigured();
    }

    /**
     * 合成语音
     * 优先级: 火山引擎 > Azure > Edge TTS（免费降级）
     * 重要：前端 localStorage 可能遗留旧音色ID（如 BV705_streaming），
     * 火山引擎未成功时必须重置 useVoice 为各自平台兼容的音色，不要把错误音色喂给降级链。
     */
    public byte[] synthesize(String text, String voice) {
        return synthesize(text, voice, 1.0);
    }

    /**
     * 合成语音（带语速）：speed 1.0 为正常语速（场景模式联动，如深夜哄睡 0.85）。
     * 优先级: 火山引擎 > Azure > Edge TTS（免费降级）
     * 重要：前端 localStorage 可能遗留旧音色ID（如 BV705_streaming），
     * 火山引擎未成功时必须重置 useVoice 为各自平台兼容的音色，不要把错误音色喂给降级链。
     */
    public byte[] synthesize(String text, String voice, double speed) {
        if (text == null || text.isBlank()) return null;
        final double speedRatio = Math.max(0.5, Math.min(2.0, speed));

        // 标准化传入的音色：空或纯空白 → null
        String useVoice = (voice != null && !voice.isBlank()) ? voice.trim() : null;

        // 1. 火山引擎 TTS（优先，支持 _bigtts 豆包音色 V3 API）
        if (volcanoTtsClient.isConfigured()) {
            // 不识别的音色 ID 一律走默认音色（清理 localStorage 遗留值）
            boolean isKnownVolcanoVoice = useVoice != null &&
                    (useVoice.endsWith("_bigtts") ||
                            useVoice.startsWith("BV") ||
                            useVoice.startsWith("zh_") ||
                            useVoice.startsWith("S_") ||
                            useVoice.startsWith("ICL_") ||
                            useVoice.startsWith("saturn_"));
            String volcVoice = isKnownVolcanoVoice ? useVoice : volcanoTtsClient.getDefaultVoice();
            byte[] result = volcanoTtsClient.synthesize(text, volcVoice, speedRatio);
            if (result != null && result.length > 0) return result;
            log.warn("火山引擎 TTS (音色={}) 失败，尝试降级", volcVoice);
        }

        // 2. Azure TTS
        if (isAzureConfigured()) {
            boolean isAzureVoice = useVoice != null && useVoice.startsWith("zh-CN-") && useVoice.endsWith("Neural");
            String azVoice = isAzureVoice ? useVoice : azureDefaultVoice;
            byte[] result = synthesizeWithAzure(text, azVoice, speedRatio);
            if (result != null && result.length > 0) return result;
            log.warn("Azure TTS (音色={}) 失败，降级到 Edge TTS", azVoice);
        }

        // 3. Edge TTS（免费降级）— 只接受 zh-CN-*Neural 格式
        String edgeVoice = "zh-CN-XiaoyiNeural";
        if (useVoice != null && useVoice.startsWith("zh-CN-") && useVoice.endsWith("Neural")) {
            edgeVoice = useVoice;
        }
        return edgeTtsClient.synthesize(text, edgeVoice, speedRatio);
    }

    /**
     * 声音复刻 - 上传音频训练自定义音色
     */
    public String cloneVoice(String speakerId, byte[] audioBytes, String format, int language, int modelType) {
        return volcanoTtsClient.uploadVoice(speakerId, audioBytes, format, language, modelType);
    }

    private boolean isAzureConfigured() {
        return endpoint != null && !endpoint.isBlank()
                && apiKey != null && !apiKey.isBlank();
    }

    private byte[] synthesizeWithAzure(String text, String voice, double speedRatio) {
        if (!isAzureConfigured()) return null;
        try {
            String url = endpoint.replaceAll("/$", "") + "/cognitiveservices/v1";

            int ratePct = (int) Math.round(Math.max(-50, Math.min(100, (speedRatio - 1.0) * 100)));
            String ssml = """
                    <speak version='1.0' xml:lang='zh-CN'>
                      <voice name='%s'><prosody rate='%d%%'>%s</prosody></voice>
                    </speak>
                    """.formatted(voice, ratePct, text);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/ssml+xml"));
            headers.set("Ocp-Apim-Subscription-Key", apiKey);
            headers.set("X-Microsoft-OutputFormat", "audio-24khz-48khz-mono-mp3");
            headers.set("User-Agent", "ai-companion/1.0");

            HttpEntity<String> entity = new HttpEntity<>(ssml, headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
            log.warn("Azure TTS 返回非成功状态: {}", response.getStatusCode());
            return null;
        } catch (Exception e) {
            log.error("Azure TTS 合成失败: {}", e.getMessage());
            return null;
        }
    }

    public String getDefaultVoice() {
        // 管理后台可在线改默认音色（app_config: tts.default_voice），未设置时用 yml 配置
        return configService.get("tts.default_voice", volcanoTtsClient.getDefaultVoice());
    }
}
