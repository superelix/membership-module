package com.application.membershipmodule.e2e;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.application.membershipmodule.testsupport.AbstractPostgresIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/reviews/04-e2e-prd-verification.md FAIL #1's structural fix, verified the way the report's
 * own root-cause analysis demands: "none of [the existing tests] let a real {@code ApplicationEvent}
 * traverse Spring's actual AFTER_COMMIT synchronization machinery the way a real HTTP request
 * against a real transaction manager does." This test does exactly that — real HTTP dispatch via
 * {@link MockMvc} (full {@code DispatcherServlet}, full controller/service/repository stack, a
 * genuinely separate, committing transaction per request, exactly like a real
 * {@code POST /checkout/{id}/place} call over the wire), against real Postgres and real Redis
 * (both via {@link AbstractPostgresIntegrationTest}'s Testcontainers).
 *
 * <p>Critically, this test never calls {@code TierEvaluationService.evaluate(...)} directly and
 * never hits {@code POST /internal/tier-recompute} — the only way the assertion can pass is if the
 * real {@code OrderPlacedEvent} -&gt; Redis Stream -&gt; {@code TierRecomputeStreamConsumer} pipeline
 * actually promotes the member automatically, which is the exact thing that was broken.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderPlacedAutoTierPromotionE2ETest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void fiveRealOrdersThroughRealHttpAutoPromoteToGoldWithoutManualTrigger() throws Exception {
        String memberId = "e2e-redis-promo-" + UUID.randomUUID();

        mockMvc.perform(post("/api/v1/subscriptions")
                        .header("X-Member-Id", memberId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"MONTHLY\"}"))
                .andExpect(status().isCreated());

        for (int i = 0; i < 5; i++) {
            String checkoutBody = "{\"items\":[{\"productId\":\"p" + i
                    + "\",\"categoryCode\":\"ELECTRONICS\",\"unitPrice\":\"10.00\",\"quantity\":1}]}";

            MvcResult startResult = mockMvc.perform(post("/api/v1/checkout")
                            .header("X-Member-Id", memberId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(checkoutBody))
                    .andExpect(status().isCreated())
                    .andReturn();
            JsonNode startJson = objectMapper.readTree(startResult.getResponse().getContentAsString());
            String orderId = startJson.get("orderId").asText();

            mockMvc.perform(post("/api/v1/checkout/" + orderId + "/place"))
                    .andExpect(status().isOk());
        }

        // No call to /internal/tier-recompute anywhere in this test - promotion must happen
        // automatically via the real OrderPlacedEvent -> Redis Stream -> consumer pipeline.
        String finalTier = awaitCurrentTier(memberId, Duration.ofSeconds(15));

        assertThat(finalTier).isEqualTo("GOLD");
    }

    private String awaitCurrentTier(String memberId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        String lastSeen = null;
        while (Instant.now().isBefore(deadline)) {
            MvcResult result = mockMvc.perform(get("/api/v1/subscriptions/me").header("X-Member-Id", memberId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
            lastSeen = json.get("currentTier").asText();
            if ("GOLD".equals(lastSeen)) {
                return lastSeen;
            }
            Thread.sleep(200);
        }
        return lastSeen;
    }
}
