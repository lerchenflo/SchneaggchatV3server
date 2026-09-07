package com.lerchenflo.schneaggchatv3server.website.faq

import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqListResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class FaqController(
    private val faqLookupService: FaqLookupService,
) {

    /** Public - powers the /faq.html page. No auth: the FAQ is marketing copy. */
    @GetMapping("/public/faq")
    fun listFaq(): FaqListResponse {
        return faqLookupService.listPublic()
    }
}
