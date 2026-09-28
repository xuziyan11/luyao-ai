package com.companion.controller;

import com.companion.service.IlinkClient;
import com.companion.service.IlinkPollingTask;
import com.companion.service.WechatBindingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * iLink ClawBot 接入接口。
 * <p>
 * 三步走：
 * 1. GET /ilink/bind               → 返回二维码图片 URL，前端展示给用户扫
 * 2. GET /ilink/status?qrcode=xxx  → 前端轮询扫码状态，confirmed 后服务端自动落库 + 启动轮询
 * 3. GET /ilink/bindings           → 查看已绑定的微信账号列表（运维/调试用）
 */
@Slf4j
@RestController
@RequestMapping("/ilink")
public class IlinkController {

    private final IlinkClient ilinkClient;
    private final WechatBindingRepository bindingRepo;
    private final IlinkPollingTask pollingTask;
    private final ObjectMapper mapper = new ObjectMapper();

    // iLink 绑定接口的保护口令：生产环境通过环境变量 COMPANION_PASSWORD 注入。
    // 不提供默认口令，缺失时启动报错，避免弱口令暴露绑定入口。
    @Value("${companion.auth.password}")
    private String password;

    @Autowired
    public IlinkController(IlinkClient ilinkClient,
                           WechatBindingRepository bindingRepo,
                           IlinkPollingTask pollingTask) {
        this.ilinkClient = ilinkClient;
        this.bindingRepo = bindingRepo;
        this.pollingTask = pollingTask;
    }

    /**
     * 第一步：拿扫码二维码。
     * 返回 { qrcode, img_url }，前端用 img_url 渲染二维码。
     */
    @GetMapping("/bind")
    public ObjectNode bind(@RequestParam(required = false) String pwd) {
        ObjectNode node = mapper.createObjectNode();
        if (!checkPwd(pwd)) {
            node.put("error", "密码错误");
            return node;
        }
        try {
            IlinkClient.QrcodeResult r = ilinkClient.getQrcode();
            node.put("qrcode", r.qrcode());
            node.put("img_url", r.qrcodeImgContent());
            return node;
        } catch (Exception e) {
            log.error("拿二维码失败", e);
            node.put("error", "拿二维码失败: " + e.getMessage());
            return node;
        }
    }

    /**
     * 第二步：前端用 qrcode 轮询扫码状态。
     * status=confirmed 时服务端自动落库 + 启动该账号的轮询线程。
     */
    @GetMapping("/status")
    public ObjectNode status(@RequestParam String qrcode,
                             @RequestParam(required = false) String pwd) {
        ObjectNode node = mapper.createObjectNode();
        if (!checkPwd(pwd)) {
            node.put("error", "密码错误");
            return node;
        }
        try {
            IlinkClient.QrcodeStatus s = ilinkClient.getQrcodeStatus(qrcode);
            node.put("status", s.status());
            if (s.confirmed()) {
                // 落库 + 启动轮询
                WechatBindingRepository.Binding b = new WechatBindingRepository.Binding(
                        s.ilinkUserId(), s.botToken(), s.ilinkBotId(), s.baseurl(),
                        null, null, null);
                bindingRepo.upsert(b);
                // 先停旧的（重新扫码场景），再启新的
                pollingTask.stopPoller(s.ilinkUserId());
                pollingTask.startPoller(bindingRepo.find(s.ilinkUserId()));
                node.put("ilink_user_id", s.ilinkUserId());
                log.info("微信账号绑定成功: {}", s.ilinkUserId());
            }
            return node;
        } catch (Exception e) {
            log.error("查询扫码状态失败", e);
            node.put("status", "error");
            node.put("error", e.getMessage());
            return node;
        }
    }

    /**
     * 第三步（可选）：查看已绑定账号。
     */
    @GetMapping("/bindings")
    public ObjectNode bindings(@RequestParam(required = false) String pwd) {
        ObjectNode node = mapper.createObjectNode();
        if (!checkPwd(pwd)) {
            node.put("error", "密码错误");
            return node;
        }
        var arr = node.putArray("bindings");
        for (WechatBindingRepository.Binding b : bindingRepo.findAll()) {
            ObjectNode item = arr.addObject();
            item.put("ilink_user_id", b.ilinkUserId());
            item.put("ilink_bot_id", b.ilinkBotId());
            item.put("baseurl", b.baseurl());
            item.put("has_context_token", b.contextToken() != null && !b.contextToken().isBlank());
            item.put("has_updates_buf", b.getUpdatesBuf() != null && !b.getUpdatesBuf().isBlank());
        }
        return node;
    }

    private boolean checkPwd(String pwd) {
        return password.equals(pwd);
    }
}
