// package com.hoy.datumprikker

// import com.hoy.datumprikker.model.Poll
// import com.hoy.datumprikker.model.PollOption
// import com.hoy.datumprikker.model.PollBooking
// import com.hoy.datumprikker.model.PollResult

// import java.util.UUID
// import java.time.OffsetDateTime

// import io.vertx.core.AbstractVerticle
// import io.vertx.core.Promise
// import io.vertx.core.Vertx;
// import io.vertx.core.http.HttpHeaders;
// import io.vertx.core.http.HttpServer;
// import io.vertx.core.http.HttpServerOptions;
// import io.vertx.core.json.Json
// import io.vertx.core.json.JsonArray;
// import io.vertx.core.json.JsonObject;
// import io.vertx.ext.web.Router;
// import io.vertx.ext.web.openapi.RouterBuilder;
// import io.vertx.ext.web.validation.RequestParameters;
// import io.vertx.ext.web.validation.ValidationHandler;
// import io.vertx.core.json.jackson.DatabindCodec;
// import com.fasterxml.jackson.databind.DeserializationFeature
// import com.fasterxml.jackson.databind.SerializationFeature
// import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule

// import io.vertx.pgclient.PgConnectOptions
// import io.vertx.pgclient.PgPool
// import io.vertx.sqlclient.Pool
// import io.vertx.sqlclient.*

// class MainVerticle : AbstractVerticle() {
//   companion object {
//     init {
//         val objectMapper = DatabindCodec.mapper()
//         objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
//         objectMapper.disable(SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS)
//         objectMapper.disable(DeserializationFeature.READ_DATE_TIMESTAMPS_AS_NANOSECONDS)
//         val module = JavaTimeModule()
//         objectMapper.registerModule(module)
//     }
//   }

//   override fun start(startPromise: Promise<Void>) {
//     val pool = dbConnect()

//     createRouter(pool)
//       .onSuccess { router ->
//         startServer(router)
//           .onComplete(startPromise)
//       }
//       .onFailure { startPromise.fail(it) }
//   }

//   private fun createRouter(pool: Pool) = RouterBuilder.create(vertx, "datumprikker.yaml")
//       .onSuccess { routerBuilder ->
//         routerBuilder.rootHandler(ResponseContentTypeHandler.create())

//         routerBuilder.operation("listPolls").handler { rc ->
//           listPolls(pool)
//             .onSuccess { polls ->
//               rc.response()
//                 .setStatusCode(200)
//                 .end(Json.encodeToBuffer(polls))
//             }
//             .onFailure { rc.fail(it) }
//         }

//         routerBuilder.operation("createPolls").handler { rc ->
//           val poll = rc.bodyAsJson.mapTo(Poll::class.java)
//           savePoll(poll, pool)
//             .onSuccess { id ->
//               rc.response()
//                 .setStatusCode(201)
//                 .end(JsonObject().put("pollId", id).encode())
//             }
//             .onFailure { rc.fail(it) }
//         }

//         routerBuilder.operation("getPoll").handler { rc ->
//           val pollId = UUID.fromString(rc.pathParam("pollId"))
//           getPoll(pollId, dbPool)
//             .onSuccess { poll ->
//               rc.response()
//                 .setStatusCode(200)
//                 .end(Json.encodeToBuffer(poll))
//             }
//             .onFailure { e ->
//               if (e is NoSuchElementException) rc.response().setStatusCode(404).end()
//               else rc.fail(e)
//             }
//         }

//         routerBuilder.operation("bookPollOption").handler { rc ->
//           val pollId = UUID.fromString(rc.pathParam("pollId"))
//           val booking = rc.bodyAsJson.mapTo(PollBooking::class.java)
//           bookPollOption(pollId, booking, pool)
//             .onSuccess { bookingId: UUID ->
//               println("Booking saved with id: $bookingId")
//               rc.response()
//                 .setStatusCode(201)
//                 .end(JsonObject().put("bookingId", bookingId).encode())
//             }
//             .onFailure { rc.fail(it) }
//         }

//         routerBuilder.operation("resultPolls").handler { rc ->
//           val pollId = UUID.fromString(routingContext.pathParam("pollId"))

//           getBookings(pollId, dbPool)
//             .onSuccess { bookings: List<PollResult> ->
//               routingContext.response()
//                 .setStatusCode(200)
//                 .end(Json.encodeToBuffer(bookings))
//             }
//             .onFailure { rc.fail(it) }
//         }

