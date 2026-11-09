package com.lerchenflo.schneaggchatv3server.website.admin

import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Serves the admin panel. Page and script live in `resources/chefdev/`, outside `static/`, so the
 * public site never ships them: the script is inlined into this one response and has no URL of its
 * own. The page carries no data (everything comes from the authenticated `/chefdev/api`), so it
 * stays public in `SecurityConfig`, because a browser navigation cannot send the token.
 */
@RestController
class ChefdevPageController {

    // Read on the first request, so a broken file only breaks the admin panel, not the startup.
    private val page: String by lazy {
        val html = read("chefdev/chefdev.html")
        check(SCRIPT_MARKER in html) { "chefdev.html has no $SCRIPT_MARKER to inline the script into" }
        html.replace(SCRIPT_MARKER, "<script>\n${read("chefdev/chefdev.js")}\n</script>")
    }

    @GetMapping("/chefdev.html")
    fun page(): ResponseEntity<String> = ResponseEntity.ok()
        .contentType(MediaType("text", "html", Charsets.UTF_8))
        .cacheControl(CacheControl.noStore())
        .header("X-Robots-Tag", "noindex, nofollow")
        .body(page)

    private fun read(path: String): String =
        ClassPathResource(path).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

    private companion object {
        const val SCRIPT_MARKER = """<script data-inline="chefdev.js"></script>"""
    }
}
