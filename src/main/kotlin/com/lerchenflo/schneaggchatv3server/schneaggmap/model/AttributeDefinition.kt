package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_class")
@JsonSubTypes(
    JsonSubTypes.Type(value = AttributeDefinition.StringDef::class, name = "string"),
    JsonSubTypes.Type(value = AttributeDefinition.IntDef::class, name = "int"),
    JsonSubTypes.Type(value = AttributeDefinition.DoubleDef::class, name = "double"),
    JsonSubTypes.Type(value = AttributeDefinition.BoolDef::class, name = "bool"),
    JsonSubTypes.Type(value = AttributeDefinition.LongDef::class, name = "long"),
    JsonSubTypes.Type(value = AttributeDefinition.EnumDef::class, name = "enum"),
    JsonSubTypes.Type(value = AttributeDefinition.DateTimeDef::class, name = "datetime"),
    JsonSubTypes.Type(value = AttributeDefinition.PriceDef::class, name = "price"),
    JsonSubTypes.Type(value = AttributeDefinition.DistanceDef::class, name = "distance"),
    JsonSubTypes.Type(value = AttributeDefinition.RatingDef::class, name = "rating"),
    JsonSubTypes.Type(value = AttributeDefinition.SecretDef::class, name = "secret"),
)
sealed interface AttributeDefinition {
    val key: AttributeKey
    val required: Boolean

    data class StringDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val maxLength: Int? = null,
    ) : AttributeDefinition

    data class IntDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val min: Int? = null,
        val max: Int? = null,
    ) : AttributeDefinition

    data class DoubleDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val min: Double? = null,
        val max: Double? = null,
    ) : AttributeDefinition

    data class LongDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val min: Long? = null,
        val max: Long? = null,
    ) : AttributeDefinition

    data class BoolDef(
        override val key: AttributeKey,
        override val required: Boolean,
    ) : AttributeDefinition

    /**
     * One choice out of [options]. Stored as an [AttributeValue.StringValue] holding the enum
     * constant's name (never its ordinal), so no new wire/DB value type is needed.
     */
    data class EnumDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val options: List<String>,
    ) : AttributeDefinition

    /** A point in time as epoch millis. Stored as an [AttributeValue.LongValue]. */
    data class DateTimeDef(
        override val key: AttributeKey,
        override val required: Boolean,
    ) : AttributeDefinition

    /** A price in euros, never negative. Stored as an [AttributeValue.DoubleValue]. */
    data class PriceDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val max: Double? = null,
    ) : AttributeDefinition

    /** A distance or length in [unit], never negative. Stored as an [AttributeValue.DoubleValue]. */
    data class DistanceDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val unit: DistanceUnit,
        val max: Double? = null,
    ) : AttributeDefinition

    /** A whole-number rating from [min] to [max] (inclusive). Stored as an [AttributeValue.IntValue]. */
    data class RatingDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val min: Int,
        val max: Int,
    ) : AttributeDefinition

    /** Sensitive text (e.g. a password), hidden by default in the client. Stored as an [AttributeValue.StringValue]. */
    data class SecretDef(
        override val key: AttributeKey,
        override val required: Boolean,
        val maxLength: Int? = null,
    ) : AttributeDefinition

}

enum class DistanceUnit {
    METERS,
    KILOMETERS,
}
