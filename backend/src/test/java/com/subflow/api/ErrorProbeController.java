package com.subflow.api;

import com.subflow.common.error.EntityNotFoundException;
import com.subflow.common.error.InvalidStateTransitionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 예외 → HTTP 응답 매핑 검증용 테스트 컨트롤러.
 *
 * <p>테스트 슬라이스의 컴포넌트 스캔에 잡히도록 최상위 클래스로 둔다(중첩 클래스는 스캔 대상이 아니다).
 */
@RestController
@RequestMapping("/api/v1/test")
public class ErrorProbeController {

    @GetMapping("/missing")
    public void missing() {
        throw new EntityNotFoundException("구독", 42L);
    }

    @GetMapping("/conflict")
    public void conflict() {
        throw new InvalidStateTransitionException("만료된 구독은 활성화할 수 없습니다.");
    }

    @GetMapping("/boom")
    public void boom() {
        throw new IllegalStateException("내부 구현 상세: DB 커넥션 풀 고갈");
    }
}
