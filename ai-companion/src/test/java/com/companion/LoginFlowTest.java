package com.companion;

import com.companion.controller.ChatWebSocketHandler;
import com.companion.prompt.PromptTemplates;
import com.companion.service.IlinkClient;
import com.companion.service.IlinkPollingTask;
import com.companion.service.MemoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;

import static org.assertj.core.api.Assertions.tuple;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:login_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:/schema.sql",
        "companion.deepseek.api-key=test-key",
        "companion.admin.password=test-pass",
        "companion.auth.password=test-pass"
})
class LoginFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemoryService memoryService;

    @Autowired
    private com.companion.service.AffinityService affinityService;

    @Autowired
    private PromptTemplates promptTemplates;

    @Test
    void rootRequiresLogin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void passwordLoginRejectsUnknownAccount() throws Exception {
        // 不存在的账号 + 任意密码都应被拒绝（不创建账号，H2 安全）；验证密码登录端点可用
        mockMvc.perform(post("/api/auth/password/login")
                        .param("phone", "13900000000")
                        .param("password", "whatever"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void previousMessagesAreReturnedInOrder() {
        String sessionId = "history-test-session";
        memoryService.saveMessage(sessionId, "user", "你好");
        memoryService.saveMessage(sessionId, "assistant", "你好呀");

        var history = memoryService.getAllMessages(sessionId);

        org.assertj.core.api.Assertions.assertThat(history)
                .extracting("role", "content")
                .containsExactly(
                        tuple("user", "你好"),
                        tuple("assistant", "你好呀")
                );
    }

    @Test
    void authenticatedSessionMustOverrideBrowserUserId() {
        String fallback = "ws-session-123";

        String resolvedNoUser = ChatWebSocketHandler.resolveSessionIdFromUri(
                URI.create("ws://localhost/chat"), fallback, null);
        String resolvedDefault = ChatWebSocketHandler.resolveSessionIdFromUri(
                URI.create("ws://localhost/chat?user=default"), fallback, "default");
        String resolvedCustom = ChatWebSocketHandler.resolveSessionIdFromUri(
                URI.create("ws://localhost/chat?user=phone-a"), fallback, "phone-a");

        org.assertj.core.api.Assertions.assertThat(resolvedNoUser).isEqualTo(fallback);
        org.assertj.core.api.Assertions.assertThat(resolvedDefault).isEqualTo(fallback);
        org.assertj.core.api.Assertions.assertThat(resolvedCustom).isEqualTo(fallback);
    }

    @Test
    void currentRealTimeIsIncludedInSystemPrompt() {
        String prompt = promptTemplates.buildSystemPrompt(40, java.util.List.of(), java.util.List.of());

        org.assertj.core.api.Assertions.assertThat(prompt)
                .contains("当前现实时间")
                .contains("星期")
                .contains("时段")
                .contains("当前现实");
    }

    @Test
    void newSessionStartsAtZeroAffinity() {
        String sessionId = "fresh-start-session";
        int affinity = affinityService.getAffinity(sessionId);

        org.assertj.core.api.Assertions.assertThat(affinity).isZero();
    }

    @Test
    void emptyPollResponsesUseBackoffToAvoidBusyLoop() {
        long backoffMs = IlinkPollingTask.calculatePollBackoffMs(false, null, 1000L);
        long activeMs = IlinkPollingTask.calculatePollBackoffMs(true, "cursor", 1000L);

        org.assertj.core.api.Assertions.assertThat(backoffMs).isEqualTo(1000L);
        org.assertj.core.api.Assertions.assertThat(activeMs).isZero();
    }

    @Test
    void ilinkGetupdatesParserHandlesNestedOpenClawPayload() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload = """
                {
                  "get_updates_buf": "cursor-123",
                  "msgs": [
                    {
                      "msg_id": "m1",
                      "from_user_id": "u1",
                      "message_type": 1,
                      "context_token": "ctx-1",
                      "item_list": [
                        { "text_item": { "text": "你好，路瑶" } }
                      ]
                    }
                  ]
                }
                """;

        var parsed = IlinkClient.parseGetUpdatesRoot(mapper.readTree(payload));

        org.assertj.core.api.Assertions.assertThat(parsed.newBuf()).isEqualTo("cursor-123");
        org.assertj.core.api.Assertions.assertThat(parsed.messages())
                .hasSize(1)
                .extracting("content", "fromUser", "msgType")
                .containsExactly(tuple("你好，路瑶", "u1", 1));

        String fallbackPayload = """
                {
                  "result": {
                    "messages": [
                      {
                        "client_id": "m2",
                        "from_user": "u2",
                        "type": 1,
                        "content": "今天天气不错"
                      }
                    ]
                  }
                }
                """;

        var fallbackParsed = IlinkClient.parseGetUpdatesRoot(mapper.readTree(fallbackPayload));
        org.assertj.core.api.Assertions.assertThat(fallbackParsed.messages())
                .extracting("content")
                .containsExactly("今天天气不错");
    }
}
