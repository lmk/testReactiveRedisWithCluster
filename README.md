# testReactiveRedisWithCluster

운영 환경에서 redis cluster 를 구성할때, master node 가 중지되는 경우를 대비하기 위해  
Master-Slave 구조로 replication cluster 구성하여, Master가 중지되면 Slave가 Master 역할을 대신합니다.

spring boot 기본 설정으로는 master가 중지되었을때 정상적으로 연동하지 못하는 문제가 있습니다.

spring boot 에서 redis 를 사용하기 위해 Lettuce를 사용하는데,
LettuceClientConfiguration 에서 ReadFrom 설정값을 ```UPSTREAM_PREFERRED``` 으로 변경해야합니다.
(https://lettuce.io/core/release/api/io/lettuce/core/ReadFrom.html)

redis cluster를 구성한 후, 기본값으로 설정할때 Redis의 key-value를 불러오지 못하는 것을 테스트하고, 
ReadFrom 설정값을 변경해서 Redis의 key-value를 불러오는 것을 테스트하겠습니다.

## 제약사항

- spring boot@2.5.4
- java@1.8
- ReactiveRedisConnectionFactory

여기서 Redis 연동은 webflux를 위해 ReactiveRedisConnectionFactory, ReactiveRedisOperations 를 사용했습니다.
다른 방법으로 연동하신다면, ReadFrom 값 설정 코드를 환경에 맞게 수정해야합니다.

## Redis Cluster 구성

코드 작성과 테스트를 하기위해 redis cluster를 먼저 구성합니다. 
간단하게 구성하기 위해 local에 docker-compose로 포트만 다르게 구성했습니다.
Redis instance를 총 6개 올려서 3 개로 데이터를 sharding 하고, 2 개씩 master-slave로 구성합니다.

7011, 7013, 7015로 sharding 되고 [7011-7012], [7013-7014], [7015-7016] 홀수는 master 짝수는 slave로 구성됩니다.

### Redis 설치

#### docker-compose.yml

```yaml
version: "3"
services:
  redis-node-01:
    platform: linux/x86_64
    image: redis:6.2
    container_name: redis01
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf --port 7011
    ports:
      - 7011:7011
      - 7012:7012
      - 7013:7013
      - 7014:7014
      - 7015:7015
      - 7016:7016

  redis-node-02:
    network_mode: "service:redis-node-01"
    platform: linux/x86_64
    image: redis:6.2
    container_name: redis02
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf --port 7012

  redis-node-03:
    network_mode: "service:redis-node-01"
    platform: linux/x86_64
    image: redis:6.2
    container_name: redis03
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf --port 7013

  redis-node-04:
    network_mode: "service:redis-node-01"
    platform: linux/x86_64
    image: redis:6.2
    container_name: redis04
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf --port 7014

  redis-node-05:
    network_mode: "service:redis-node-01"
    platform: linux/x86_64
    image: redis:6.2
    container_name: redis05
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf --port 7015
 
  redis-node-06:
    network_mode: "service:redis-node-01"
    platform: linux/x86_64
    image: redis:6.2
    container_name: redis06
    volumes:
      - ./conf/redis.conf:/usr/local/etc/redis/redis.conf
    command: redis-server /usr/local/etc/redis/redis.conf --port 7016
```

```bash
$ docker-compose up -d
[+] Running 7/7
 ⠿ Network redis-cluster_default  Created                            0.0s
 ⠿ Container redis01              Started                            0.8s
 ⠿ Container redis06              Started                            0.9s
 ⠿ Container redis02              Started                            1.0s
 ⠿ Container redis04              Started                            0.9s
 ⠿ Container redis03              Started                            0.9s
 ⠿ Container redis05              Started                            0.9s
 
$ docker ps
CONTAINER ID   IMAGE       COMMAND                  CREATED         STATUS         PORTS                                        NAMES
3205e7ea811a   redis:6.2   "docker-entrypoint.s…"   3 seconds ago   Up 2 seconds                                                redis04
1e46540732a7   redis:6.2   "docker-entrypoint.s…"   3 seconds ago   Up 2 seconds                                                redis06
c685970d2dd1   redis:6.2   "docker-entrypoint.s…"   3 seconds ago   Up 2 seconds                                                redis02
623b3de74e02   redis:6.2   "docker-entrypoint.s…"   3 seconds ago   Up 2 seconds                                                redis05
c762962671b8   redis:6.2   "docker-entrypoint.s…"   3 seconds ago   Up 2 seconds                                                redis03
faad041b15ae   redis:6.2   "docker-entrypoint.s…"   3 seconds ago   Up 2 seconds   6379/tcp, 0.0.0.0:7011-7016->7011-7016/tcp   redis01
```

#### Redis Cluster 구성

rc.sh 라는 shell script를 하나 만들어서 실행합니다.

```bash
function cluster_flushall() {
  echo flushall
  redis-cli -c -p 7011 flushall
  redis-cli -c -p 7012 flushall
  redis-cli -c -p 7013 flushall
  redis-cli -c -p 7014 flushall
  redis-cli -c -p 7015 flushall
  redis-cli -c -p 7016 flushall
}

function cluster_reset() {
  echo cluster reset
  redis-cli -c -p 7011 cluster reset
  redis-cli -c -p 7012 cluster reset
  redis-cli -c -p 7013 cluster reset
  redis-cli -c -p 7014 cluster reset
  redis-cli -c -p 7015 cluster reset
  redis-cli -c -p 7016 cluster reset
}

if [ "$1" == "shard" ]; then

  cluster_flushall
  cluster_reset

  echo cluster addslots
  redis-cli -c -p 7011 cluster addslots {0..5461}
  redis-cli -c -p 7013 cluster addslots {5462..10923}
  redis-cli -c -p 7015 cluster addslots {10924..16383}

  echo cluster meet
  redis-cli -c -p 7011 cluster meet 127.0.0.1 7011
  redis-cli -c -p 7011 cluster meet 127.0.0.1 7012
  redis-cli -c -p 7011 cluster meet 127.0.0.1 7013
  redis-cli -c -p 7011 cluster meet 127.0.0.1 7014
  redis-cli -c -p 7011 cluster meet 127.0.0.1 7015
  redis-cli -c -p 7011 cluster meet 127.0.0.1 7016

  sleep 1

  echo cluster replicate
  redis-cli -c -p 7012 cluster replicate `redis-cli -c -p 7011 cluster nodes | grep ":7011" | awk '{print $1}'`
  redis-cli -c -p 7014 cluster replicate `redis-cli -c -p 7013 cluster nodes | grep ":7013" | awk '{print $1}'`
  redis-cli -c -p 7016 cluster replicate `redis-cli -c -p 7015 cluster nodes | grep ":7015" | awk '{print $1}'`

  sleep 1

fi

redis-cli -c -p 7011 cluster nodes
echo ""
redis-cli -c -p 7011 cluster info
```

최종 결과는 아래와 같습니다.

```bash
$ redis-cli -p 7011 cluster nodes
ad01ed0c7b0399955edbb078e7429c220b0ef6a6 127.0.0.1:7015@17015 master - 0 1673242843730 2 connected 10924-16383
f1ae9b0317c8b7789fa7266e30dcfae064f74653 127.0.0.1:7016@17016 slave ad01ed0c7b0399955edbb078e7429c220b0ef6a6 0 1673242843000 2 connected
1b548aa350de181bf31f2fd6d660516d1ce4ad17 127.0.0.1:7012@17012 slave 1853d99ca68e8473febd6920dead5e1beb0ea965 0 1673242842000 1 connected
8037ac6a7ed2b5bd26f2a781b63ab378d45ac19c 127.0.0.1:7014@17014 slave 216c5676c9a88d97f1be2d2f9eb35a5a8978fed7 0 1673242839000 15 connected
216c5676c9a88d97f1be2d2f9eb35a5a8978fed7 127.0.0.1:7013@17013 master - 0 1673242842726 15 connected 5462-10923
1853d99ca68e8473febd6920dead5e1beb0ea965 127.0.0.1:7011@17011 myself,master - 0 1673242841000 1 connected 0-5461
```

#### key 값 저장

```key1:value1```을 저장하고 조회 해보면, 7013 node에 저장된 것을 알 수 있습니다.

```bash
$ redis-cli -c -p 7011 set "key1" "value1"
OK
$ redis-cli -p 7011 get "key1"
(error) MOVED 9189 127.0.0.1:7013
$ redis-cli -p 7013 get "key1"
"value1"
```

### Restful 코드 작성

redis 연동을 위한 java 기본 설정의 코드를 작성하고, 연동 테스트를 해보겠습니다.

#### application.yml

node 목록을 한줄로 넣어야 합니다. 
하이픈(-)으로 구분해서 여러줄로 넣었더니 ```@Value``` 프로퍼티에서 제대로 읽지를 못하네요.

```yaml
server:
  port: 8081
spring:
  redis:
    cluster:
      nodes: 127.0.0.1:7011, 127.0.0.1:7013, 127.0.0.1:7015
```

#### ReactiveRedisConfig.java

```java
@Configuration
public class ReactiveRedisConfig {

    @Value("${spring.redis.cluster.nodes}")
    private List<String> clusterNodes;

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
```

#### RedisController.java

rest 콘트롤러를 아래와 같이 코딩합니다.

```java

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
}

```

### 기본 설정 테스트

1. Application 기동

```application.yml``` 파일에서 master 노드 3개만 넣었지만 6개 정보를 모두 갖고있습니다.

```log
[DEBUG] factory nodes [127.0.0.1:7012, 127.0.0.1:7016, 127.0.0.1:7014, 127.0.0.1:7013, 127.0.0.1:7011, 127.0.0.1:7015]
```
2. rest API를 연동하면 'value1' 을 정상적으로 읽어 옵니다.

```http request
http://localhost:8081/redis/get/key1

HTTP/1.1 200 
Content-Type: text/plain;charset=UTF-8
Content-Length: 6
Date: Mon, 09 Jan 2023 08:25:57 GMT
Keep-Alive: timeout=60
Connection: keep-alive

value1

Response code: 200; Time: 33ms (33 ms); Content length: 6 bytes (6 B)
```

3. 7013 master 노드 중지 합니다.

7013 컨테이너를 중지하고 cluster 상태를 조회하면, 
7013은 disconnect 상태이고 7014가 master가 된 것을 알 수 있습니다.

```bash
$ docker ps
CONTAINER ID   IMAGE       COMMAND                  CREATED      STATUS          PORTS                                        NAMES
3205e7ea811a   redis:6.2   "docker-entrypoint.s…"   4 days ago   Up 4 days                                                    redis04
1e46540732a7   redis:6.2   "docker-entrypoint.s…"   4 days ago   Up 4 days                                                    redis06
c685970d2dd1   redis:6.2   "docker-entrypoint.s…"   4 days ago   Up 4 days                                                    redis02
623b3de74e02   redis:6.2   "docker-entrypoint.s…"   4 days ago   Up 4 days                                                    redis05
c762962671b8   redis:6.2   "docker-entrypoint.s…"   4 days ago   Up 11 minutes                                                redis03
faad041b15ae   redis:6.2   "docker-entrypoint.s…"   4 days ago   Up 4 days       6379/tcp, 0.0.0.0:7011-7016->7011-7016/tcp   redis01
$ docker stop redis03
redis03
$ redis-cli -p 7011 cluster nodes
ad01ed0c7b0399955edbb078e7429c220b0ef6a6 127.0.0.1:7015@17015 master - 0 1673253300896 2 connected 10924-16383
f1ae9b0317c8b7789fa7266e30dcfae064f74653 127.0.0.1:7016@17016 slave ad01ed0c7b0399955edbb078e7429c220b0ef6a6 0 1673253300000 2 connected
1b548aa350de181bf31f2fd6d660516d1ce4ad17 127.0.0.1:7012@17012 slave 1853d99ca68e8473febd6920dead5e1beb0ea965 0 1673253301000 1 connected
8037ac6a7ed2b5bd26f2a781b63ab378d45ac19c 127.0.0.1:7014@17014 master - 0 1673253301902 18 connected 5462-10923
216c5676c9a88d97f1be2d2f9eb35a5a8978fed7 127.0.0.1:7013@17013 master,fail - 1673253154082 1673253150062 17 disconnected
1853d99ca68e8473febd6920dead5e1beb0ea965 127.0.0.1:7011@17011 myself,master - 0 1673253299000 1 connected 0-5461
```

값을 조회해보면 7014에서 조회가능합니다.

```bash
$ redis-cli -p 7011 get "key1"
(error) MOVED 9189 127.0.0.1:7014
$ redis-cli -p 7014 get "key1"
"value1"
```

4. rest API를 연동하면 'value1' 을 정상적으로 읽어 오지 못합니다.

rest API 연동을 해보면, 200 OK 지만, 값을 제대로 가지고오지 못하고(Response body is empty), 
application을 재기동해야 정상적으로 값을 가져옵니다.

```http request
http://localhost:8081/redis/get/key1

HTTP/1.1 200 
Content-Type: text/plain;charset=UTF-8
Content-Length: 0
Date: Mon, 09 Jan 2023 08:44:16 GMT
Keep-Alive: timeout=60
Connection: keep-alive

<Response body is empty>

Response code: 200; Time: 60014ms (1 m 0 s 14 ms); Content length: 0 bytes (0 B)
```

5. 동일한 테스트를 위해 7013을 master로 구성합니다.

```bash
$ docker start redis03
redis03
$ redis-cli -p 7013 cluster failover
OK
$ redis-cli -p 7011 cluster nodes
ad01ed0c7b0399955edbb078e7429c220b0ef6a6 127.0.0.1:7015@17015 master - 0 1673254127178 2 connected 10924-16383
f1ae9b0317c8b7789fa7266e30dcfae064f74653 127.0.0.1:7016@17016 slave ad01ed0c7b0399955edbb078e7429c220b0ef6a6 0 1673254125170 2 connected
1b548aa350de181bf31f2fd6d660516d1ce4ad17 127.0.0.1:7012@17012 slave 1853d99ca68e8473febd6920dead5e1beb0ea965 0 1673254126000 1 connected
8037ac6a7ed2b5bd26f2a781b63ab378d45ac19c 127.0.0.1:7014@17014 slave 216c5676c9a88d97f1be2d2f9eb35a5a8978fed7 0 1673254126173 19 connected
216c5676c9a88d97f1be2d2f9eb35a5a8978fed7 127.0.0.1:7013@17013 master - 0 1673254127000 19 connected 5462-10923
1853d99ca68e8473febd6920dead5e1beb0ea965 127.0.0.1:7011@17011 myself,master - 0 1673254125000 1 connected 0-5461
$ redis-cli -p 7011 get "key1"
(error) MOVED 9189 127.0.0.1:7013
```

### 설정 변경 코드 작성

ReactiveRedisConfig class에 reactiveRedisConnectionFactory Bean을 추가 합니다.

#### ReactiveRedisConfig.java

readFrom 설정하나를 추가하는데, RedisClusterConfiguration 만들어줘야해서 코드가 길어지네요.

```java
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
```

### 변경 설정 테스트

1. Application 기동

2. rest API를 연동하면 'value1' 을 정상적으로 읽어 옵니다.

```http request
http://localhost:8081/redis/get/key1

HTTP/1.1 200 
Content-Type: text/plain;charset=UTF-8
Content-Length: 6
Date: Mon, 09 Jan 2023 08:55:57 GMT
Keep-Alive: timeout=60
Connection: keep-alive

value1

Response code: 200; Time: 172ms (172 ms); Content length: 6 bytes (6 B)
```

3. 7013 master 노드 중지 합니다.

4. rest API를 연동하면 정상적으로 값을 가져옵니다.

```http request
http://localhost:8081/redis/get/key1

HTTP/1.1 200 
Content-Type: text/plain;charset=UTF-8
Content-Length: 6
Date: Mon, 09 Jan 2023 08:57:55 GMT
Keep-Alive: timeout=60
Connection: keep-alive

value1

Response code: 200; Time: 8ms (8 ms); Content length: 6 bytes (6 B)
```

## github

전체 코드는 아래 주소에서 받을 수 있습니다.
> https://github.com/lmk/testReactiveRedisWithCluster

