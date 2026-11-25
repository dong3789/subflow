package com.subflow.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 클라이언트 구성.
 *
 * <p>Redis 를 실제로 쓰는 구성({@code subflow.lock.provider=redis})에서만 만든다.
 * 이렇게 분리해 두면 테스트·로컬에서 Redis 없이 인메모리 락으로 결제 코어를 검증할 수 있다.
 */
@Configuration
@ConditionalOnProperty(name = "subflow.lock.provider", havingValue = "redis", matchIfMissing = true)
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${spring.data.redis.host:localhost}") String host,
                                         @Value("${spring.data.redis.port:6379}") int port,
                                         @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        var singleServer = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8)
                .setTimeout(2000);
        if (password != null && !password.isBlank()) {
            singleServer.setPassword(password);
        }
        return Redisson.create(config);
    }
}
