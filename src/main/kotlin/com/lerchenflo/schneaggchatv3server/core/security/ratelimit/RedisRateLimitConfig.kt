package com.lerchenflo.schneaggchatv3server.core.security.ratelimit

import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.codec.ByteArrayCodec
import io.lettuce.core.codec.RedisCodec
import io.lettuce.core.codec.StringCodec
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
@EnableConfigurationProperties(RateLimitProperties::class)
class RedisRateLimitConfig {

    @Value("\${spring.data.redis.host:localhost}")
    private lateinit var redisHost: String

    @Value("\${spring.data.redis.port:6379}")
    private var redisPort: Int = 6379

    @Value("\${spring.data.redis.password:}")
    private lateinit var redisPassword: String

    @Bean(name = ["rateLimitRedisClient"], destroyMethod = "shutdown")
    fun rateLimitRedisClient(): RedisClient {
        val uriBuilder = RedisURI.builder()
            .withHost(redisHost)
            .withPort(redisPort)
            .withTimeout(Duration.ofSeconds(2))
        if (redisPassword.isNotEmpty()) uriBuilder.withPassword(redisPassword.toCharArray())
        return RedisClient.create(uriBuilder.build())
    }

    @Bean(name = ["rateLimitRedisConnection"], destroyMethod = "close")
    fun rateLimitRedisConnection(rateLimitRedisClient: RedisClient): StatefulRedisConnection<String, ByteArray> =
        rateLimitRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE))

    @Bean
    fun rateLimitProxyManager(
        rateLimitRedisConnection: StatefulRedisConnection<String, ByteArray>
    ): ProxyManager<String> =
        LettuceBasedProxyManager.builderFor(rateLimitRedisConnection).build()
}
