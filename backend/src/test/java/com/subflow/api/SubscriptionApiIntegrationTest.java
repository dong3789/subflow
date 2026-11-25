package com.subflow.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subflow.domain.billingkey.BillingKey;
import com.subflow.domain.customer.Customer;
import com.subflow.domain.plan.BillingCycle;
import com.subflow.domain.plan.Plan;
import com.subflow.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * REST API 통합 테스트 — 실제 HTTP 요청으로 구독 생애주기를 끝까지 호출한다.
 *
 * <p>서비스 단위 테스트가 도메인 규칙을 본다면, 이 테스트는 "API 로 실제로 그 규칙이 지켜지는지"
 * (상태 코드·오류 코드·응답 필드)를 확인한다.
 */
@AutoConfigureMockMvc
class SubscriptionApiIntegrationTest extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private long createSubscriptionViaApi(Plan plan, Customer customer, BillingKey billingKey) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "customerId", customer.getId(),
                "planId", plan.getId(),
                "billingKeyId", billingKey.getId(),
                "quantity", 1));
        String response = mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(response);
        return node.get("id").asLong();
    }

    @Test
    @DisplayName("구독 생성 → 당겨받기 → 중복 차단 → 해지 → 복구 → 이력 조회가 API 로 동작한다")
    void fullLifecycleOverHttp() throws Exception {
        Plan plan = createPlan("9900", BillingCycle.MONTHLY, 0);
        Customer customer = createCustomer();
        BillingKey billingKey = createBillingKey(customer);
        long subscriptionId = createSubscriptionViaApi(plan, customer, billingKey);

        mockMvc.perform(get("/api/v1/subscriptions/{id}", subscriptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingCycle").value("MONTHLY"))
                .andExpect(jsonPath("$.anchorDay").isNumber())
                .andExpect(jsonPath("$.billingCycleCount").value(0));

        mockMvc.perform(get("/api/v1/subscriptions/{id}/cycle-change/preview", subscriptionId)
                        .param("billingCycle", "WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCycle").value("MONTHLY"))
                .andExpect(jsonPath("$.newCycle").value("WEEKLY"));

        mockMvc.perform(post("/api/v1/subscriptions/{id}/renew", subscriptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.billingCycleCount").value(1));

        mockMvc.perform(post("/api/v1/subscriptions/{id}/renew", subscriptionId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_BILLING_ATTEMPT"));

        mockMvc.perform(post("/api/v1/subscriptions/{id}/quantity-change", subscriptionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":3,\"effectiveMode\":\"NEXT_CYCLE\",\"reason\":\"좌석 추가\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.amount").value(29700));

        mockMvc.perform(post("/api/v1/subscriptions/{id}/cancel", subscriptionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"고객 요청\",\"immediate\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.cancelReason").value("고객 요청"));

        mockMvc.perform(post("/api/v1/subscriptions/{id}/restore", subscriptionId)
                        .header(ActorHeaders.HEADER_ACTOR_TYPE, "ADMIN")
                        .header(ActorHeaders.HEADER_ACTOR_ID, "admin-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/subscriptions/{id}/history", subscriptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].changeType").value("RESTORE"))
                .andExpect(jsonPath("$[1].changeType").value("CANCEL"))
                .andExpect(jsonPath("$[2].changeType").value("QUANTITY_CHANGE"));

        mockMvc.perform(get("/api/v1/admin/audit-logs").param("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").exists())
                .andExpect(jsonPath("$[0].targetId").exists());
    }

    @Test
    @DisplayName("단종 플랜으로는 API 로 구독을 만들 수 없다(400 PLAN_INACTIVE)")
    void inactivePlanCannotBeSubscribed() throws Exception {
        Plan plan = createPlan("9900", BillingCycle.MONTHLY, 0);
        plan.deactivate();
        planRepository.save(plan);
        Customer customer = createCustomer();
        BillingKey billingKey = createBillingKey(customer);

        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "customerId", customer.getId(), "planId", plan.getId(),
                "billingKeyId", billingKey.getId(), "quantity", 1));

        mockMvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_INACTIVE"));
    }

    @Test
    @DisplayName("환불 웹훅 API 는 200 으로 응답하고, 재전송은 DUPLICATE_IGNORED 로 조용히 넘긴다")
    void webhookEndpointIsIdempotent() throws Exception {
        Plan plan = createPlan("9900", BillingCycle.MONTHLY, 0);
        Customer customer = createCustomer();
        BillingKey billingKey = createBillingKey(customer);
        long subscriptionId = createSubscriptionViaApi(plan, customer, billingKey);

        String renewResponse = mockMvc.perform(post("/api/v1/subscriptions/{id}/renew", subscriptionId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pgTxId = objectMapper.readTree(renewResponse).get("pgTxId").asText();

        String payload = objectMapper.writeValueAsString(java.util.Map.of(
                "eventId", "evt-http-1",
                "eventType", "PAYMENT_REFUNDED",
                "pgTxId", pgTxId,
                "amount", 9900,
                "reason", "환불 요청"));

        mockMvc.perform(post("/api/v1/webhooks/MOCK")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.subscriptionId").value(subscriptionId));

        mockMvc.perform(post("/api/v1/webhooks/MOCK")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DUPLICATE_IGNORED"));

        mockMvc.perform(get("/api/v1/subscriptions/{id}", subscriptionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    @DisplayName("운영 API 로 요약·배치·정합성 점검·Outbox 발행을 실행할 수 있다")
    void adminEndpointsWork() throws Exception {
        Customer customer = createCustomer();
        createSubscriptionViaApi(createPlan("9900", BillingCycle.MONTHLY, 0), customer,
                createBillingKey(customer));

        mockMvc.perform(get("/api/v1/admin/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSubscriptions").value(1))
                .andExpect(jsonPath("$.pendingOutboxEvents").isNumber());

        mockMvc.perform(post("/api/v1/admin/billing/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(post("/api/v1/admin/reconciliation/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedCount").value(0));

        mockMvc.perform(post("/api/v1/admin/outbox/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").isNumber());
    }

    @Test
    @DisplayName("없는 구독 조회는 404 ENTITY_NOT_FOUND 로 응답한다")
    void missingSubscriptionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/subscriptions/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENTITY_NOT_FOUND"));
    }
}
