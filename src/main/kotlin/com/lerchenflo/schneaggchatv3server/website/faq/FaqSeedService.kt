@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server.website.faq

import com.lerchenflo.schneaggchatv3server.util.AppLogger
import com.lerchenflo.schneaggchatv3server.repository.FaqRepository
import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqCategory
import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqEntry
import com.lerchenflo.schneaggchatv3server.website.faq.model.FaqText
import org.springframework.stereotype.Service
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Inserts the FAQ entries the site ships with, so a fresh database is never an empty FAQ page.
 *
 * Runs on every startup and inserts only the seeds whose [FaqEntry.seedKey] is missing from the
 * collection, soft-deleted rows included - an entry an admin deleted or rewrote stays that way.
 * Austrian is left empty on purpose: the de-at copy is hand-written, and the page falls back to
 * German until it exists.
 */
@Service
class FaqSeedService(
    private val faqRepository: FaqRepository,
) {

    fun seedMissingEntries() {
        val existingKeys = faqRepository.findAll().mapNotNull { it.seedKey }.toSet()
        val missing = seedEntries.filter { it.seedKey !in existingKeys }
        if (missing.isEmpty()) return

        val now = Clock.System.now()
        faqRepository.saveAll(
            missing.map {
                FaqEntry(
                    category = it.category,
                    sortOrder = it.sortOrder,
                    seedKey = it.seedKey,
                    german = FaqText(it.germanQuestion, it.germanAnswer),
                    english = FaqText(it.englishQuestion, it.englishAnswer),
                    austrian = FaqText(it.voriQuestion, it.voriAnswer),
                    createdAt = now,
                    updatedAt = now,
                )
            }
        )
        AppLogger.info("Seeded ${missing.size} FAQ entries")
    }
}

private class FaqSeed(
    val seedKey: String,
    val category: FaqCategory,
    val sortOrder: Int,
    val germanQuestion: String,
    val germanAnswer: String,
    val englishQuestion: String,
    val englishAnswer: String,
    val voriQuestion: String,
    val voriAnswer: String
)

