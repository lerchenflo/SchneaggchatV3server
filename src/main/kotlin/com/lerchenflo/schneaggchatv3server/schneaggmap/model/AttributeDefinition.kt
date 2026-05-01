package com.lerchenflo.schneaggchatv3server.schneaggmap.model

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

    data class BoolDef(
        override val key: String,
        override val required: Boolean,
    ) : AttributeDefinition
}
