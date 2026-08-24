package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeDefinition
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeKey
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue
import org.springframework.data.annotation.TypeAlias

/**
 * HOW TO ADD A NEW LOCATION ENTRY
 * ================================
 *
 * SERVER SIDE (SchneaggchatV3server):
 * 1. Add the new type to @JsonSubTypes annotation with name in snake_case
 * 2. Add the data class in LocationData sealed class with:
 *    - @TypeAlias annotation with snake_case name (must match JsonSubTypes name)
 *    - Properties with AttributeValue types (nullable if optional), named
 *      lowerCamelCase(<TYPE>_<ATTRIBUTE>) to match their AttributeKey 1:1
 *    - Override fun schema() returning list of AttributeDefinitions, keyed by AttributeKey
 * 3. Add the new type to LocationDataWriteConverter in MongoConfig.kt
 * 4. Add an AttributeKey entry per attribute in model/AttributeKey.kt
 * 5. Wire the new type + its keys into getValueByKey() in GetValueForKey.kt - the compiler will
 *    force this via the exhaustive `when` over LocationData
 * 6. Update any service files that instantiate LocationData (e.g., SchneaggmapService.kt)
 *
 * CLIENT SIDE (SchneaggchatV3):
 * See client LocationData.kt for detailed client-side instructions
 *
 * IMPORTANT: Keep the serial/type alias names ("radar", ...) consistent between client and server
 * (snake_case). Keep property names consistent with AttributeKey entries (lowerCamelCase) - they are
 * also the Mongo field names and the wire JSON keys, so client and server must match exactly.
 */


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
    JsonSubTypes.Type(value = LocationData.Wifi::class,            name = "wifi"),
    JsonSubTypes.Type(value = LocationData.FoodKebab::class,       name = "food_kebab"),
    JsonSubTypes.Type(value = LocationData.FoodPizza::class,       name = "food_pizza"),
    JsonSubTypes.Type(value = LocationData.FoodBurger::class,      name = "food_burger"),
    JsonSubTypes.Type(value = LocationData.FoodBeer::class,        name = "food_beer"),
    JsonSubTypes.Type(value = LocationData.FoodIce::class,         name = "food_ice"),
    JsonSubTypes.Type(value = LocationData.FoodAsian::class,       name = "food_asian"),
    JsonSubTypes.Type(value = LocationData.FoodGreek::class,       name = "food_greek"),
    JsonSubTypes.Type(value = LocationData.FoodOther::class,       name = "food_other"),
    JsonSubTypes.Type(value = LocationData.Climbingspot::class,    name = "climbingspot"),
    JsonSubTypes.Type(value = LocationData.FoodCafeBakery::class,  name = "food_cafe_bakery"),
)

sealed class LocationData {

    abstract fun schema(): List<AttributeDefinition>


    // Traffic & Hazards

    @TypeAlias("radar")
    data class Radar(
        val radarSpeedLimit: AttributeValue,
        val radarMobile: AttributeValue?,
        val radarRedLight: AttributeValue,
    ) : LocationData() {

        override fun schema() = listOf(
            AttributeDefinition.IntDef(key = AttributeKey.RADAR_SPEED_LIMIT, required = true, min = 0),
            AttributeDefinition.BoolDef(key = AttributeKey.RADAR_MOBILE,    required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.RADAR_RED_LIGHT,  required = true),
        )
    }

