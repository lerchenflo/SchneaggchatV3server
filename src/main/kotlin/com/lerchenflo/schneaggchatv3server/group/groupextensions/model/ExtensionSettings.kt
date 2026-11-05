package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.data.annotation.TypeAlias

/**
 * Per-extension configuration, set when the extension is created and changeable on rename.
 * Which subclass a type takes is set in [GroupExtensionType.settingsType].
 * Stored in Mongo through ExtensionSettingsWriteConverter/ReadConverter (MongoConfig.kt), like
 * LocationData. The discriminator names (@JsonSubTypes name = @TypeAlias) are the wire names and
 * must match the client.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "_class")
@JsonSubTypes(
    JsonSubTypes.Type(value = ExtensionSettings.None::class,  name = "none"),
    JsonSubTypes.Type(value = ExtensionSettings.Split::class, name = "split"),
    JsonSubTypes.Type(value = ExtensionSettings.Route::class, name = "route"),
)
sealed class ExtensionSettings {

    /** Field limits of these settings. Empty = valid. */
    abstract fun validate(): List<String>

    /** Types without configuration (SHARED_TEXT, QUOTES). */
    @TypeAlias("none")
    data object None : ExtensionSettings() {
        override fun validate() = emptyList<String>()
    }

    /** SPLIT: one ISO 4217 currency for the whole extension; expenses carry no currency. */
    @TypeAlias("split")
    data class Split(
        val currency: String = "EUR",
    ) : ExtensionSettings() {
        override fun validate(): List<String> = buildList {
            if (!currency.matches(Regex("^[A-Z]{3}$"))) add("currency: must be an ISO 4217 code")
        }
    }

    /** ROUTE_PLANNER */
    @TypeAlias("route")
    data class Route(
        val transportMode: String? = null,
        val startDate: Long? = null,
    ) : ExtensionSettings() {
        override fun validate(): List<String> = buildList {
            transportMode?.let { if (it.length > 30) add("transportMode: length ${it.length} exceeds maximum 30") }
        }
    }
}
