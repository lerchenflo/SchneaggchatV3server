package com.lerchenflo.schneaggchatv3server.core

import com.lerchenflo.schneaggchatv3server.schneaggmap.LocationData
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.config.AbstractMongoClientConfiguration
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.MongoCustomConversions
import org.springframework.data.mongodb.core.mapping.MongoMappingContext

@Bean
fun mongoMappingContext(
    applicationContext: ApplicationContext
): MongoMappingContext {
    val context = MongoMappingContext()
    context.setInitialEntitySet(setOf(
        LocationData.Radar::class.java,
        LocationData.Street::class.java,
        LocationData.Camping::class.java,
        LocationData.SightSeeing::class.java,
        LocationData.SwimmingLocation::class.java,
        LocationData.PartyLocation::class.java,
        LocationData.Food::class.java,
    ))
    return context
}