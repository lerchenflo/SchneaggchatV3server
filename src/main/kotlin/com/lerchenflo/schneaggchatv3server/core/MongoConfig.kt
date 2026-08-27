package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.Document
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.annotation.TypeAlias
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import kotlin.reflect.full.findAnnotation


@Configuration
class MongoConfig {

    @Bean
    fun customConversions(): MongoCustomConversions {
        return MongoCustomConversions(listOf(
            AttributeValueWriteConverter(),
            AttributeValueReadConverter(),
            LocationDataWriteConverter(),
            LocationDataReadConverter(),
        ))
    }
}





@WritingConverter
class AttributeValueWriteConverter : Converter<AttributeValue, Document> {
    override fun convert(source: AttributeValue): Document {
        // Manually create document with type discriminator
        return when (source) {
            is AttributeValue.StringValue -> Document("_class", "string").append("value", source.value)
            is AttributeValue.IntValue -> Document("_class", "int").append("value", source.value)
            is AttributeValue.DoubleValue -> Document("_class", "double").append("value", source.value)
            is AttributeValue.BoolValue -> Document("_class", "bool").append("value", source.value)
            is AttributeValue.LongValue -> Document("_class", "long").append("value", source.value)
        }
    }
}

@ReadingConverter
class AttributeValueReadConverter : Converter<Document, AttributeValue> {
    override fun convert(source: Document): AttributeValue {
        val type = source.getString("_class")
        val value = source["value"]

        return when (type) {
            "string" -> AttributeValue.StringValue(value.toString())
            "int" -> AttributeValue.IntValue((value as Number).toInt())
            "double" -> AttributeValue.DoubleValue((value as Number).toDouble())
            "bool" -> AttributeValue.BoolValue(value as Boolean)
            "long" -> AttributeValue.LongValue(value as Long)
            else -> throw IllegalArgumentException("Unknown attribute type: $type")
        }
    }
}




@WritingConverter
class LocationDataWriteConverter : Converter<LocationData, Document> {
    override fun convert(source: LocationData): Document {
        // Use Jackson to serialize the entire object
        val json = Json.mapper.writeValueAsString(source)
        val doc = Document.parse(json)

        // Add the discriminator field for the sealed class - derived from the same @TypeAlias
        // annotation each subtype already carries, instead of a hand-maintained string table that
        // can drift from it (Jackson's own @JsonTypeInfo on LocationData already writes the same
        // value into `json` above; this just guarantees it explicitly from a single source).
        val typeName = source::class.findAnnotation<TypeAlias>()?.value
            ?: error("Missing @TypeAlias on ${source::class.simpleName}")
        doc["_class"] = typeName

        return doc
    }
}


@ReadingConverter
class LocationDataReadConverter : Converter<Document, LocationData> {
    override fun convert(source: Document): LocationData {
        // Add the type discriminator that Jackson expects
        val docWithType = Document(source)

        return Json.mapper.convertValue(docWithType, LocationData::class.java)
    }
}



