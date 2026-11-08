package com.lerchenflo.schneaggchatv3server.website

import com.lerchenflo.schneaggchatv3server.util.LoggingService
import com.lerchenflo.schneaggchatv3server.website.donations.DonationLookupService
import com.lerchenflo.schneaggchatv3server.website.faq.FaqLookupService
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Component
@Controller
class WebsiteController(
    private val loggingService: LoggingService,
    private val faqLookupService: FaqLookupService,
    private val donationLookupService: DonationLookupService,
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

    // FAQ and donations are rendered here so their content is in the HTML for crawlers that do not
    // run JavaScript. faq.js and donations-data.js still load the API and render again, for the
    // language switch.

    @GetMapping("/faq.html")
    fun getFaq(model: Model): String {
        model.addAttribute("faqSections", faqLookupService.germanSections())
        return "faq"
    }

    @GetMapping("/donations.html")
    fun getDonations(model: Model): String {
        model.addAttribute("donationsPage", donationLookupService.page())
        return "donations"
    }

}