//         val router = routerBuilder.createRouter()
//         vertx.createHttpServer()
//           .requestHandler(router)
//           .listen(8080)
//           .onComplete { http ->
//             if (http.succeeded()) {
//               startPromise.complete()
//               println("HTTP server started on port 8080")
//             } else {
//               startPromise.fail(http.cause());
//             }
//           }
//       }
//       .onFailure { failure ->
//         startPromise.fail(failure)
//       }
//   }

//   private fun startServer(router: Router) = vertx.createHttpServer()
//     .requestHandler(router)
//     .listen(8080)
//     .onComplete { http ->
//       if (http.succeeded()) {
//         println("HTTP server started on port 8080")
//       } else {
//         println("Failed to start HTTP server: ${http.cause()}")
//       }
//     }

//   private fun dbConnect(): Pool {
//     val connectOptions = PgConnectOptions()
//       .setPort(5432)
//       .setHost("localhost")
//       .setDatabase("mydb")
//       .setUser("myuser")
//       .setPassword("mypassword")

//     val poolOptions = PoolOptions().setMaxSize(5)

//     return Pool.pool(vertx, connectOptions, poolOptions)
//   }

//   fun listPolls(client: Pool) = client.query("SELECT * FROM polls")
//     .execute()
//     .map { it.map { row ->
//       Poll(
//         id = row.getUUID("id"),
//         title = row.getString("title"),
//         description = row.getString("description"),
//         createdBy = row.getString("created_by")
//       )
//     } }

//   fun savePoll(poll: Poll, pool: Pool) = pool.withTransaction { conn ->
//     conn.preparedQuery("""
//         INSERT INTO polls (title, description, created_by)
//         VALUES ($1, $2, $3) RETURNING id
//     """).execute(Tuple.of(poll.title, poll.description, poll.createdBy))
//     .flatMap { rows ->
//       val pollId = rows.first().getUUID("id")
//       conn.preparedQuery("""
//         INSERT INTO poll_options (poll_id, begin_date_time, end_date_time)
//         VALUES ($1, $2, $3) RETURNING id
//       """).executeBatch(poll.options.map { Tuple.of(pollId, it.beginDateTime, it.endDateTime) })
//       .map { pollId }
//     }
//   }

//   fun getPoll(pollId: UUID, client: Pool) = client.preparedQuery(
//     "SELECT polls.id AS poll, title, description, created_by, poll_options.id, begin_date_time, end_date_time FROM polls JOIN poll_options ON polls.id = poll_options.poll_id WHERE polls.id = $1"
//   ).execute(Tuple.of(pollId))
//   .map { it.groupBy { row -> row.getUUID("poll") }
//     .map { (pollId, rows) ->
//       val pollRow = rows.first()
//       Poll(
//         id = pollId,
//         title = pollRow.getString("title"),
//         description = pollRow.getString("description"),
//         createdBy = pollRow.getString("created_by"),
//         options = rows.map { row ->
//           PollOption(
//             optionId = row.getUUID("id"),
//             beginDateTime = row.getOffsetDateTime("begin_date_time"),
//             endDateTime = row.getOffsetDateTime("end_date_time")
//           )
//         }.toTypedArray()
//       )
//     }.first()
//   }

//   fun getPollOptions(pollId: UUID, client: Pool) = client.preparedQuery(
//     "SELECT * FROM poll_options WHERE poll_id = $1"
//   ).execute(Tuple.of(pollId))
//   .map { it.map { row ->
//     PollOption(
//       optionId = row.getUUID("id"),
//       pollId = row.getUUID("poll_id"),
//       beginDateTime = row.getOffsetDateTime("begin_date_time"),
//       endDateTime = row.getOffsetDateTime("end_date_time")
//     )
//   } }

//   fun bookPollOption(pollId: UUID, data: PollBooking, client: Pool) = client.preparedQuery(
//     "INSERT INTO booking (poll_id, poll_option_id, booked_by) VALUES ($1, $2, $3) RETURNING id"
//   ).execute(Tuple.of(pollId, data.optionId, data.name))
//   .map { it.iterator().next().getUUID("id") }

//   fun getBookings(pollId: UUID, client: Pool) = client.preparedQuery(
//     "SELECT booked_by, begin_date_time, end_date_time FROM bookings JOIN poll_options ON bookings.poll_option_id = poll_options.id WHERE bookings.poll_id = $1"
//   ).execute(Tuple.of(pollId))
//   .map { it.map { row ->
//     PollResult(
//       name = row.getString("booked_by"),
//       beginDateTime = row.getOffsetDateTime("begin_date_time"),
//       endDateTime = row.getOffsetDateTime("end_date_time"),
//     )
//   } }
// }
// package com.hoy.datumprikker

