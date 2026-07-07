package com.lerchenflo.schneaggchatv3server.recap

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.recap.model.RecapResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Year
import java.time.ZoneId

@RestController
@RequestMapping("/recap")
class RecapController(
    private val recapService: RecapService,
) {

    @GetMapping
    fun getRecap(@RequestParam(required = false) year: Int?): RecapResponse {
        val requesterId = requireAuth()
        val resolvedYear = year ?: Year.now(ZoneId.of("Europe/Vienna")).value
        return recapService.buildRecap(requesterId, resolvedYear)
    }
}
