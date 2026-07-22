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

        // Add the discriminator field for sealed class
        val typeName = when (source) {

            // Traffic & Hazards
            is LocationData.Radar          -> "radar"
            is LocationData.Police         -> "police"

            // Rider Spots
            is LocationData.MountainStreet -> "mountain_street"
            is LocationData.Wheeliespot    -> "wheeliespot"
            is LocationData.OffroadMotorcycle -> "offroad_motorcycle"
            is LocationData.Viewpoint      -> "viewpoint"

            // Nature & Activities
            is LocationData.Camping        -> "camping"
            is LocationData.SwimmingLocation -> "swimming"
            is LocationData.Climbingspot   -> "climbingspot"

            // Sport
            is LocationData.Volleyball     -> "volleyball"
            is LocationData.Bicycle        -> "bicycle"
            is LocationData.OutdoorFitness -> "outdoor_fitness"
            is LocationData.TableTennis    -> "table_tennis"
            is LocationData.Tennis         -> "tennis"

            // Social & Entertainment
            is LocationData.SightSeeing    -> "sightseeing"
            is LocationData.PartyLocation  -> "party"

            // Fast Food & Snacks
            is LocationData.FoodKebab      -> "food_kebab"
            is LocationData.FoodPizza      -> "food_pizza"
            is LocationData.FoodBurger     -> "food_burger"
            is LocationData.FoodBeer       -> "food_beer"
            is LocationData.FoodCafeBakery -> "food_cafe_bakery"

            // Restaurant
            is LocationData.FoodAsian      -> "food_asian"
            is LocationData.FoodGreek      -> "food_greek"
            is LocationData.FoodOther      -> "food_other"
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



