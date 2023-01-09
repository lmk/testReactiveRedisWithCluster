package com.example.testredis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.annotation.Resource;

@SpringBootTest
class TestRedisApplicationTests {

    @Resource
    ReactiveRedisOperations<String, String> redisTestOperations;

    @Test
    void contextLoads() {
    }

    @Test
    void testSet() {
        Mono<Boolean> result = redisTestOperations.opsForValue().set("key1", "value1");
        StepVerifier.create(result)
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void testGet() {
        Mono<String> fetchedValue = redisTestOperations.opsForValue().get("key1");

        StepVerifier.create(fetchedValue)
                .expectNext("value1")
                .verifyComplete();
    }

}