private val seedEntries = listOf(
    FaqSeed(
        seedKey = "general_what_is_schneaggchat",
        category = FaqCategory.GENERAL,
        sortOrder = 0,
        germanQuestion = "Was ist Schneaggchat?",
        germanAnswer = "Schneaggchat ist eine kostenlose Chat-App ohne Werbung für Android, iOS und Desktop - mit Live-Karte, Events, Umfragen, Minispielen und Vorarlbergerisch als Sprache.",
        englishQuestion = "What is Schneaggchat?",
        englishAnswer = "Schneaggchat is a free, ad-free chat app for Android, iOS and desktop - with a live map, events, polls, mini games and Vorarlberg dialect as a language option.",
        voriQuestion = "Was isch Schneaggchat?",
        voriAnswer = "Schneaggchat isch a kostenlose Chat-App ohne Werbung für Android, iOS und Desktop mit Live-Karta, Events, Umfroga, Minispiele und Vorarlbergerisch als Sproch.",
    ),
    FaqSeed(
        seedKey = "general_what_does_it_cost",
        category = FaqCategory.GENERAL,
        sortOrder = 1,
        germanQuestion = "Was kostet Schneaggchat?",
        germanAnswer = "Nichts. Schneaggchat ist kostenlos, ohne Werbung und ohne Abo. Freiwillig unterstützen kannst du uns über die Spendenseite.",
        englishQuestion = "What does Schneaggchat cost?",
        englishAnswer = "Nothing. Schneaggchat is free, with no ads and no subscription. If you want to support us, you can do so voluntarily on the donations page.",
        voriQuestion = "Was kostat Schneaggchat",
        voriAnswer = "Garnix. Schneaggchat isch gratis, ohne Werbung und ohne Abo. Freiwillige spenda sind immer wilkommen, kannsch uf da Spendensitta abgia.",
    ),
    FaqSeed(
        seedKey = "account_no_verification_email",
        category = FaqCategory.ACCOUNT,
        sortOrder = 0,
        germanQuestion = "Warum bekomme ich keine Verifizierungs-E-Mail?",
        germanAnswer = "Wenn die E-Mail-Adresse auf deinem Verifizierungs-Screen stimmt, wurde die E-Mail verschickt. Warte bitte ein bis zwei Minuten und schau auch im Spam-Ordner nach.",
        englishQuestion = "Why am I not getting a verification email?",
        englishAnswer = "If the email address shown on your verification screen is correct, the email has been sent. Please wait one to two minutes and also check your spam folder.",
        voriQuestion = "Warum kriag i kua Verifizierungs-E-Mail?",
        voriAnswer = "Die E-Mail wird automatisch an dine beim Registriera igeabane E-Mail gschickt. Bitte wart 1-2 Minuta und luag o im Spam Ordner noch.",
    ),
    FaqSeed(
        seedKey = "account_forgot_password",
        category = FaqCategory.ACCOUNT,
        sortOrder = 1,
        germanQuestion = "Ich habe mein Passwort vergessen - was jetzt?",
        germanAnswer = "Nutze im Login-Screen \"Passwort vergessen\". Wir schicken dir eine E-Mail mit einem Link, über den du ein neues Passwort setzen kannst.",
        englishQuestion = "I forgot my password - what now?",
        englishAnswer = "Use \"Forgot password\" on the login screen. We send you an email with a link that lets you set a new password.",
        voriQuestion = "I hob mi Passwort vergeassa",
        voriAnswer = "Ufam Loginscreen und uf da Website gits Buttons zum ufd Passwort Reset sitta ko, döt kannsch di Passwort zrucksetza.",
    ),
    FaqSeed(
        seedKey = "chats_end_to_end_encryption",
        category = FaqCategory.CHATS,
        sortOrder = 0,
        germanQuestion = "Sind meine Nachrichten Ende-zu-Ende-verschlüsselt?",
        germanAnswer = "Noch nicht. Die Übertragung zwischen App und Server ist verschlüsselt, an der Ende-zu-Ende-Verschlüsselung arbeiten wir noch.",
        englishQuestion = "Are my messages end-to-end encrypted?",
        englishAnswer = "Not yet. The connection between the app and the server is encrypted, but end-to-end encryption is still being worked on.",
        voriQuestion = "Sind mine Nachrichta Ende-zu-Ende verschlüsselt?",
        voriAnswer = "Aktuell no ned. Dine Nochrichta werrand verschlüsselt an Server gschickt, aber eze simma no dra.",
    ),
    FaqSeed(
        seedKey = "notifications_sometimes_not_working",
        category = FaqCategory.NOTIFICATIONS,
        sortOrder = 0,
        germanQuestion = "Warum funktionieren die Benachrichtigungen manchmal nicht?",
        germanAnswer = "Wir arbeiten mit Hochdruck daran.",
        englishQuestion = "Why do notifications sometimes not work?",
        englishAnswer = "We are working hard on it.",
        voriQuestion = "Warum tuand Notis / Notifications / Benachrichtigunga manchmol ned?",
        voriAnswer = "Es isch a unendliches leiden, mir sind immer voll dra und es wird immer besser.",
    ),
    FaqSeed(
        seedKey = "notifications_alarm_feature",
        category = FaqCategory.NOTIFICATIONS,
        sortOrder = 1,
        germanQuestion = "Was macht das Weckerfeature?",
        germanAnswer = "Damit spielst du bei Freunden einen Weckton ab, auch wenn deren Gerät auf lautlos steht. Das Feature gibt es nur auf Android.",
        englishQuestion = "What does the alarm feature do?",
        englishAnswer = "It plays an alarm sound on your friends' devices, even when their device is on silent. The feature is Android only.",
        voriQuestion = "Was tuat s Weckerfeature?",
        voriAnswer = "S Weckerfeature spielt bei andra lüt an Alarm ab, wie bei nam normala Wecker dean ma stella kann, o wenns Handy uf Lautlos isch.",
    ),
    FaqSeed(
        seedKey = "technical_report_bug",
        category = FaqCategory.TECHNICAL,
        sortOrder = 0,
        germanQuestion = "Ich habe einen Fehler gefunden - wo melde ich den?",
        germanAnswer = "Schreib uns an schneaggchat@gmail.com und beschreib kurz, was passiert ist und auf welchem Gerät. Wir antworten so schnell wir können.",
        englishQuestion = "I found a bug - where do I report it?",
        englishAnswer = "Write to schneaggchat@gmail.com and briefly describe what happened and on which device. We answer as fast as we can.",
        voriQuestion = "I hob an Fehler gfunda - wo meald i dean?",
        voriAnswer = "Fehler kannsch an die E-Mail unta schicka, mir werrand se so schneall wie möglich beheba.",
    ),
    FaqSeed(
        seedKey = "technical_open_source",
        category = FaqCategory.TECHNICAL,
        sortOrder = 1,
        germanQuestion = "Ist Schneaggchat Open Source?",
        germanAnswer = "Ja. App und Server sind Open Source, den Code findest du auf GitHub.",
        englishQuestion = "Is Schneaggchat open source?",
        englishAnswer = "Yes. Both the app and the server are open source, and you find the code on GitHub.",
        voriQuestion = "Isch Schneaggchat Open Source?",
        voriAnswer = "Jo. App und Server sind Open Source, da Code kannsch uf Github aluaga und wennd willsch o neue Features programmiera.",
    ),
    
)
