package com.hoy.datumprikker

import com.hoy.datumprikker.exceptions.ConflictException
import com.hoy.datumprikker.model.*
import com.hoy.datumprikker.redis.RedisClient

import java.time.OffsetDateTime
import java.util.UUID

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.http.HttpHeaders
import io.vertx.core.json.Json
import io.vertx.core.json.JsonObject
import io.vertx.core.json.jackson.DatabindCodec
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.StaticHandler
import io.vertx.ext.web.openapi.RouterBuilder
import io.vertx.redis.client.Command
import io.vertx.redis.client.Request

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Tuple

class MainVerticle : AbstractVerticle() {
    private lateinit var redis: RedisClient

    companion object {
        init {
            // Configure Jackson to handle Java 8 date/time types
            val objectMapper = DatabindCodec.mapper()
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            objectMapper.disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
            objectMapper.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
            val module = JavaTimeModule()
            objectMapper.registerModule(module)

            // Add support for Kotlin data classes
            val kotlinModule = KotlinModule.Builder().build()
            objectMapper.registerModule(kotlinModule)
        }
    }

    override fun start(startPromise: Promise<Void>) {
        redis = RedisClient(vertx)
        val pool = dbConnect()

        createRouter(pool, redis)
            .compose { startServer(it) }
            .onSuccess { startPromise.complete() }
            .onFailure { startPromise.fail(it) }
    }

    /**
     * Creates a router with defined operations for handling polls.
     *
     * @param pool Database connection pool.
     * @param redis Redis client instance.
     * @return Router built from the provided operations.     
     */
    private fun createRouter(pool: Pool, redis: RedisClient) = RouterBuilder.create(vertx, "datumprikker.yaml")
        .map { routerBuilder ->
            routerBuilder.operation("listPolls").handler { rc ->
                val cacheKey = "all_polls"
                // Check if the list of polls is cached
                redis.getClient().send(Request.cmd(Command.GET).arg(cacheKey)) { res ->
                    if (res.succeeded()) {
                        val cached = res.result()
                        if (cached != null) {
                            // If cached, return the cached list
                            rc.response()
                                .setStatusCode(200)
                                .end(cached.toString())
                        } else {
                            // If not cached, fetch the list from the database and cache it
                            listPolls(pool, redis)
                                .onSuccess { polls ->
                                    val json = Json.encode(polls)
                                    redis.getClient().send(Request.cmd(Command.SET).arg(cacheKey).arg(json)) { res ->
                                        if (res.succeeded()) {
                                            rc.response()
                                                .setStatusCode(200)
                                                .end(json)
                                        } else {
                                            rc.fail(res.cause())
                                        }
                                    }
                                }
                                .onFailure { rc.fail(it) }
                        }
                    } else {
                        rc.fail(res.cause())
                    }
                }
            }

            routerBuilder.operation("createPolls").handler { rc ->
                val poll = rc.body().asJsonObject().mapTo(PollCreateRequest::class.java)
                savePoll(poll, pool, redis)
                    .onSuccess { id ->
                        rc.response()
                            .setStatusCode(201)
                            .end(JsonObject().put("pollId", id).encode())
                    }
                    .onFailure { rc.fail(it) }
            }

            routerBuilder.operation("getPoll").handler { rc ->
                val pollId = UUID.fromString(rc.pathParam("pollId"))
                getPoll(pollId, pool)
                    .onSuccess { poll ->
                        rc.response()
                            .setStatusCode(200)
                            .end(Json.encodeToBuffer(poll))
                    }
                    .onFailure { e ->
                        if (e is NoSuchElementException) rc.response().setStatusCode(404).end()
                        else rc.fail(e)
                    }
            }

            routerBuilder.operation("bookPollOption").handler { rc ->
                val pollId = UUID.fromString(rc.pathParam("pollId"))
                val booking = rc.body().asJsonObject().mapTo(PollBooking::class.java)
                bookPollOption(pollId, booking, pool)
                    .onSuccess { bookingId: UUID ->
                        rc.response()
                            .setStatusCode(201)
                            .end(JsonObject().put("bookingId", bookingId).encode())
                    }
                    .onFailure { e: Throwable ->
                        if (e is ConflictException) rc.response().setStatusCode(409).end()
                        else rc.fail(e)
                    }
            }

            routerBuilder.operation("resultPolls").handler { rc ->
                val pollId = UUID.fromString(rc.pathParam("pollId"))

                getBookings(pollId, pool)
                    .onSuccess { bookings: List<PollResult> ->
                        rc.response()
                            .setStatusCode(200)
                            .end(Json.encodeToBuffer(bookings))
                    }
                    .onFailure { rc.fail(it) }
            }

            val router = routerBuilder.createRouter()

            router.get("/docs/*").handler(createSwaggerHandler())
            router.route("/datumprikker.yaml").handler { rc ->
                rc.response()
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/yaml")
                    .sendFile("datumprikker.yaml")
            }

            router.route().failureHandler { ctx ->
                val statusCode = ctx.statusCode()
                val message = ctx.failure().message
                ctx.response()
                    .setStatusCode(statusCode)
                    .end(JsonObject().put("error", message).encode())
            }

            router
        }

    /**
     * Starts the HTTP server with the provided router.
     * 
     * @param router Router to handle incoming requests.
     * @return Future indicating the result of the server startup.     
     */
    private fun startServer(router: Router) = vertx.createHttpServer()
        .requestHandler(router)
        .listen(8080)
        .onComplete { http ->
            if (http.succeeded()) {
                println("HTTP server started on port 8080")
            } else {
                println("Failed to start HTTP server: ${http.cause()}")
            }
        }

