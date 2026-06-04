package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import org.springframework.data.annotation.TypeAlias


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_class")
@JsonSubTypes(
    JsonSubTypes.Type(value = LocationData.Radar::class,           name = "radar"),
    JsonSubTypes.Type(value = LocationData.Street::class,          name = "street"),
    JsonSubTypes.Type(value = LocationData.Camping::class,         name = "camping"),
    JsonSubTypes.Type(value = LocationData.SightSeeing::class,     name = "sightseeing"),
    JsonSubTypes.Type(value = LocationData.SwimmingLocation::class, name = "swimming"),
    JsonSubTypes.Type(value = LocationData.PartyLocation::class,   name = "party"),
    JsonSubTypes.Type(value = LocationData.FastFood::class,        name = "fast_food"),
    JsonSubTypes.Type(value = LocationData.AsianFood::class,       name = "asian_food"),
    JsonSubTypes.Type(value = LocationData.GenericFood::class,     name = "generic_food"),)

sealed class LocationData {

    abstract fun schema(): List<AttributeDefinition>


    //STREET LOCATION TYPES

    @TypeAlias("radar")
    data class Radar(
        val speedLimit: AttributeValue.IntValue,
        val mobile: AttributeValue.BoolValue?,
        val redLight: AttributeValue.BoolValue
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.IntDef(key = "speedLimit", required = true, min = 0),
            AttributeDefinition.BoolDef(key = "mobile", required = false),
            AttributeDefinition.BoolDef(key = "redLight", required = true)
        )
    }



    @TypeAlias("street")
    data class Street(

        val mautFee: AttributeValue.DoubleValue?,
        val heightLimit: AttributeValue.DoubleValue?,
        val closedInWinter: AttributeValue.BoolValue?,

        val wheeliesAllowed: AttributeValue.BoolValue?,

    ): LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "mautFee",        required = false, min = 0.0),
            AttributeDefinition.DoubleDef(key = "heightLimit",    required = false, min = 0.0),
            AttributeDefinition.BoolDef  (key = "closedInWinter", required = false),
            AttributeDefinition.BoolDef  (key = "wheeliesAllowed",required = false),
        )
    }

    @TypeAlias("camping")
    data class Camping(

        val official: AttributeValue.BoolValue

    ): LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "official", required = true),
        )
    }


    //ACtivities

    @TypeAlias("sightseeing")
    data class SightSeeing(

        val entryFee: AttributeValue.DoubleValue?,

    ): LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "entryFee", required = false, min = 0.0),
        )
    }

    @TypeAlias("swimming")
    data class SwimmingLocation(
        val indoor: AttributeValue.BoolValue?
    ): LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "indoor", required = false),
        )
    }

    @TypeAlias("party")
    data class PartyLocation(

        val entryFee: AttributeValue.DoubleValue?,

    ): LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "entryFee", required = false, min = 0.0),
        )
    }


    //Food
    @TypeAlias("fast_food")
    data class FastFood(
        val burger: AttributeValue.BoolValue?,
        val kebab: AttributeValue.BoolValue?,
        val pizza: AttributeValue.BoolValue?,
        val allYouCanEat: AttributeValue.BoolValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "burger", required = false),
            AttributeDefinition.BoolDef(key = "kebab", required = false),
            AttributeDefinition.BoolDef(key = "pizza", required = false),
            AttributeDefinition.BoolDef(key = "allYouCanEat", required = false),
        )
    }


    @TypeAlias("asian_food")
    data class AsianFood(
        val chinese: AttributeValue.BoolValue?,
        val japanese: AttributeValue.BoolValue?,
        val thai: AttributeValue.BoolValue?,
        val allYouCanEat: AttributeValue.BoolValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "chinese", required = false),
            AttributeDefinition.BoolDef(key = "japanese", required = false),
            AttributeDefinition.BoolDef(key = "thai", required = false),
            AttributeDefinition.BoolDef(key = "allYouCanEat", required = false),
        )
    }


    @TypeAlias("generic_food")
    data class GenericFood(
        val cuisine: AttributeValue.StringValue,  // Free text field for any cuisine
        val allYouCanEat: AttributeValue.BoolValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.StringDef(key = "cuisine", required = true),
            AttributeDefinition.BoolDef(key = "allYouCanEat", required = false),
        )
    }

}
