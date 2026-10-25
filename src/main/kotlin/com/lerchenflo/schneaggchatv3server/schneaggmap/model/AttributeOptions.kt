package com.lerchenflo.schneaggchatv3server.schneaggmap.model

/*
 * Option sets for [AttributeDefinition.EnumDef] attributes. The constant names are the stored/wire
 * values, so they must match the client's enums exactly - never rename a constant without a
 * migration, only add new ones.
 */

enum class BicycleUndergroundType {
    ASPHALT,
    GRAVEL,
    DIRT,
    TRAIL,
    OTHER,
}

/** Whether a sports/activity venue is indoors, outdoors or has both. */
enum class VenueSetting {
    INDOOR,
    OUTDOOR,
    BOTH,
}

enum class OffroadDiscipline {
    MOTOCROSS,
    ENDURO,
    BOTH,
}

enum class CampingKind {
    OFFICIAL_SITE,
    WILD_CAMPING,
    TOLERATED,
}
