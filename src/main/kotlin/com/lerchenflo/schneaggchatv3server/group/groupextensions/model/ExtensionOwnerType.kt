package com.lerchenflo.schneaggchatv3server.group.groupextensions.model

/**
 * What an extension belongs to. Only groups for now; FRIENDSHIP (1:1 chats) is reserved, so the
 * owner key never has to be renamed when it is added.
 */
enum class ExtensionOwnerType {
    GROUP,
}
