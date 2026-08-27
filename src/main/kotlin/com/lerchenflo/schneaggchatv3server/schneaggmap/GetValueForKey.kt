package com.lerchenflo.schneaggchatv3server.schneaggmap

import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeKey
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.AttributeValue

/**
 * Resolves one attribute's value by its [AttributeKey]. Replaces the old reflection-based lookup
 * (`data::class.members.firstOrNull { it.name == key }`) - a mismatch here is now a compile error
 * (the outer `when` over the sealed [LocationData] is exhaustive) instead of a silently-null value.
 * Mirrors the client's `GetSetValueForKey.kt`.
 */
fun LocationData.getValueByKey(key: AttributeKey): AttributeValue? = when (this) {

    // Traffic & Hazards
    is LocationData.Radar -> when (key) {
        AttributeKey.RADAR_SPEED_LIMIT -> radarSpeedLimit
        AttributeKey.RADAR_MOBILE      -> radarMobile
        AttributeKey.RADAR_RED_LIGHT   -> radarRedLight
        else -> null
    }

    is LocationData.Police -> when (key) {
        AttributeKey.POLICE_LAST_SEEN -> policeLastSeen
        else -> null
    }

    // Rider Spots
    is LocationData.MountainStreet -> when (key) {
        AttributeKey.MOUNTAIN_STREET_MAUT_FEE        -> mountainStreetMautFee
        AttributeKey.MOUNTAIN_STREET_HEIGHT_LIMIT    -> mountainStreetHeightLimit
        AttributeKey.MOUNTAIN_STREET_CLOSED_IN_WINTER -> mountainStreetClosedInWinter
        else -> null
    }
    is LocationData.Wheeliespot -> when (key) {
        AttributeKey.WHEELIESPOT_ONLY_ON_WEEKENDS -> wheeliespotOnlyOnWeekends
        else -> null
    }
    is LocationData.OffroadMotorcycle -> when (key) {
        AttributeKey.OFFROAD_MOTORCYCLE_LEGAL     -> offroadMotorcycleLegal
        AttributeKey.OFFROAD_MOTORCYCLE_MOTOCROSS -> offroadMotorcycleMotocross
        AttributeKey.OFFROAD_MOTORCYCLE_ENDURO    -> offroadMotorcycleEnduro
        else -> null
    }
    is LocationData.Viewpoint -> when (key) {
        AttributeKey.VIEWPOINT_LIE_DOWN_FRIENDLY -> viewpointLieDownFriendly
        else -> null
    }

    // Nature & Activities
    is LocationData.Camping -> when (key) {
        AttributeKey.CAMPING_OFFICIAL           -> campingOfficial
        AttributeKey.CAMPING_WATER_DISTANCE      -> campingWaterDistance
        AttributeKey.CAMPING_SITTING_POSSIBILITY -> campingSittingPossibility
        AttributeKey.CAMPING_GRILL_POSSIBILITY   -> campingGrillPossibility
        else -> null
    }
    is LocationData.SwimmingLocation -> when (key) {
        AttributeKey.SWIMMING_INDOOR           -> swimmingIndoor
        AttributeKey.SWIMMING_JUMP_SPOT        -> swimmingJumpSpot
        AttributeKey.SWIMMING_LIE_DOWN_FRIENDLY -> swimmingLieDownFriendly
        AttributeKey.SWIMMING_PRICE            -> swimmingPrice
        else -> null
    }
    is LocationData.Climbingspot -> when (key) {
        AttributeKey.CLIMBINGSPOT_VIA_FERRATA -> climbingspotViaFerrata
        AttributeKey.CLIMBINGSPOT_OUTDOOR     -> climbingspotOutdoor
        AttributeKey.CLIMBINGSPOT_PRICE       -> climbingspotPrice
        else -> null
    }

    // Sport
    is LocationData.Volleyball -> when (key) {
        AttributeKey.VOLLEYBALL_GOOD_NET   -> volleyballGoodNet
        AttributeKey.VOLLEYBALL_GOOD_FIELD -> volleyballGoodField
        AttributeKey.VOLLEYBALL_OUTDOOR    -> volleyballOutdoor
        else -> null
    }
    is LocationData.Bicycle -> when (key) {
        AttributeKey.BICYCLE_LEGAL             -> bicycleLegal
        AttributeKey.BICYCLE_DIFFICULTY        -> bicycleDifficulty
        AttributeKey.BICYCLE_UNDERGROUND_TYPE  -> bicycleUndergroundType
        else -> null
    }
    is LocationData.OutdoorFitness -> when (key) {
        AttributeKey.OUTDOOR_FITNESS_SHADOW -> outdoorFitnessShadow
        else -> null
    }
    is LocationData.TableTennis -> when (key) {
        AttributeKey.TABLE_TENNIS_PRIVATE -> tableTennisPrivate
        else -> null
    }
    is LocationData.Tennis -> when (key) {
        AttributeKey.TENNIS_PADDLE -> tennisPaddle
        else -> null
    }

    // Social & Entertainment
    is LocationData.SightSeeing -> when (key) {
        AttributeKey.SIGHTSEEING_ENTRY_FEE -> sightseeingEntryFee
        else -> null
    }
    is LocationData.PartyLocation -> when (key) {
        AttributeKey.PARTY_ENTRY_FEE -> partyEntryFee
        else -> null
    }
    is LocationData.Wifi -> when (key) {
        AttributeKey.WIFI_SSID     -> wifiSsid
        AttributeKey.WIFI_PASSWORD -> wifiPassword
        else -> null
    }

    // Fast Food & Snacks
    is LocationData.FoodKebab -> when (key) {
        AttributeKey.FOOD_KEBAB_PRICE -> foodKebabPrice
        else -> null
    }
    is LocationData.FoodPizza -> when (key) {
        AttributeKey.FOOD_PIZZA_MARGARITA_PRICE -> foodPizzaMargaritaPrice
        else -> null
    }
    is LocationData.FoodBurger -> when (key) {
        AttributeKey.FOOD_BURGER_CHEESEBURGER_PRICE -> foodBurgerCheeseburgerPrice
        else -> null
    }
    is LocationData.FoodBeer -> when (key) {
        AttributeKey.FOOD_BEER_PRICE -> foodBeerPrice
        else -> null
    }
    is LocationData.FoodIce -> when (key) {
        AttributeKey.FOOD_ICE_SCOOP_PRICE -> foodIceScoopPrice
        else -> null
    }
    is LocationData.FoodCafeBakery -> when (key) {
        AttributeKey.FOOD_CAFE_BAKERY_OUTDOOR_SEATING -> foodCafeBakeryOutdoorSeating
        AttributeKey.FOOD_CAFE_BAKERY_ALCOHOL         -> foodCafeBakeryAlcohol
        AttributeKey.FOOD_CAFE_BAKERY_COFFEE          -> foodCafeBakeryCoffee
        AttributeKey.FOOD_CAFE_BAKERY_BREAKFAST       -> foodCafeBakeryBreakfast
        else -> null
    }

    // Restaurant
    is LocationData.FoodAsian -> when (key) {
        AttributeKey.FOOD_ASIAN_ALL_YOU_CAN_EAT -> foodAsianAllYouCanEat
        else -> null
    }
    is LocationData.FoodGreek -> null
    is LocationData.FoodOther -> when (key) {
        AttributeKey.FOOD_OTHER_CUISINE -> foodOtherCuisine
        else -> null
    }
}
