package com.lerchenflo.schneaggchatv3server.group.groupextensions

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.GroupExtensionType
import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.RoutePointKind
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import com.lerchenflo.schneaggchatv3server.util.ValidationUtils
import org.springframework.data.annotation.TypeAlias

/**
 * HOW TO ADD A NEW EXTENSION TYPE
 * ===============================
 *
 * SERVER SIDE (SchneaggchatV3server):
 * 1. Add the enum value to GroupExtensionType (model/GroupExtensionType.kt) with its max item count
 *    and its settings class
 * 2. Add the data class for its items in this sealed class with:
 *    - @TypeAlias annotation with a snake_case name
 *    - the same name in the @JsonSubTypes annotation below
 *    - `override val extensionType` returning the new enum value
 *    - `override fun validate()` (field limits) and `override fun referencedUserIds()` (every user
 *      id the payload holds - the service checks they are group members)
 *    - optionally `override fun prepareForSave()` when the server derives fields (see SharedText)
 * 3. If the type needs configuration, add an ExtensionSettings subclass the same way
 *    (model/ExtensionSettings.kt) and name it in the enum value
 *
 * CLIENT SIDE (SchneaggchatV3):
 * See the client ExtensionItemData.kt for the client-side steps
 *
 * IMPORTANT: The type alias names ("shared_text", ...) and the property names are the Mongo field
 * names and the wire JSON keys at the same time, so client and server must match exactly.
 * Timestamps inside payloads are epoch millis (Long).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_class")
@JsonSubTypes(
    JsonSubTypes.Type(value = ExtensionItemData.SharedText::class,   name = "shared_text"),
    JsonSubTypes.Type(value = ExtensionItemData.SplitExpense::class, name = "split_expense"),
    JsonSubTypes.Type(value = ExtensionItemData.Quote::class,        name = "quote"),
    JsonSubTypes.Type(value = ExtensionItemData.RoutePoint::class,   name = "route_point"),
)
sealed class ExtensionItemData {

    /** The extension type this payload belongs to. Derived, never stored or sent. */
    @get:JsonIgnore
    abstract val extensionType: GroupExtensionType

    /** Field limits of this payload. Empty = valid. */
    abstract fun validate(): List<String>

    /** Every user id this payload references. The service only accepts group members here. */
    abstract fun referencedUserIds(): Set<String>

    /**
     * Hook for fields the server sets itself, called before [validate] with the stored payload of
     * the same item ([previous], null on create) and the writing user.
     */
    open fun prepareForSave(previous: ExtensionItemData?, requesterId: String): ExtensionItemData = this


    /**
     * SHARED_TEXT: one item per extension holds the whole text.
     *
     * [writers] says who wrote which characters: consecutive runs whose lengths add up to
     * `text.length` (UTF-16 chars, like Kotlin's String.length). The server sets it - the client
     * only sends the new [text].
     */
    @TypeAlias("shared_text")
    data class SharedText(
        val text: String,
        val writers: List<WriterRun> = emptyList(),
    ) : ExtensionItemData() {
        override val extensionType get() = GroupExtensionType.SHARED_TEXT

        data class WriterRun(val length: Int, val writtenBy: String)

        override fun validate(): List<String> = buildList {
            if (text.length > 20_000) add("text: length ${text.length} exceeds maximum 20000")
            if (writers.sumOf { it.length } != text.length) add("writers: lengths do not add up to the text length")
        }

        override fun referencedUserIds() = writers.map { it.writtenBy }.toSet()

        /**
         * Keeps the writers of the unchanged start and end of the text and marks the changed middle
         * part as written by [requesterId].
         */
        override fun prepareForSave(previous: ExtensionItemData?, requesterId: String): ExtensionItemData {
            val old = previous as? SharedText
            val oldText = old?.text ?: ""
            // Stored writers are always valid (the server sets them); fall back to the current writer if not
            val oldWriters = old?.writers?.takeIf { runs -> runs.sumOf { it.length } == oldText.length }
                ?: listOf(WriterRun(oldText.length, requesterId))

            val maxCommon = minOf(oldText.length, text.length)
            var prefix = 0
            while (prefix < maxCommon && oldText[prefix] == text[prefix]) prefix++
            // Never split an emoji (surrogate pair) between two writers
            if (prefix > 0 && text[prefix - 1].isHighSurrogate()) prefix--

            var suffix = 0
            while (suffix < maxCommon - prefix && oldText[oldText.length - 1 - suffix] == text[text.length - 1 - suffix]) suffix++
            if (suffix > 0 && text[text.length - suffix].isLowSurrogate()) suffix--

            val runs = sliceRuns(oldWriters, 0, prefix) +
                WriterRun(text.length - prefix - suffix, requesterId) +
                sliceRuns(oldWriters, oldText.length - suffix, oldText.length)

            return copy(writers = mergeRuns(runs))
        }

        /** The part of [runs] that covers the characters [from] until [to] (exclusive). */
        private fun sliceRuns(runs: List<WriterRun>, from: Int, to: Int): List<WriterRun> {
            var start = 0
            return runs.mapNotNull { run ->
                val end = start + run.length
                val overlap = minOf(end, to) - maxOf(start, from)
                start = end
                if (overlap > 0) run.copy(length = overlap) else null
            }
        }

        /** Joins neighbouring runs of the same writer and drops empty ones. */
        private fun mergeRuns(runs: List<WriterRun>): List<WriterRun> {
            val merged = mutableListOf<WriterRun>()
            runs.filter { it.length > 0 }.forEach { run ->
                val last = merged.lastOrNull()
                if (last != null && last.writtenBy == run.writtenBy) {
                    merged[merged.lastIndex] = last.copy(length = last.length + run.length)
                } else {
                    merged += run
                }
            }
            return merged
        }
    }


    /**
     * SPLIT: one item per expense or money transfer. Amounts in minor units of the extension currency.
     *
     * Each participant (the [shares] keys) owes `fixedCents + rest * weight / sum of all weights`,
     * where `rest = amountCents - sum of all fixedCents`. Equal split = every weight 1, no fixed amounts.
     */
    @TypeAlias("split_expense")
    data class SplitExpense(
        val title: String,
        val amountCents: Long,
        val paidBy: String,                       // userId
        val shares: Map<String, Share>,           // userId -> share of the cost
        val date: Long,
        @get:JsonProperty("isSettlement")
        val isSettlement: Boolean = false,        // true = "paidBy paid the single shares key back"
        val emoji: String? = null,
    ) : ExtensionItemData() {
        override val extensionType get() = GroupExtensionType.SPLIT

        data class Share(val weight: Int = 1, val fixedCents: Long = 0)

        override fun validate(): List<String> = buildList {
            if (title.length > 100) add("title: length ${title.length} exceeds maximum 100")
            if (!isSettlement && title.isBlank()) add("title: required for an expense")
            if (amountCents !in 0L..100_000_000L) add("amountCents: $amountCents is not within 0..100000000")
            if (date < 0) add("date: must not be negative")
            if (shares.isEmpty() || shares.size > 100) add("shares: needs 1..100 participants")
            if (shares.values.any { it.weight !in 0..1000 }) add("shares: weight must be within 0..1000")
            if (shares.values.any { it.fixedCents < 0 }) add("shares: fixedCents must not be negative")
            val fixedSum = shares.values.sumOf { it.fixedCents }
            if (fixedSum > amountCents) add("shares: fixed amounts exceed the total")
            if (shares.values.sumOf { it.weight } == 0 && fixedSum != amountCents) add("shares: the rest has no weights to go to")
            if (isSettlement && (shares.size != 1 || paidBy in shares)) add("shares: a transfer goes to exactly one other user")
            emoji?.let { if (it.isBlank() || it.length > 32) add("emoji: must be 1..32 chars") }
        }

        override fun referencedUserIds() = shares.keys + paidBy
    }


    /** QUOTES: one item per quote. */
    @TypeAlias("quote")
    data class Quote(
        val text: String,
        val saidByUserId: String? = null,         // group member, or
        val saidByName: String? = null,           // free text for non-members
        val saidAt: Long? = null,
        val context: String? = null,
    ) : ExtensionItemData() {
        override val extensionType get() = GroupExtensionType.QUOTES

        override fun validate(): List<String> = buildList {
            if (text.isBlank() || text.length > 1_000) add("text: must be 1..1000 chars")
            if (saidByUserId == null && saidByName.isNullOrBlank()) add("saidBy: user or name required")
            saidByName?.let { if (it.length > 50) add("saidByName: length ${it.length} exceeds maximum 50") }
            context?.let { if (it.length > 500) add("context: length ${it.length} exceeds maximum 500") }
        }

        override fun referencedUserIds() = setOfNotNull(saidByUserId)
    }


    /** ROUTE_PLANNER: one item per point; the route is the items ordered by sortIndex. */
    @TypeAlias("route_point")
    data class RoutePoint(
        val coordinates: LatLong,
        val name: String,
        val description: String = "",
        val sortIndex: Int,
        val kind: RoutePointKind = RoutePointKind.STOP,
        val plannedAt: Long? = null,
        val mapEntryId: String? = null,           // soft link to a map entry; coordinates/name are copies
    ) : ExtensionItemData() {
        override val extensionType get() = GroupExtensionType.ROUTE_PLANNER

        override fun validate(): List<String> = buildList {
            if (!ValidationUtils.validateLatLong(coordinates.lat, coordinates.long)) add("coordinates: invalid")
            if (name.isBlank() || name.length > 100) add("name: must be 1..100 chars")
            if (description.length > 500) add("description: length ${description.length} exceeds maximum 500")
            if (sortIndex < 0) add("sortIndex: must not be negative")
            mapEntryId?.let { if (!ValidationUtils.validateObjectId(it)) add("mapEntryId: invalid id") }
        }

        override fun referencedUserIds() = emptySet<String>()
    }
}