    /**
     * Creates a connection pool to the PostgreSQL database.
     * 
     * @return Connection pool instance.
     */
    private fun dbConnect(): Pool {
        // Retrieve database connection details from environment variables
        val connectOptions = PgConnectOptions().apply {
            port = System.getenv("DB_PORT").toInt()
            host = System.getenv("DB_HOST")
            database = System.getenv("DB_NAME")
            user = System.getenv("DB_USER")
            password = System.getenv("DB_PASS")
        }

        val poolOptions = PoolOptions().apply {
            maxSize = 5
        }

        return Pool.pool(vertx, connectOptions, poolOptions)
    }

    /**
     * Creates a Swagger handler to serve API documentation.
     * 
     * @return StaticHandler instance for Swagger UI.
     */
    private fun createSwaggerHandler() =
        StaticHandler
            .create("webroot/swagger-ui")
            .setCachingEnabled(false)
            .setIndexPage("index.html")

    // Database operations
    fun listPolls(pool: Pool, redis: RedisClient) = pool.preparedQuery(
        """
    SELECT 
      p.id AS poll_id, 
      p.title, 
      p.description, 
      p.created_by, 
      po.id AS option_id, 
      po.begin_date_time, 
      po.end_date_time 
    FROM polls p 
    JOIN poll_options po ON p.id = po.poll_id
    """.trimIndent()
    ).execute()
        .map {
            it.groupBy { row -> row.getUUID("poll_id") }
                .map { (pollId, rows) ->
                    val pollRow = rows.first()
                    Poll(
                        id = pollId,
                        title = pollRow.getString("title"),
                        description = pollRow.getString("description"),
                        createdBy = pollRow.getString("created_by"),
                        options = rows.map { row ->
                            PollOption(
                                optionId = row.getUUID("option_id"),
                                beginDateTime = row.getOffsetDateTime("begin_date_time"),
                                endDateTime = row.getOffsetDateTime("end_date_time")
                            )
                        }.toTypedArray()
                    )
                }
        }

    fun savePoll(poll: PollCreateRequest, pool: Pool, redis: RedisClient) = pool.withTransaction { conn ->
        conn.preparedQuery(
            """
      INSERT INTO polls (title, description, created_by)
      VALUES ($1, $2, $3) 
      RETURNING id
      """.trimIndent()
        ).execute(Tuple.of(poll.title, poll.description, poll.createdBy))
            .flatMap { rows ->
                val pollId = rows.first().getUUID("id")
                conn.preparedQuery(
                    """
        INSERT INTO poll_options (poll_id, begin_date_time, end_date_time)
        VALUES ($1, $2, $3) 
        """.trimIndent()
                ).executeBatch(poll.options.map { Tuple.of(pollId, it.beginDateTime, it.endDateTime) })
                    .map { pollId }
            }
    }.compose { pollId ->
        redis.getClient().connect()
            .compose { redis ->
                redis.send(Request.cmd(Command.DEL).arg("all_polls"))
                    .onComplete { redis.close() }
            }
            .map { pollId }
    }

    fun getPoll(pollId: UUID, client: Pool) = client.preparedQuery(
        """
    SELECT 
      p.id AS poll_id, 
      p.title, 
      p.description, 
      p.created_by, 
      po.id AS option_id, 
      po.begin_date_time, 
      po.end_date_time 
    FROM polls p 
    JOIN poll_options po ON p.id = po.poll_id
    LEFT JOIN bookings b ON po.id = b.poll_option_id
    WHERE p.id = $1
    GROUP BY p.id, po.id
    HAVING COUNT(b.id) = 0
    """.trimIndent()
    ).execute(Tuple.of(pollId))
        .map {
            it.groupBy { row -> row.getUUID("poll_id") }
                .map { (pollId, rows) ->
                    val pollRow = rows.first()
                    Poll(
                        id = pollId,
                        title = pollRow.getString("title"),
                        description = pollRow.getString("description"),
                        createdBy = pollRow.getString("created_by"),
                        options = rows.map { row ->
                            PollOption(
                                optionId = row.getUUID("option_id"),
                                beginDateTime = row.getOffsetDateTime("begin_date_time"),
                                endDateTime = row.getOffsetDateTime("end_date_time")
                            )
                        }.toTypedArray()
                    )
                }.first()
        }

    fun bookPollOption(pollId: UUID, data: PollBooking, client: Pool) = client.preparedQuery(
        """
    INSERT INTO bookings (poll_id, poll_option_id, booked_by, email)
    VALUES ($1, $2, $3, $4)
    ON CONFLICT (poll_option_id) DO NOTHING
    RETURNING id
    """.trimIndent()
    ).execute(Tuple.of(pollId, data.optionId, data.name, data.email))
        .compose { res ->
            if (res.size() == 0) {
                Future.failedFuture(ConflictException("Option already booked"))
            } else {
                Future.succeededFuture(res.first().getUUID("id"))
            }
        }

    fun getBookings(pollId: UUID, client: Pool) = client.preparedQuery(
        """
    SELECT 
      b.booked_by, 
      po.begin_date_time, 
      po.end_date_time 
    FROM bookings b
    JOIN poll_options po ON b.poll_option_id = po.id 
    WHERE b.poll_id = $1
    """.trimIndent()
    ).execute(Tuple.of(pollId))
        .map { it.map { row -> row.toPollResult() } }
}
