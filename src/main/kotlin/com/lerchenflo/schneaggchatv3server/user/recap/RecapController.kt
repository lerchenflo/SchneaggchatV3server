package com.lerchenflo.schneaggchatv3server.user.recap

import com.lerchenflo.schneaggchatv3server.core.security.requireAuth
import com.lerchenflo.schneaggchatv3server.user.recap.model.RecapResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Year
import java.time.ZoneId

private const val FIRST_RECAP_YEAR = 2024

@RestController
@RequestMapping("/recap")
class RecapController(
    private val recapService: RecapService,
) {

    @GetMapping
    fun getRecap(@RequestParam(required = false) year: Int?): RecapResponse {
        val requesterId = requireAuth()
        val currentYear = Year.now(ZoneId.of("Europe/Vienna")).value
        val resolvedYear = year ?: currentYear
        // Out-of-range years made ZonedDateTime throw (500, logged as an exception against the requester)
        require(resolvedYear in FIRST_RECAP_YEAR..currentYear) { "Invalid recap year" }
        return recapService.buildRecap(requesterId, resolvedYear)
    }
}
