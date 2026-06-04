package com.lerchenflo.schneaggchatv3server.schneaggmap.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.data.annotation.TypeAlias

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = AttributeValue.StringValue::class, name = "string"),
    JsonSubTypes.Type(value = AttributeValue.IntValue::class, name = "int"),
    JsonSubTypes.Type(value = AttributeValue.DoubleValue::class, name = "double"),
    JsonSubTypes.Type(value = AttributeValue.BoolValue::class, name = "bool"),
    JsonSubTypes.Type(value = AttributeValue.EnumValue::class, name = "enum"),
)

sealed class AttributeValue {

    @TypeAlias("string")
    data class StringValue(val value: String) : AttributeValue()

    @TypeAlias("int")
    data class IntValue(val value: Int) : AttributeValue()

    @TypeAlias("double")
    data class DoubleValue(val value: Double) : AttributeValue()

    @TypeAlias("bool")
    data class BoolValue(val value: Boolean) : AttributeValue()

    @TypeAlias("enum")
    data class EnumValue(val value: String) : AttributeValue()

}
