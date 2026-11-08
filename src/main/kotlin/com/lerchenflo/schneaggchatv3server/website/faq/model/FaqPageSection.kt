package com.lerchenflo.schneaggchatv3server.website.faq.model

/** One category of the server-rendered /faq.html, in German. */
data class FaqPageSection(
    val label: String,
    val entries: List<FaqTextResponse>,
)
