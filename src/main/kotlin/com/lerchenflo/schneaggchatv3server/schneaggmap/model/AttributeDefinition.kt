package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_class")
@JsonSubTypes(
    JsonSubTypes.Type(value = AttributeDefinition.StringDef::class, name = "string"),
    JsonSubTypes.Type(value = AttributeDefinition.IntDef::class, name = "int"),
    JsonSubTypes.Type(value = AttributeDefinition.DoubleDef::class, name = "double"),
    JsonSubTypes.Type(value = AttributeDefinition.BoolDef::class, name = "bool"),
    JsonSubTypes.Type(value = AttributeDefinition.EnumDef::class, name = "enum")
)
sealed interface AttributeDefinition {
    val key: String
    val required: Boolean

    data class StringDef(
        override val key: String,
        override val required: Boolean,
        val maxLength: Int? = null,
    ) : AttributeDefinition

    data class IntDef(
        override val key: String,
        override val required: Boolean,
        val min: Int? = null,
        val max: Int? = null,
    ) : AttributeDefinition

    data class DoubleDef(
        override val key: String,
        override val required: Boolean,
        val min: Double? = null,
        val max: Double? = null,
    ) : AttributeDefinition

    data class BoolDef(
        override val key: String,
        override val required: Boolean,
    ) : AttributeDefinition

    data class EnumDef(
        override val key: String,
        override val required: Boolean,
        val options: List<String>, // the enum names
    ) : AttributeDefinition
}
