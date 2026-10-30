package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

import kotlin.reflect.KClass

/**
 * Kind of a group extension. The enum names are the wire names and must match the client.
 * Every type has exactly one [com.lerchenflo.schneaggchatv3server.group.groupextensions.ExtensionItemData]
 * subclass for its items.
 *
 * @param maxItems how many items one extension of this type may hold
 * @param settingsType the only [ExtensionSettings] subclass an extension of this type accepts
 */
enum class GroupExtensionType(val maxItems: Int, val settingsType: KClass<out ExtensionSettings>) {
    SHARED_TEXT(maxItems = 1, settingsType = ExtensionSettings.None::class),
    SPLIT(maxItems = 1000, settingsType = ExtensionSettings.Split::class),
    QUOTES(maxItems = 1000, settingsType = ExtensionSettings.None::class),
    ROUTE_PLANNER(maxItems = 1000, settingsType = ExtensionSettings.Route::class),
}