// import com.hoy.datumprikker.model.Poll
// import com.hoy.datumprikker.model.PollOption
// import com.hoy.datumprikker.model.PollBooking
// import com.hoy.datumprikker.model.PollResult

// import java.util.UUID
// import java.time.OffsetDateTime

// import io.vertx.core.AbstractVerticle
// import io.vertx.core.Promise
// import io.vertx.core.Vertx;
// import io.vertx.core.http.HttpHeaders;
// import io.vertx.core.http.HttpServer;
// import io.vertx.core.http.HttpServerOptions;
// import io.vertx.core.json.Json
// import io.vertx.core.json.JsonArray;
// import io.vertx.core.json.JsonObject;
// import io.vertx.ext.web.Router;
// import io.vertx.ext.web.openapi.RouterBuilder;
// import io.vertx.ext.web.validation.RequestParameters;
// import io.vertx.ext.web.validation.ValidationHandler;
// import io.vertx.core.json.jackson.DatabindCodec;
// import com.fasterxml.jackson.databind.DeserializationFeature
// import com.fasterxml.jackson.databind.SerializationFeature
// import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
// import com.fasterxml.jackson.module.kotlin.KotlinModule

// import io.vertx.pgclient.PgConnectOptions
// import io.vertx.pgclient.PgPool
// import io.vertx.sqlclient.Pool
// import io.vertx.sqlclient.*

// class MainVerticle : AbstractVerticle() {
//   companion object {
//     init {
//       val objectMapper = DatabindCodec.mapper()
//       objectMapper.apply {
//           registerModule(JavaTimeModule())
//           registerModule(KotlinModule.Builder().build())
//           disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
//           disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
//           disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
//       }
//     }
//   }

//   override fun start(startPromise: Promise<Void>) {
//     val dbPool = configurePool()
//     createRouter(dbPool)
//       .compose { startServer(it) }
//       .onComplete(startPromise)
//   }

//   private fun configurePool(): Pool {
//     val connectOptions = PgConnectOptions()
//       .setPort(5432)
//       .setHost("localhost")
//       .setDatabase("mydb")
//       .setUser("myuser")
//       .setPassword("mypassword")
  
//     Pool.pool(vertx, connectOptions, PoolOptions().setMaxSize(5))
//   }

//   private fun createRouter(pool: Pool) = RouterBuilder.create(vertx, "datumprikker.yaml").map { routerBuilder ->
//     routerBuilder.rootHandler(ResponseContentTypeHandler.create())

//     // List polls
//     routerBuilder.operation("listPolls").handler { rc ->
//         listPolls(pool)
//             .onSuccess { polls ->
//                 rc.response()
//                     .setStatusCode(200)
//                     .end(Json.encodeToBuffer(polls))
//             }
//             .onFailure { rc.fail(it) }
//     }

//     // Create poll
//     routerBuilder.operation("createPolls").handler { rc ->
//         val poll = rc.bodyAsJson.mapTo(Poll::class.java)
//         savePoll(poll, pool)
//             .onSuccess { id ->
//                 rc.response()
//                     .setStatusCode(201)
//                     .putHeader(HttpHeaders.LOCATION, "/polls/$id")
//                     .end(JsonObject().put("pollId", id.toString()).toBuffer())
//             }
//             .onFailure { rc.fail(it) }
//     }

//     // Get poll details
//     routerBuilder.operation("getPoll").handler { rc ->
//         val pollId = UUID.fromString(rc.pathParam("pollId"))
//         getPoll(pollId, pool)
//             .onSuccess { poll ->
//                 rc.response()
//                     .setStatusCode(200)
//                     .end(Json.encodeToBuffer(poll))
//             }
//             .onFailure { e ->
//                 if (e is NoSuchElementException) rc.response().setStatusCode(404).end()
//                 else rc.fail(e)
//             }
//     }

//     // Book poll option
//     routerBuilder.operation("bookPollOption").handler { rc ->
//         val pollId = UUID.fromString(rc.pathParam("pollId"))
//         val booking = rc.bodyAsJson.mapTo(PollBooking::class.java)
        
