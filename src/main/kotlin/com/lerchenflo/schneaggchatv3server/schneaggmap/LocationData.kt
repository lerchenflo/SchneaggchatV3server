package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import org.springframework.data.annotation.TypeAlias


@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_class")
@JsonSubTypes(
    JsonSubTypes.Type(value = LocationData.Radar::class,           name = "radar"),
    JsonSubTypes.Type(value = LocationData.Police::class,          name = "police"),
    JsonSubTypes.Type(value = LocationData.MountainStreet::class,  name = "mountain_street"),
    JsonSubTypes.Type(value = LocationData.Wheeliespot::class,     name = "wheeliespot"),
    JsonSubTypes.Type(value = LocationData.OffroadMotorcycle::class, name = "offroad_motorcycle"),
    JsonSubTypes.Type(value = LocationData.Viewpoint::class,       name = "viewpoint"),
    JsonSubTypes.Type(value = LocationData.Camping::class,         name = "camping"),
    JsonSubTypes.Type(value = LocationData.SwimmingLocation::class, name = "swimming"),
    JsonSubTypes.Type(value = LocationData.Volleyball::class,      name = "volleyball"),
    JsonSubTypes.Type(value = LocationData.Bicycle::class,         name = "bicycle"),
    JsonSubTypes.Type(value = LocationData.OutdoorFitness::class,  name = "outdoor_fitness"),
    JsonSubTypes.Type(value = LocationData.TableTennis::class,     name = "table_tennis"),
    JsonSubTypes.Type(value = LocationData.Tennis::class,          name = "tennis"),
    JsonSubTypes.Type(value = LocationData.SightSeeing::class,     name = "sightseeing"),
    JsonSubTypes.Type(value = LocationData.PartyLocation::class,   name = "party"),
    JsonSubTypes.Type(value = LocationData.FoodKebab::class,       name = "food_kebab"),
    JsonSubTypes.Type(value = LocationData.FoodPizza::class,       name = "food_pizza"),
    JsonSubTypes.Type(value = LocationData.FoodBurger::class,      name = "food_burger"),
    JsonSubTypes.Type(value = LocationData.FoodBeer::class,        name = "food_beer"),
    JsonSubTypes.Type(value = LocationData.FoodAsian::class,       name = "food_asian"),
    JsonSubTypes.Type(value = LocationData.FoodGreek::class,       name = "food_greek"),
    JsonSubTypes.Type(value = LocationData.FoodOther::class,       name = "food_other"),
)

sealed class LocationData {

    abstract fun schema(): List<AttributeDefinition>


    // Traffic & Hazards

    @TypeAlias("radar")
    data class Radar(
        val speedLimit: AttributeValue,
        val mobile: AttributeValue?,
        val redLight: AttributeValue,
    ) : LocationData() {

        override fun schema() = listOf(
            AttributeDefinition.IntDef(key = "speedLimit", required = true, min = 0),
            AttributeDefinition.BoolDef(key = "mobile",    required = false),
            AttributeDefinition.BoolDef(key = "redLight",  required = true),
        )
    }

