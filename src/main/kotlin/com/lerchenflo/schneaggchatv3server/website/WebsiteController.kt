package com.lerchenflo.schneaggchatv3server.website

import com.lerchenflo.schneaggchatv3server.util.LoggingService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Component
@Controller
class WebsiteController(
    private val loggingService: LoggingService
) {

    @GetMapping("/stats.html")
    fun getStats(model: Model): String {
        // Count logs by type
        val stats = loggingService.getStats()

        // Calculate total
        val total = stats.values.sum()

        model.addAttribute("stats", stats)
        model.addAttribute("total", total)
        model.addAttribute("deviceCounts", loggingService.getActiveDeviceCount())

        return "stats"
    }

}