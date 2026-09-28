package com.companion.controller;

import com.companion.service.DiaryService;
import com.companion.service.GeoService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * AI 情绪日记接口：按日查询 / 日期列表 / 手动生成 / 编辑保存。
 * 与 /api/profile 等接口一致，以 user（聊天 sessionId）标识数据归属。
 */
@RestController
@RequestMapping("/api/diary")
public class DiaryController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DiaryService diaryService;
    private final GeoService geoService;

    public DiaryController(DiaryService diaryService, GeoService geoService) {
        this.diaryService = diaryService;
        this.geoService = geoService;
    }

    private String normalizeDate(String date) {
        return (date == null || date.isBlank()) ? LocalDate.now().format(DAY) : date;
    }

    /** 查询某天日记；无日记返回 exists=false（附当日是否已有足够聊天可生成）。 */
    @GetMapping
    public Map<String, Object> get(@RequestParam String user, @RequestParam(required = false) String date) {
        String d = normalizeDate(date);
        Map<String, Object> diary = diaryService.get(user, d);
        if (diary == null) {
            return Map.of("ok", true, "exists", false, "date", d);
        }
        return Map.of("ok", true, "exists", true, "date", d, "diary", diary);
    }

    /** 有日记的日期列表（倒序）。 */
    @GetMapping("/dates")
    public Map<String, Object> dates(@RequestParam String user) {
        return Map.of("ok", true, "dates", diaryService.listDates(user, 60));
    }

    /** 手动生成/重新生成某天（默认今天）日记。lat/lon 为浏览器定位坐标（可选）；
     *  无坐标时由后端按请求真实客户端 IP 离线解析城市，作为天气定位兜底。 */
    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestParam String user,
                                        @RequestParam(required = false) String date,
                                        @RequestParam(required = false) Double lat,
                                        @RequestParam(required = false) Double lon,
                                        HttpServletRequest request) {
        String d = normalizeDate(date);
        String city = geoService.cityOf(request); // 真实客户端 IP 城市（离线库），无坐标时兜底用
        Map<String, Object> diary = diaryService.generate(user, d, lat, lon, city);
        if (diary == null) {
            return Map.of("ok", false, "message", "当天的对话还太少，多聊几句再来生成日记吧");
        }
        return Map.of("ok", true, "exists", true, "date", d, "diary", diary);
    }

    /** 保存编辑：正文 / 关键事件(JSON数组字符串) / 寄语。 */
    @PutMapping
    public Map<String, Object> update(@RequestParam String user,
                                      @RequestParam String date,
                                      @RequestParam(required = false) String content,
                                      @RequestParam(required = false) String events,
                                      @RequestParam(required = false) String blessing) {
        boolean saved = diaryService.update(user, date,
                content == null ? "" : content,
                events == null ? "[]" : events,
                blessing == null ? "" : blessing);
        return Map.of("ok", saved);
    }
}
