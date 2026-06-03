package com.lerchenflo.schneaggchatv3server.core

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
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
            LocationDataReadConverter(),
            LocationDataWriteConverter(),
            AttributeValueReadConverter()
        )
        )
    }
}

// plain mapper with no @JsonTypeInfo interference
private val schneaggmapMapper: ObjectMapper = ObjectMapper()
    .registerModule(
        KotlinModule.Builder()
            .configure(KotlinFeature.SingletonSupport, true)
            .build()
    )
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    .configure(MapperFeature.USE_ANNOTATIONS, false)

@WritingConverter
class LocationDataWriteConverter : Converter<LocationData, Document> {
    override fun convert(source: LocationData): Document {
        val doc = Document(Json.mapper.convertValue(source, Map::class.java) as Map<String, Any?>)
        return doc
    }
}


@ReadingConverter
class LocationDataReadConverter : Converter<Document, LocationData?> {

    private val aliasToType = mapOf(
        "radar"       to "radar",
        "street"      to "street",
        "camping"     to "camping",
        "sightseeing" to "sightseeing",
        "swimming"    to "swimming",
        "party"       to "party",
        "food"        to "food",

        "av_string"   to "string",
        "av_int"      to "int",
        "av_double"   to "double",
        "av_bool"     to "bool",
        "av_enum"     to "enum",
    )

    override fun convert(source: Document): LocationData? {
        val map = injectTypes(source.toMap().toMutableMap())
        return Json.mapper.convertValue(map, LocationData::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    private fun injectTypes(map: MutableMap<String, Any?>): MutableMap<String, Any?> {
        (map["_class"] as? String)?.let { alias ->
            map["type"] = aliasToType[alias]
                ?: throw IllegalArgumentException("Unknown _class alias: $alias")
        }
        map.forEach { (key, value) ->
            when (value) {
                is MutableMap<*, *> ->
                    map[key] = injectTypes(value as MutableMap<String, Any?>)
                is List<*> ->
                    map[key] = value.map { item ->
                        if (item is MutableMap<*, *>)
                            injectTypes(item as MutableMap<String, Any?>)
                        else item
                    }
            }
        }
        return map
    }
}

@ReadingConverter
class AttributeValueReadConverter : Converter<Document, AttributeValue?> {


    override fun convert(source: Document): AttributeValue? {
        val typeAlias = source["_class"] as? String
            ?: throw IllegalArgumentException("Type alias not found in document")

        val map = source.toMap()

        return when (typeAlias) {
            "av_string" -> schneaggmapMapper.convertValue(map, AttributeValue.StringValue::class.java)
            "av_int"    -> schneaggmapMapper.convertValue(map, AttributeValue.IntValue::class.java)
            "av_double" -> schneaggmapMapper.convertValue(map, AttributeValue.DoubleValue::class.java)
            "av_bool"   -> schneaggmapMapper.convertValue(map, AttributeValue.BoolValue::class.java)
            "av_enum"   -> schneaggmapMapper.convertValue(map, AttributeValue.EnumValue::class.java)
            else        -> throw IllegalArgumentException("Unknown AttributeValue type alias: $typeAlias")
        }
    }
}