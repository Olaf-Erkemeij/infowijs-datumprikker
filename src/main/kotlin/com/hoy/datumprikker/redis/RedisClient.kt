package com.hoy.datumprikker.redis

import io.vertx.core.Vertx
import io.vertx.redis.client.Redis
import io.vertx.redis.client.RedisOptions

class RedisClient(private val vertx: Vertx) {
    private val redis: Redis by lazy {
        Redis.createClient(
            vertx,
            RedisOptions()
                .setConnectionString("redis://redis:6379")
                .setMaxPoolSize(20)
                .setMaxPoolWaiting(100)
        )
    }

    fun getClient() = redis
}