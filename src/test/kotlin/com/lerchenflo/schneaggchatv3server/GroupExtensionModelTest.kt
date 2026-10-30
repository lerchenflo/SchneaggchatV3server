@file:OptIn(ExperimentalTime::class)

package com.lerchenflo.schneaggchatv3server

import com.lerchenflo.schneaggchatv3server.core.MongoConfig
import com.lerchenflo.schneaggchatv3server.group.groupextensions.ExtensionItemData
import com.lerchenflo.schneaggchatv3server.group.groupextensions.GroupExtensionController
import com.lerchenflo.schneaggchatv3server.group.groupextensions.model.*
import com.lerchenflo.schneaggchatv3server.schneaggmap.model.LatLong
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.convert.MappingMongoConverter
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver
import org.springframework.data.mongodb.core.mapping.MongoMappingContext
import tools.jackson.databind.json.JsonMapper
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@DisplayName("Group extension model Tests")
class GroupExtensionModelTest {

    private val userA = ObjectId()
    private val userB = ObjectId()

    // Same conversions as the app, without a database
    private val converter: MappingMongoConverter = run {
        val conversions = MongoConfig().customConversions()
        val context = MongoMappingContext().apply {
            setSimpleTypeHolder(conversions.simpleTypeHolder)
            afterPropertiesSet()
        }
        MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context).apply {
            setCustomConversions(conversions)
            afterPropertiesSet()
        }
    }

    // Jackson 3, like the Spring Boot 4 HTTP layer
    private val httpMapper = JsonMapper.builder().findAndAddModules().build()

    private fun extension(type: GroupExtensionType, settings: ExtensionSettings, vararg data: ExtensionItemData): GroupExtension {
        val now = Clock.System.now()
        return GroupExtension(
            ownerId = ObjectId(),
            type = type,
            title = "Italien 2026",
            settings = settings,
            items = data.map { GroupExtensionItem(data = it, createdBy = userA, createdAt = now, updatedBy = userA, updatedAt = now) },
            createdBy = userA,
            createdAt = now,
            updatedBy = userA,
            updatedAt = now,
        )
    }

    private fun roundTrip(extension: GroupExtension): Pair<Document, GroupExtension> {
        val doc = Document()
        converter.write(extension, doc)
        return doc to converter.read(GroupExtension::class.java, doc)
    }

    // ===== MONGO MAPPING =====

    @Test
    @DisplayName("Every payload and settings type survives a Mongo round trip")
    fun `mongo round trip keeps all payloads`() {
        val payloads = listOf(
            extension(GroupExtensionType.SHARED_TEXT, ExtensionSettings.None,
                ExtensionItemData.SharedText("Hallo", listOf(ExtensionItemData.SharedText.WriterRun(5, userA.toHexString())))),
            extension(GroupExtensionType.SPLIT, ExtensionSettings.Split("CHF"),
                ExtensionItemData.SplitExpense(
                    title = "Pizza", amountCents = 4200, paidBy = userA.toHexString(),
                    shares = mapOf(userA.toHexString() to ExtensionItemData.SplitExpense.Share(1, 0), userB.toHexString() to ExtensionItemData.SplitExpense.Share(2, 500)),
                    date = 1L, isSettlement = true, emoji = "🍕",
                )),
            extension(GroupExtensionType.QUOTES, ExtensionSettings.None,
                ExtensionItemData.Quote(text = "Zemma!", saidByName = "Fabi", saidAt = 2L)),
            extension(GroupExtensionType.ROUTE_PLANNER, ExtensionSettings.Route("bike", 3L),
                ExtensionItemData.RoutePoint(coordinates = LatLong(47.4, 9.7), name = "Dornbirn", sortIndex = 0, kind = RoutePointKind.POI)),
        )

        payloads.forEach { original ->
            val (_, read) = roundTrip(original)
            assertEquals(original, read)
        }
    }

    @Test
    @DisplayName("Stored document uses _id for items, _class discriminators and no derived fields")
    fun `mongo document shape`() {
        val original = extension(GroupExtensionType.SPLIT, ExtensionSettings.Split(),
            ExtensionItemData.SplitExpense(title = "Pizza", amountCents = 100, paidBy = userA.toHexString(),
                shares = mapOf(userA.toHexString() to ExtensionItemData.SplitExpense.Share()), date = 1L))
        val (doc, _) = roundTrip(original)

        val item = doc.getList("items", Document::class.java).single()
        assertEquals(original.items.single().id, item["_id"])
        val data = item["data"] as Document
        assertEquals("split_expense", data["_class"])
        assertEquals(false, data["isSettlement"])
        assertFalse(data.containsKey("extensionType"))
        assertEquals("split", (doc["settings"] as Document)["_class"])
    }

    @Test
    @DisplayName("Settings None is stored and read as {_class: none}")
    fun `mongo none settings`() {
        val (doc, read) = roundTrip(extension(GroupExtensionType.QUOTES, ExtensionSettings.None))
        assertEquals(Document("_class", "none"), doc["settings"])
        assertEquals(ExtensionSettings.None, read.settings)
    }

    // ===== HTTP JSON =====

    @Test
    @DisplayName("Item request without server-set fields and defaults is read")
    fun `http item request uses defaults`() {
        val shared = httpMapper.readValue(
            """{"extensionId":"${ObjectId().toHexString()}","itemId":null,"data":{"_class":"shared_text","text":"Hallo"}}""",
            GroupExtensionController.ItemRequest::class.java,
        )
        assertEquals(ExtensionItemData.SharedText("Hallo"), shared.data)

        val split = httpMapper.readValue(
            """{"extensionId":"${ObjectId().toHexString()}","data":{"_class":"split_expense","title":"Pizza","amountCents":100,"paidBy":"a","shares":{"a":{}},"date":1}}""",
            GroupExtensionController.ItemRequest::class.java,
        )
        assertEquals(ExtensionItemData.SplitExpense.Share(weight = 1, fixedCents = 0), (split.data as ExtensionItemData.SplitExpense).shares["a"])

        val route = httpMapper.readValue(
            """{"extensionId":"${ObjectId().toHexString()}","data":{"_class":"route_point","coordinates":{"lat":1.0,"long":2.0},"name":"X","sortIndex":0}}""",
            GroupExtensionController.ItemRequest::class.java,
        )
        assertEquals(RoutePointKind.STOP, (route.data as ExtensionItemData.RoutePoint).kind)
    }

    @Test
    @DisplayName("Extension request reads none settings")
    fun `http extension request none settings`() {
        val request = httpMapper.readValue(
            """{"ownerType":"GROUP","ownerId":"${ObjectId().toHexString()}","type":"QUOTES","title":"Q","settings":{"_class":"none"}}""",
            GroupExtensionController.ExtensionRequest::class.java,
        )
        assertEquals(ExtensionSettings.None, request.settings)
    }

    @Test
    @DisplayName("Response JSON has the contract keys")
    fun `http response shape`() {
        val response = extension(GroupExtensionType.SPLIT, ExtensionSettings.Split(),
            ExtensionItemData.SplitExpense(title = "Pizza", amountCents = 100, paidBy = userA.toHexString(),
                shares = mapOf(userA.toHexString() to ExtensionItemData.SplitExpense.Share()), date = 1L)
        ).toGroupExtensionResponse()

        val json = httpMapper.readTree(httpMapper.writeValueAsString(response))
        assertEquals("SPLIT", json["type"].asString())
        assertEquals("split", json["settings"]["_class"].asString())
        val data = json["items"][0]["data"]
        assertEquals("split_expense", data["_class"].asString())
        assertTrue(data.has("isSettlement"))
        assertFalse(data.has("extensionType"))
        assertEquals("""{"_class":"none"}""", httpMapper.writeValueAsString(ExtensionSettings.None as ExtensionSettings))
    }

    // ===== SHARED TEXT WRITERS =====

    private val a = "a"
    private val b = "b"
    private fun runs(vararg pairs: Pair<Int, String>) = pairs.map { ExtensionItemData.SharedText.WriterRun(it.first, it.second) }
    private fun write(previous: ExtensionItemData.SharedText?, text: String, by: String) =
        ExtensionItemData.SharedText(text).prepareForSave(previous, by) as ExtensionItemData.SharedText

    @Test
    @DisplayName("First text belongs to its writer")
    fun `writers on create`() {
        assertEquals(runs(5 to a), write(null, "Hallo", a).writers)
        assertEquals(emptyList<ExtensionItemData.SharedText.WriterRun>(), write(null, "", a).writers)
    }

    @Test
    @DisplayName("Insert, append, delete and replace keep the other writers")
    fun `writers on edit`() {
        val first = write(null, "Hallo zemma", a)
        assertEquals(runs(6 to a, 3 to b, 5 to a), write(first, "Hallo du zemma", b).writers)   // insert "du "
        assertEquals(runs(11 to a, 1 to b), write(first, "Hallo zemma!", b).writers)           // append
        assertEquals(runs(5 to a), write(first, "Hallo", b).writers)                           // delete end
        assertEquals(runs(6 to a, 2 to b, 3 to a), write(first, "Hallo dumma", b).writers)    // replace "ze" -> "du"
        assertEquals(runs(14 to a), write(first, "Hallo du zemma", a).writers)                 // own edit merges
    }

    @Test
    @DisplayName("An emoji is never split between two writers")
    fun `writers keep surrogate pairs whole`() {
        val first = write(null, "x😀", a)
        val edited = write(first, "x😃", b)
        assertEquals(runs(1 to a, 2 to b), edited.writers)
        assertEquals(edited.text.length, edited.writers.sumOf { it.length })
    }

    // ===== VALIDATION =====

    @Test
    @DisplayName("Split rules: fixed amounts, weights and transfers")
    fun `split validation`() {
        fun expense(shares: Map<String, ExtensionItemData.SplitExpense.Share>, amount: Long = 1000, settlement: Boolean = false) =
            ExtensionItemData.SplitExpense(title = if (settlement) "" else "X", amountCents = amount, paidBy = a, shares = shares, date = 0, isSettlement = settlement)

        assertTrue(expense(mapOf(a to ExtensionItemData.SplitExpense.Share(1, 300), b to ExtensionItemData.SplitExpense.Share(2, 0))).validate().isEmpty())
        assertTrue(expense(mapOf(a to ExtensionItemData.SplitExpense.Share(0, 400), b to ExtensionItemData.SplitExpense.Share(0, 600))).validate().isEmpty())
        assertFalse(expense(mapOf(a to ExtensionItemData.SplitExpense.Share(0, 400))).validate().isEmpty())            // rest has nowhere to go
        assertFalse(expense(mapOf(a to ExtensionItemData.SplitExpense.Share(1, 2000))).validate().isEmpty())           // fixed above total
        assertTrue(expense(mapOf(b to ExtensionItemData.SplitExpense.Share()), settlement = true).validate().isEmpty())
        assertFalse(expense(mapOf(a to ExtensionItemData.SplitExpense.Share()), settlement = true).validate().isEmpty()) // transfer to the payer
    }

    @Test
    @DisplayName("Every type names its own settings class")
    fun `settings per type`() {
        assertTrue(GroupExtensionType.SPLIT.settingsType.isInstance(ExtensionSettings.Split()))
        assertFalse(GroupExtensionType.SPLIT.settingsType.isInstance(ExtensionSettings.None))
        assertTrue(GroupExtensionType.QUOTES.settingsType.isInstance(ExtensionSettings.None))
        assertEquals(1, GroupExtensionType.SHARED_TEXT.maxItems)
    }
}
