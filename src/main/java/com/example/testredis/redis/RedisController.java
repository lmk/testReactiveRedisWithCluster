package com.example.testredis.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;

@RequiredArgsConstructor
@RequestMapping("/redis")
@RestController
public class RedisController {

    @Resource
    ReactiveRedisOperations<String, String> redisTestOperations;

    @GetMapping("/get/{key}")
    public String get(@PathVariable String key) {

        String result = "";
        try {
            Mono<String> value = redisTestOperations.opsForValue().get(key);
            result = value.block();

        } catch(Exception e) {
            System.out.println(e);

        }

        return result;
    }

    @GetMapping("/set/{key}/{value}")
    public ResponseEntity set(@PathVariable String key, @PathVariable String value) {
        System.out.println(key + " values is " + value);
        Mono<Boolean> result = redisTestOperations.opsForValue().set(key, value);
        if (!result.block()) {
            return new ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity(HttpStatus.OK);
    }

    @GetMapping("/del/{key}")
    public ResponseEntity del(@PathVariable String key) {
        Mono<Boolean> result = redisTestOperations.opsForValue().delete(key);
        if (!result.block()) {
            return new ResponseEntity(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        return new ResponseEntity(HttpStatus.OK);
    }
}