//         bookPollOption(pollId, booking, pool)
//             .onSuccess { bookingId ->
//                 rc.response()
//                     .setStatusCode(201)
//                     .putHeader(HttpHeaders.LOCATION, "/bookings/$bookingId")
//                     .end(JsonObject().put("bookingId", bookingId.toString()).toBuffer())
//             }
//             .onFailure { rc.fail(it) }
//     }

//     // Get poll results
//     routerBuilder.operation("resultPolls").handler { rc ->
//         val pollId = UUID.fromString(rc.pathParam("pollId"))
//         getPollResults(pollId, pool)
//             .onSuccess { results ->
//                 rc.response()
//                     .setStatusCode(200)
//                     .end(Json.encodeToBuffer(results))
//             }
//             .onFailure { rc.fail(it) }
//     }

//     routerBuilder.createRouter()
//   }

//   private fun startServer(router: Router) = vertx.createHttpServer()
//     .requestHandler(router)
//     .listen(8080)
//     .map { null }

//   fun listPolls(client: Pool) = client.query("SELECT * FROM polls")
//     .execute()
//     .map { it.map { row ->
//       Poll(
//         id = row.getUUID("id"),
//         title = row.getString("title"),
//         description = row.getString("description"),
//         createdBy = row.getString("created_by")
//       )
//     } }

//   fun savePoll(poll: Poll, pool: Pool) = pool.withTransaction { conn ->
//     conn.preparedQuery("""
//         INSERT INTO polls (title, description, created_by)
//         VALUES ($1, $2, $3) RETURNING id
//     """).execute(Tuple.of(poll.title, poll.description, poll.createdBy))
//     .flatMap { rows ->
//       val pollId = rows.first().getUUID("id")
//       conn.preparedQuery("""
//         INSERT INTO poll_options (poll_id, begin_date_time, end_date_time)
//         VALUES ($1, $2, $3) RETURNING id
//       """).executeBatch(poll.options.map { Tuple.of(pollId, it.beginDateTime, it.endDateTime) })
//       .map { pollId }
//     }
//   }

//   fun getPoll(pollId: UUID, client: Pool) = client.preparedQuery(
//     "SELECT polls.id AS poll, title, description, created_by, poll_options.id, begin_date_time, end_date_time FROM polls JOIN poll_options ON polls.id = poll_options.poll_id WHERE polls.id = $1"
//   ).execute(Tuple.of(pollId))
//   .map { it.groupBy { row -> row.getUUID("poll") }
//     .map { (pollId, rows) ->
//       val pollRow = rows.first()
//       Poll(
//         id = pollId,
//         title = pollRow.getString("title"),
//         description = pollRow.getString("description"),
//         createdBy = pollRow.getString("created_by"),
//         options = rows.map { row ->
//           PollOption(
//             optionId = row.getUUID("id"),
//             beginDateTime = row.getOffsetDateTime("begin_date_time"),
//             endDateTime = row.getOffsetDateTime("end_date_time")
//           )
//         }.toTypedArray()
//       )
//     }.first()
//   }

//   fun getPollOptions(pollId: UUID, client: Pool) = client.preparedQuery(
//     "SELECT * FROM poll_options WHERE poll_id = $1"
//   ).execute(Tuple.of(pollId))
//   .map { it.map { row ->
//     PollOption(
//       optionId = row.getUUID("id"),
//       pollId = row.getUUID("poll_id"),
//       beginDateTime = row.getOffsetDateTime("begin_date_time"),
//       endDateTime = row.getOffsetDateTime("end_date_time")
//     )
//   } }

//   fun bookPollOption(pollId: UUID, data: PollBooking, client: Pool) = client.preparedQuery(
//     "INSERT INTO booking (poll_id, poll_option_id, booked_by) VALUES ($1, $2, $3) RETURNING id"
//   ).execute(Tuple.of(pollId, data.optionId, data.name))
//   .map { it.iterator().next().getUUID("id") }

//   fun getBookings(pollId: UUID, client: Pool) = client.preparedQuery(
//     "SELECT booked_by, begin_date_time, end_date_time FROM bookings JOIN poll_options ON bookings.poll_option_id = poll_options.id WHERE bookings.poll_id = $1"
//   ).execute(Tuple.of(pollId))
//   .map { it.map { row ->
//     PollResult(
//       name = row.getString("booked_by"),
//       beginDateTime = row.getOffsetDateTime("begin_date_time"),
//       endDateTime = row.getOffsetDateTime("end_date_time"),
//     )
//   } }
// }