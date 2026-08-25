package com.lerchenflo.schneaggchatv3server.events.eventmodel


enum class EventType {
    SPORT,
    FOOD,

    BEER,
    DRIVING,

    HORSE_RIDING,

    // New
    PARTY,       // birthdays, get-togethers, celebrations
    GAMING,      // board games, video games, LAN
    MOVIE,       // cinema or movie night
    TRIP,        // day trips, weekend trips, vacations
    MEETUP,      // casual hangout with no specific activity
    OUTDOOR,     // hiking, camping, swimming, nature stuff
    OTHER        // fallback / catch-all
}