    @TypeAlias("police")
    data class Police(
        val policeLastSeen: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.LongDef(key = AttributeKey.POLICE_LAST_SEEN, required = false),
        )
    }


    // Rider Spots

    @TypeAlias("mountain_street")
    data class MountainStreet(
        val mountainStreetMautFee: AttributeValue?,
        val mountainStreetHeightLimit: AttributeValue?,
        val mountainStreetClosedInWinter: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.MOUNTAIN_STREET_MAUT_FEE,        required = false, min = 0.0),
            AttributeDefinition.DoubleDef(key = AttributeKey.MOUNTAIN_STREET_HEIGHT_LIMIT,    required = false, min = 0.0),
            AttributeDefinition.BoolDef  (key = AttributeKey.MOUNTAIN_STREET_CLOSED_IN_WINTER, required = false),
        )
    }

    @TypeAlias("wheeliespot")
    data class Wheeliespot(
        val wheeliespotOnlyOnWeekends: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.WHEELIESPOT_ONLY_ON_WEEKENDS, required = false),
        )
    }

    @TypeAlias("offroad_motorcycle")
    data class OffroadMotorcycle(
        val offroadMotorcycleLegal: AttributeValue,
        val offroadMotorcycleMotocross: AttributeValue?,
        val offroadMotorcycleEnduro: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.OFFROAD_MOTORCYCLE_LEGAL,     required = true),
            AttributeDefinition.BoolDef(key = AttributeKey.OFFROAD_MOTORCYCLE_MOTOCROSS, required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.OFFROAD_MOTORCYCLE_ENDURO,    required = false),
        )
    }

    @TypeAlias("viewpoint")
    data class Viewpoint(
        val viewpointLieDownFriendly: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.VIEWPOINT_LIE_DOWN_FRIENDLY, required = false),
        )
    }


    // Nature & Activities

    @TypeAlias("camping")
    data class Camping(
        val campingOfficial: AttributeValue,
        val campingWaterDistance: AttributeValue?,
        val campingSittingPossibility: AttributeValue?,
        val campingGrillPossibility: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.CAMPING_OFFICIAL,           required = true),
            AttributeDefinition.IntDef (key = AttributeKey.CAMPING_WATER_DISTANCE,      required = false, min = 0),
            AttributeDefinition.BoolDef(key = AttributeKey.CAMPING_SITTING_POSSIBILITY, required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.CAMPING_GRILL_POSSIBILITY,   required = false),
        )
    }

    @TypeAlias("swimming")
    data class SwimmingLocation(
        val swimmingIndoor: AttributeValue?,
        val swimmingJumpSpot: AttributeValue?,
        val swimmingLieDownFriendly: AttributeValue?,
        val swimmingPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.SWIMMING_INDOOR,          required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.SWIMMING_JUMP_SPOT,       required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.SWIMMING_LIE_DOWN_FRIENDLY, required = false),
            AttributeDefinition.IntDef (key = AttributeKey.SWIMMING_PRICE,           required = false, min = 0),
        )
    }

    @TypeAlias("climbingspot")
    data class Climbingspot(
        val climbingspotViaFerrata: AttributeValue?,
        val climbingspotOutdoor: AttributeValue?,
        val climbingspotPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.CLIMBINGSPOT_VIA_FERRATA, required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.CLIMBINGSPOT_OUTDOOR,     required = false),
            AttributeDefinition.IntDef (key = AttributeKey.CLIMBINGSPOT_PRICE,       required = false, min = 0),
        )
    }


    // Sport

    @TypeAlias("volleyball")
    data class Volleyball(
        val volleyballGoodNet: AttributeValue?,
        val volleyballGoodField: AttributeValue?,
        val volleyballOutdoor: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.VOLLEYBALL_GOOD_NET,   required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.VOLLEYBALL_GOOD_FIELD, required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.VOLLEYBALL_OUTDOOR,    required = false),
        )
    }

    @TypeAlias("bicycle")
    data class Bicycle(
        val bicycleLegal: AttributeValue,
        val bicycleDifficulty: AttributeValue,
        val bicycleUndergroundType: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef  (key = AttributeKey.BICYCLE_LEGAL,            required = true),
            AttributeDefinition.IntDef   (key = AttributeKey.BICYCLE_DIFFICULTY,       required = true, min = 1, max = 10),
            AttributeDefinition.StringDef(key = AttributeKey.BICYCLE_UNDERGROUND_TYPE, required = false),
        )
    }

    @TypeAlias("outdoor_fitness")
    data class OutdoorFitness(
        val outdoorFitnessShadow: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.OUTDOOR_FITNESS_SHADOW, required = false),
        )
    }

    @TypeAlias("table_tennis")
    data class TableTennis(
        val tableTennisPrivate: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.TABLE_TENNIS_PRIVATE, required = false),
        )
    }

    @TypeAlias("tennis")
    data class Tennis(
        val tennisPaddle: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.TENNIS_PADDLE, required = false),
        )
    }


    // Social & Entertainment

    @TypeAlias("sightseeing")
    data class SightSeeing(
        val sightseeingEntryFee: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.SIGHTSEEING_ENTRY_FEE, required = false, min = 0.0),
        )
    }

    @TypeAlias("party")
    data class PartyLocation(
        val partyEntryFee: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.PARTY_ENTRY_FEE, required = false, min = 0.0),
        )
    }

    @TypeAlias("wifi")
    data class Wifi(
        val wifiSsid: AttributeValue?,
        val wifiPassword: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.StringDef(key = AttributeKey.WIFI_SSID,     required = false),
            AttributeDefinition.StringDef(key = AttributeKey.WIFI_PASSWORD, required = false),
        )
    }


    // Fast Food & Snacks

    @TypeAlias("food_kebab")
    data class FoodKebab(
        val foodKebabPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.FOOD_KEBAB_PRICE, required = false, min = 0.0),
        )
    }

    @TypeAlias("food_pizza")
    data class FoodPizza(
        val foodPizzaMargaritaPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.FOOD_PIZZA_MARGARITA_PRICE, required = false, min = 0.0),
        )
    }

    @TypeAlias("food_burger")
    data class FoodBurger(
        val foodBurgerCheeseburgerPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.FOOD_BURGER_CHEESEBURGER_PRICE, required = false, min = 0.0),
        )
    }

    @TypeAlias("food_beer")
    data class FoodBeer(
        val foodBeerPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.FOOD_BEER_PRICE, required = false, min = 0.0),
        )
    }

    @TypeAlias("food_ice")
    data class FoodIce(
        val foodIceScoopPrice: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.DoubleDef(key = AttributeKey.FOOD_ICE_SCOOP_PRICE, required = false, min = 0.0),
        )
    }

    @TypeAlias("food_cafe_bakery")
    data class FoodCafeBakery(
        val foodCafeBakeryOutdoorSeating: AttributeValue?,
        val foodCafeBakeryAlcohol: AttributeValue?,
        val foodCafeBakeryCoffee: AttributeValue?,
        val foodCafeBakeryBreakfast: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.FOOD_CAFE_BAKERY_OUTDOOR_SEATING, required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.FOOD_CAFE_BAKERY_ALCOHOL,        required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.FOOD_CAFE_BAKERY_COFFEE,          required = false),
            AttributeDefinition.BoolDef(key = AttributeKey.FOOD_CAFE_BAKERY_BREAKFAST,       required = false),
        )
    }


    // Restaurant

    @TypeAlias("food_asian")
    data class FoodAsian(
        val foodAsianAllYouCanEat: AttributeValue?,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.BoolDef(key = AttributeKey.FOOD_ASIAN_ALL_YOU_CAN_EAT, required = false),
        )
    }


    @TypeAlias("food_greek")
    class FoodGreek : LocationData() {
        override fun schema() = emptyList<AttributeDefinition>()
    }

    @TypeAlias("food_other")
    data class FoodOther(
        val foodOtherCuisine: AttributeValue,
    ) : LocationData() {
        override fun schema() = listOf(
            AttributeDefinition.StringDef(key = AttributeKey.FOOD_OTHER_CUISINE, required = true),
        )
    }
}
