package com.subflow.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 배치 스케줄러 활성화.
 *
 * <p>{@code subflow.scheduler.enabled=false} 면 스케줄링 자체를 켜지 않는다(테스트에서 배치가 끼어들지 않게).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "subflow.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
