package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import com.lerchenflo.schneaggchatv3server.util.Json
import org.bson.Document
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions


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
            is AttributeValue.EnumValue -> Document("_class", "enum").append("value", source.value)
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
            "enum" -> AttributeValue.EnumValue(value.toString())
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

        // Add the discriminator field for sealed class
        val typeName = when (source) {
            is LocationData.Radar -> "radar"
            is LocationData.Street -> "street"
            is LocationData.Camping -> "camping"
            is LocationData.SightSeeing -> "sightseeing"
            is LocationData.SwimmingLocation -> "swimming"
            is LocationData.PartyLocation -> "party"
            is LocationData.Food -> "food"
        }
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



