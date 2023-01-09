package com.example.testredis.redis;

import io.lettuce.core.ReadFrom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisOperations;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class ReactiveRedisConfig {

    @Value("${spring.redis.cluster.nodes}")
    private List<String> clusterNodes;

    @Bean
    @Primary
    public ReactiveRedisConnectionFactory reactiveRedisConnectionFactory() {

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .readFrom(ReadFrom.UPSTREAM_PREFERRED)
            .build();

        RedisClusterConfiguration redisClusterConfiguration = new RedisClusterConfiguration();

        clusterNodes.forEach(node -> {
            System.out.println("config getNodes " + node);

            String[] url = node.split(":");
            redisClusterConfiguration.clusterNode(url[0], Integer.parseInt(url[1]));
        });

        ReactiveRedisConnectionFactory factory = new LettuceConnectionFactory(redisClusterConfiguration, clientConfig);

        return factory;
    }

    @Bean
    public ReactiveRedisOperations<String, String> redisTestOperations(ReactiveRedisConnectionFactory factory) {

        Flux<RedisClusterNode> flux = factory.getReactiveClusterConnection().clusterGetNodes();
        System.out.println("[DEBUG] factory nodes " + flux.toStream().collect(Collectors.toList()));

        RedisSerializer<String> serializer = new StringRedisSerializer();
        RedisSerializationContext<String, String> serializationContext = RedisSerializationContext
                .<String, String>newSerializationContext()
                .key(serializer)
                .value(serializer)
                .hashKey(serializer)
                .hashValue(serializer)
                .build();

        return new ReactiveRedisTemplate<>(factory, serializationContext);
    }
}
