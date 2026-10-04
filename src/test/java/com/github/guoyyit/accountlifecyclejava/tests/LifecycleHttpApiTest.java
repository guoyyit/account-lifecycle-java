package com.github.guoyyit.accountlifecyclejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "account-lifecycle.data-dir=target/test-data/http-api-tests",
        "account-lifecycle.default-credential-grace-period=PT0.05S",
        "account-lifecycle.default-verification-ttl=PT0.05S"
})
class LifecycleHttpApiTest {

    @Autowired
    MockMvc mockMvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void graceExpiryAndRecoveryVerificationFlowOverHttp() throws Exception {
        String id = "http-" + System.nanoTime();

        mockMvc.perform(post("/api/lifecycle/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + id + "\",\"credentialTtl\":\"PT0.01S\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));

        Thread.sleep(20);
        mockMvc.perform(post("/api/lifecycle/scan")).andExpect(status().isOk());
        Thread.sleep(60);

        MvcResult rejected = mockMvc.perform(post("/api/lifecycle/accounts/" + id
                        + "/credential-renewal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttl\":\"P30D\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("GRACE_PERIOD_EXPIRED"))
                .andReturn();

        mockMvc.perform(post("/api/lifecycle/scan")).andExpect(status().isOk());

        mockMvc.perform(get("/api/lifecycle/accounts/" + id + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.eventType=='CREDENTIAL_RENEWAL_REJECTED')]").exists());

        MvcResult start = mockMvc.perform(post("/api/lifecycle/accounts/" + id + "/recoveries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"req-" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        JsonNode challenge = mapper.readTree(start.getResponse().getContentAsString());
        String token = challenge.get("verificationToken").asText();
        org.junit.jupiter.api.Assertions.assertFalse(token.isBlank());

        mockMvc.perform(post("/api/lifecycle/recoveries/req-" + id + "/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationToken\":\"forged\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECOVERY_TOKEN_MISMATCH"));

        Thread.sleep(60);
        mockMvc.perform(post("/api/lifecycle/recoveries/req-" + id + "/verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verificationToken\":\"" + token + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECOVERY_TOKEN_EXPIRED"));
    }
}
