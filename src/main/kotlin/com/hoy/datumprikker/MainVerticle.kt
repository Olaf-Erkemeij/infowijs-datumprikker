package com.hoy.datumprikker

import com.hoy.datumprikker.model.*

import java.util.UUID
import java.time.OffsetDateTime

import io.vertx.core.AbstractVerticle
import io.vertx.core.Promise
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.Json
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.openapi.RouterBuilder;
import io.vertx.ext.web.handler.ResponseContentTypeHandler
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestParameters
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.core.json.jackson.DatabindCodec
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule

import io.vertx.pgclient.PgConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.*

class MainVerticle : AbstractVerticle() {
  companion object {
    init {
        val objectMapper = DatabindCodec.mapper()
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        objectMapper.disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
        objectMapper.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
        val module = JavaTimeModule()
        objectMapper.registerModule(module)
    }
  }

  override fun start(startPromise: Promise<Void>) {
    val pool = dbConnect()

    createRouter(pool)
      .compose { startServer(it) }
      .onSuccess { startPromise.complete() }
      .onFailure { startPromise.fail(it) }
  }

  private fun createRouter(pool: Pool) = RouterBuilder.create(vertx, "datumprikker.yaml")
      .map { routerBuilder ->
        routerBuilder.operation("listPolls").handler { rc ->
          listPolls(pool)
            .onSuccess { polls ->
              println("Polls: $polls")
              rc.response()
                .setStatusCode(200)
                .end(Json.encodeToBuffer(polls))
            }
            .onFailure { rc.fail(it) }
        }

        routerBuilder.operation("createPolls").handler { rc ->
          val poll = rc.body().asJsonObject().mapTo(Poll::class.java)
          savePoll(poll, pool)
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
              println("Booking saved with id: $bookingId")
              rc.response()
                .setStatusCode(201)
                .end(JsonObject().put("bookingId", bookingId).encode())
            }
            .onFailure { rc.fail(it) }
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

        routerBuilder.createRouter()
      }

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

  private fun dbConnect(): Pool {
    val connectOptions = PgConnectOptions().apply {
      port = 5432
      host = "localhost"
      database = "mydb"
      user = "myuser"
      password = "mypassword"
    }

    val poolOptions = PoolOptions().apply {
      maxSize = 5
    }

    return Pool.pool(vertx, connectOptions, poolOptions)
  }

  fun listPolls(pool: Pool) = pool.query("SELECT * FROM polls")
    .execute()
    .map { it.map { row -> row.toPoll() } }

  fun savePoll(poll: Poll, pool: Pool) = pool.withTransaction { conn ->
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
    WHERE p.id = $1
    """.trimIndent()
  ).execute(Tuple.of(pollId))
  .map { it.groupBy { row -> row.getUUID("poll_id") }
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
    INSERT INTO bookings (poll_id, poll_option_id, booked_by) 
    VALUES ($1, $2, $3) 
    RETURNING id
    """.trimIndent()
  ).execute(Tuple.of(pollId, data.optionId, data.name))
  .map { it.first().getUUID("id") }

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