    @TypeAlias("police")
    data class Police(
        val lastSeen: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.LongDef(key = "lastSeen", required = false),
        )
    }


    // Rider Spots

    @TypeAlias("mountain_street")
    data class MountainStreet(
        val mautFee: AttributeValue?,
        val heightLimit: AttributeValue?,
        val closedInWinter: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "mautFee",        required = false, min = 0.0),
            AttributeDefinition.DoubleDef(key = "heightLimit",    required = false, min = 0.0),
            AttributeDefinition.BoolDef  (key = "closedInWinter", required = false),
        )
    }

    @TypeAlias("wheeliespot")
    data class Wheeliespot(
        val onlyOnWeekends: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "onlyOnWeekends", required = false),
        )
    }

    @TypeAlias("offroad_motorcycle")
    data class OffroadMotorcycle(
        val legal: AttributeValue,
        val motocross: AttributeValue?,
        val enduro: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "legal",     required = true),
            AttributeDefinition.BoolDef(key = "motocross", required = false),
            AttributeDefinition.BoolDef(key = "enduro",    required = false),
        )
    }

    @TypeAlias("viewpoint")
    data class Viewpoint(
        val lieDownFriendly: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "lieDownFriendly", required = false),
        )
    }


    // Nature & Activities

    @TypeAlias("camping")
    data class Camping(
        val official: AttributeValue,
        val waterDistance: AttributeValue?,
        val sittingPossibility: AttributeValue?,
        val grillPossibility: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "official",           required = true),
            AttributeDefinition.IntDef (key = "waterDistance",      required = false, min = 0),
            AttributeDefinition.BoolDef(key = "sittingPossibility", required = false),
            AttributeDefinition.BoolDef(key = "grillPossibility",   required = false),
        )
    }

    @TypeAlias("swimming")
    data class SwimmingLocation(
        val indoor: AttributeValue?,
        val jumpSpot: AttributeValue?,
        val lieDownFriendly: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "indoor",          required = false),
            AttributeDefinition.BoolDef(key = "jumpSpot",        required = false),
            AttributeDefinition.BoolDef(key = "lieDownFriendly", required = false),
        )
    }


    // Sport

    @TypeAlias("volleyball")
    data class Volleyball(
        val goodNet: AttributeValue?,
        val goodField: AttributeValue?,
        val outdoor: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "goodNet",   required = false),
            AttributeDefinition.BoolDef(key = "goodField", required = false),
            AttributeDefinition.BoolDef(key = "outdoor",   required = false),
        )
    }

    @TypeAlias("bicycle")
    data class Bicycle(
        val legal: AttributeValue,
        val difficulty: AttributeValue,
        val undergroundType: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef  (key = "legal",           required = true),
            AttributeDefinition.IntDef   (key = "difficulty",      required = true, min = 1, max = 10),
            AttributeDefinition.StringDef(key = "undergroundType", required = false),
        )
    }

    @TypeAlias("outdoor_fitness")
    data class OutdoorFitness(
        val shadow: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "shadow", required = false),
        )
    }

    @TypeAlias("table_tennis")
    data class TableTennis(
        val `private`: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "private", required = false),
        )
    }

    @TypeAlias("tennis")
    data class Tennis(
        val paddle: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "paddle", required = false),
        )
    }


    // Social & Entertainment

    @TypeAlias("sightseeing")
    data class SightSeeing(
        val entryFee: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "entryFee", required = false, min = 0.0),
        )
    }

    @TypeAlias("party")
    data class PartyLocation(
        val entryFee: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "entryFee", required = false, min = 0.0),
        )
    }


    // Fast Food & Snacks

    @TypeAlias("food_kebab")
    data class FoodKebab(
        val kebabPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "kebabPrice", required = false, min = 0.0),
        )
    }

    @TypeAlias("food_pizza")
    data class FoodPizza(
        val margaritaPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "margaritaPrice", required = false, min = 0.0),
        )
    }

    @TypeAlias("food_burger")
    data class FoodBurger(
        val cheeseburgerPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "cheeseburgerPrice", required = false, min = 0.0),
        )
    }

    @TypeAlias("food_beer")
    data class FoodBeer(
        val beerPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = "beerPrice", required = false, min = 0.0),
        )
    }


    // Restaurant

    @TypeAlias("food_asian")
    data class FoodAsian(
        val allYouCanEat: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = "allYouCanEat", required = false),
        )
    }


    @TypeAlias("food_greek")
    class FoodGreek : LocationData() {
        override fun schema() = emptyList<AttributeDefinition>()
    }

    @TypeAlias("food_other")
    data class FoodOther(
        val cuisine: AttributeValue,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.StringDef(key = "cuisine", required = true),
        )
    }
}