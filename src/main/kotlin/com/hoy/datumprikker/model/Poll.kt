package com.hoy.datumprikker.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import io.vertx.sqlclient.Row
import java.time.OffsetDateTime
import java.util.UUID

// Model classes first
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class Poll(
    val id: UUID? = null,
    val title: String? = null,
    val description: String? = null,
    val createdBy: String? = null,
    val options: Array<PollOption> = arrayOf()
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PollCreateRequest(
    val title: String,
    val description: String? = null,
    val createdBy: String,
    val options: Array<PollOptionCreate>
)

    
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PollOptionCreate(
    val beginDateTime: OffsetDateTime,
    val endDateTime: OffsetDateTime
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PollOption(
    val optionId: UUID? = null,
    val pollId: UUID? = null,
    val beginDateTime: OffsetDateTime = OffsetDateTime.now(),
    val endDateTime: OffsetDateTime = beginDateTime.plusHours(1)
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PollBooking(
    val pollId: UUID? = null,
    val optionId: UUID? = null,
    val name: String? = null,
    val email: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
data class PollResult(
    val name: String? = null,
    val beginDateTime: OffsetDateTime = OffsetDateTime.now(),
    val endDateTime: OffsetDateTime = beginDateTime.plusHours(1),
)

// Extension functions after model classes
fun Row.toPoll() = Poll(
    id = getUUID("id"),
    title = getString("title"),
    description = getString("description"),
    createdBy = getString("created_by")
)

fun Row.toPollOption() = PollOption(
    optionId = getUUID("id"),
    pollId = getUUID("poll_id"),
    beginDateTime = getOffsetDateTime("begin_date_time"),
    endDateTime = getOffsetDateTime("end_date_time")
)

fun Row.toPollResult() = PollResult(
    name = getString("booked_by"),
    beginDateTime = getOffsetDateTime("begin_date_time"),
    endDateTime = getOffsetDateTime("end_date_time")
)

fun Row.toPollBooking() = PollBooking(
    pollId = getUUID("poll_id"),
    optionId = getUUID("poll_option_id"),
    name = getString("booked_by")
)