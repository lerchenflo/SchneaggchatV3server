package com.lerchenflo.schneaggchatv3server.schneaggmap.model

data class AttributeValueDoc(
    val type: String,
    val stringValue: String? = null,
    val intValue: Int? = null,
    val doubleValue: Double? = null,
    val boolValue: Boolean? = null,
) {
    fun toAttributeValue(): AttributeValue = when (type) {
        "string" -> AttributeValue.StringValue(stringValue ?: "")
        "int"    -> AttributeValue.IntValue(intValue ?: 0)
        "double" -> AttributeValue.DoubleValue(doubleValue ?: 0.0)
        "bool"   -> AttributeValue.BoolValue(boolValue ?: false)
        else     -> throw IllegalStateException("Unknown attribute type: $type")
    }
}

fun AttributeValue.toDoc(): AttributeValueDoc = when (this) {
    is AttributeValue.StringValue -> AttributeValueDoc(type = "string", stringValue = value)
    is AttributeValue.IntValue    -> AttributeValueDoc(type = "int", intValue = value)
    is AttributeValue.DoubleValue -> AttributeValueDoc(type = "double", doubleValue = value)
    is AttributeValue.BoolValue   -> AttributeValueDoc(type = "bool", boolValue = value)
}
