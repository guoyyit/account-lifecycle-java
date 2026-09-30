package com.github.guoyyit.accountlifecyclejava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "account-lifecycle.data-dir=target/integration-data",
        "account-lifecycle.scheduler-enabled=false"
})
@AutoConfigureMockMvc
class WebApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerQueryAndDisableThroughHttp() throws Exception {
        String accountId = "web-" + System.nanoTime();

        mockMvc.perform(post("/api/accounts")
                        .contentType("application/json")
                        .content("{\"accountId\":\"" + accountId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.statusReason").value("REGISTERED"));

        mockMvc.perform(get("/api/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupName").value("default"));

        mockMvc.perform(post("/api/accounts/" + accountId + ":disable")
                        .param("reason", "security-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(get("/api/accounts/" + accountId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("ACCOUNT_REGISTERED"));

        // 对非回收账号发起恢复必须被拒绝，HTTP 400。
        String activeId = "web-active-" + System.nanoTime();
        mockMvc.perform(post("/api/accounts")
                        .contentType("application/json")
                        .content("{\"accountId\":\"" + activeId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/recovery/" + activeId))
                .andExpect(status().isBadRequest());
    }
}
