package com.subflow.api;

import com.subflow.api.dto.ApiDtos.MockPgStatusResponse;
import com.subflow.api.dto.ApiDtos.MockScenarioRequest;
import com.subflow.pg.MockPgClient;
import com.subflow.pg.MockPgScenario;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock PG 시나리오 제어 API(데모·검증용).
 *
 * <p>{@code subflow.pg.provider=mock} 일 때만 열린다. 실제 PG 구성에서는 이 컨트롤러가 없다.
 * 시나리오를 바꿔 놓고 당겨받기/배치를 호출하면 한도 초과·타임아웃·멱등 재응답을 눈으로 확인할 수 있다.
 */
@RestController
@RequestMapping("/api/v1/mock-pg")
@ConditionalOnBean(MockPgClient.class)
public class MockPgController {

    private final MockPgClient mockPgClient;

    public MockPgController(MockPgClient mockPgClient) {
        this.mockPgClient = mockPgClient;
    }

    @GetMapping("/status")
    public MockPgStatusResponse status() {
        return new MockPgStatusResponse(mockPgClient.getDefaultScenario().name(),
                mockPgClient.transactions().size(), mockPgClient.approvedCount());
    }

    @PostMapping("/scenario")
    public MockPgStatusResponse setScenario(@Valid @RequestBody MockScenarioRequest request) {
        mockPgClient.setDefaultScenario(request.scenario());
        return status();
    }

    /** 다음 승인 요청 1건에만 적용할 시나리오. */
    @PostMapping("/scenario/next")
    public MockPgStatusResponse setNextScenario(@Valid @RequestBody MockScenarioRequest request) {
        mockPgClient.scriptNext(request.scenario());
        return status();
    }

    @PostMapping("/reset")
    public MockPgStatusResponse reset() {
        mockPgClient.reset();
        return status();
    }

    @GetMapping("/scenarios")
    public MockPgScenario[] scenarios() {
        return MockPgScenario.values();
    }
}
