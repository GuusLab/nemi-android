package com.guuslab.nemi

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NemiClientTest {
    private val server = MockWebServer()
    private val routes = Routes()
    private lateinit var nemi: NemiClient

    @BeforeTest
    fun setUp() {
        server.dispatcher = routes
        server.start()
        nemi = NemiClient(StaticTokenProvider("nemi_oat_1"), baseUrl = endpointsFor(server).apiBase)
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    @Test
    fun `me decodes snake_case and sends the bearer token`() = runTest {
        routes.on("GET /api/v1/me") {
            json("""{"id":"sub1","name":"Ada","username":"ada","email":"a@x.com","email_verified":true,"new_field":1}""")
        }
        val me = nemi.me()
        assertEquals(NemiUser("sub1", "Ada", "ada", null, "a@x.com", true), me)
        val request = server.takeRequest()
        assertEquals("Bearer nemi_oat_1", request.getHeader("Authorization"))
    }

    @Test
    fun `workspaces and file listing decode`() = runTest {
        routes.on("GET /api/v1/workspaces") { json("""{"workspaces":[{"id":"w1","name":"Home","role":"owner"}]}""") }
        routes.on("GET /api/v1/files") {
            json(
                """{"folders":[{"id":"f1","name":"Trips"}],"files":[{"id":"x1","name":"a.pdf","size":12,
                "type":"application/pdf","workspace_id":"w1","folder_id":null,"created_at":"2026-01-01T00:00:00Z"}]}""",
            )
        }
        assertEquals("owner", nemi.workspaces().single().role)
        val listing = nemi.files.list("w1", limit = 10)
        assertEquals("Trips", listing.folders.single().name)
        val file = listing.files.single()
        assertEquals("w1", file.workspaceId)
        assertNull(file.folderId)
        assertEquals("2026-01-01T00:00:00Z", file.createdAt)

        server.takeRequest()
        val url = server.takeRequest().requestUrl!!
        assertEquals("w1", url.queryParameter("workspace_id"))
        assertEquals("10", url.queryParameter("limit"))
        assertNull(url.queryParameter("folder_id"))
    }

    @Test
    fun `errors map to NemiApiException`() = runTest {
        routes.on("GET /api/v1/files/search") {
            json("""{"error":{"code":"insufficient_scope","message":"This call needs files.read.","required_scope":"files.read"}}""", 403)
        }
        val e = assertFailsWith<NemiApiException> { nemi.files.search("tax") }
        assertEquals(403, e.status)
        assertEquals("insufficient_scope", e.code)
        assertEquals("files.read", e.requiredScope)
        assertEquals("This call needs files.read.", e.message)

        routes.on("GET /api/v1/files/missing") { MockResponse().setResponseCode(404).setBody("<html>") }
        val notFound = assertFailsWith<NemiApiException> { nemi.files.get("missing") }
        assertEquals("not_found", notFound.code)
    }

    @Test
    fun `401 without a refresh surfaces invalid_token`() = runTest {
        routes.on("GET /api/v1/me") {
            json("""{"error":{"code":"invalid_token","message":"Expired"}}""", 401)
                .setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"")
        }
        assertEquals("invalid_token", assertFailsWith<NemiApiException> { nemi.me() }.code)
    }

    @Test
    fun `429 is retried honoring Retry-After`() = runTest {
        var calls = 0
        routes.on("GET /api/v1/me") {
            calls++
            if (calls < 3) {
                json("""{"error":{"code":"rate_limited","message":"Slow down"}}""", 429).setHeader("Retry-After", "1")
            } else {
                json("""{"id":"sub1"}""")
            }
        }
        assertEquals("sub1", nemi.me().id)
        assertEquals(3, calls)
    }

    @Test
    fun `429 gives up after two retries`() = runTest {
        routes.on("GET /api/v1/me") {
            json("""{"error":{"code":"rate_limited","message":"Slow down"}}""", 429).setHeader("Retry-After", "2")
        }
        val e = assertFailsWith<NemiApiException> { nemi.me() }
        assertEquals("rate_limited", e.code)
        assertEquals(2L, e.retryAfterSeconds)
        assertEquals(3, routes.counts["GET /api/v1/me"])
    }

    @Test
    fun `retry after parses seconds and dates`() {
        assertEquals(5L, parseRetryAfterSeconds("5", 0))
        assertEquals(10L, parseRetryAfterSeconds("Thu, 01 Jan 1970 00:00:10 GMT", 0))
        assertNull(parseRetryAfterSeconds("soon", 0))
    }

    @Test
    fun `upload runs the three steps`() = runTest {
        val uploadUrl = server.url("/bucket/put-here").toString()
        routes.on("POST /api/v1/files/uploads") {
            json("""{"upload_id":"u1","upload_url":"$uploadUrl","method":"PUT","expires_at":"2026-01-01T00:15:00Z"}""")
        }
        routes.on("PUT /bucket/put-here") { MockResponse().setResponseCode(200) }
        routes.on("POST /api/v1/files/uploads/u1/complete") {
            json("""{"id":"x9","name":"notes.txt","size":5,"type":"text/plain","workspace_id":"w1","created_at":"2026-01-01T00:00:00Z"}""")
        }
        val file = nemi.files.upload("w1", "notes.txt", "hello".toByteArray(), "text/plain")
        assertEquals("x9", file.id)

        val create = server.takeRequestFor("POST /api/v1/files/uploads")
        val body = NemiJson.parseToJsonElement(create.body.readUtf8()).jsonObject
        assertEquals("w1", body["workspace_id"]!!.jsonPrimitive.content)
        assertEquals("notes.txt", body["name"]!!.jsonPrimitive.content)
        assertEquals("5", body["size"]!!.jsonPrimitive.content)
        assertTrue("folder_id" !in body) // nulls are left out

        val put = server.takeRequestFor("PUT /bucket/put-here")
        assertEquals("hello", put.body.readUtf8())
        assertNull(put.getHeader("Authorization")) // the presigned URL never gets the token
    }

    @Test
    fun `download fetches the short-lived link without a token`() = runTest {
        val link = server.url("/signed/file").toString()
        routes.on("GET /api/v1/files/x1/download") { json("""{"url":"$link","expires_at":"2026-01-01T00:15:00Z"}""") }
        routes.on("GET /signed/file") { MockResponse().setBody("bytes!") }
        assertContentEquals("bytes!".toByteArray(), nemi.files.download("x1"))
        server.takeRequest()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `gallery photos flow follows cursors`() = runTest {
        routes.on("GET /api/v1/gallery/photos") { request ->
            when (request.requestUrl!!.queryParameter("cursor")) {
                null -> json("""{"photos":[{"id":"p1","width":10,"height":20,"size":3,"favorite":true,"thumbnail_url":"t1"}],"next_cursor":"c2"}""")
                "c2" -> json("""{"photos":[{"id":"p2"},{"id":"p3"}],"next_cursor":null}""")
                else -> json("{}", 500)
            }
        }
        val photos = nemi.gallery.photos(workspaceId = "w1", pageSize = 500).toList()
        assertEquals(listOf("p1", "p2", "p3"), photos.map { it.id })
        assertEquals(true, photos[0].favorite)
        assertEquals("t1", photos[0].thumbnailUrl)
        assertEquals("100", server.takeRequest().requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun `gallery upload is multipart`() = runTest {
        routes.on("POST /api/v1/gallery/photos") { json("""{"id":"p9","name":"a.jpg","width":1,"height":1,"size":4,"favorite":false}""") }
        assertEquals("p9", nemi.gallery.upload("w1", "a.jpg", byteArrayOf(1, 2, 3, 4), "image/jpeg").id)
        val request = server.takeRequest()
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readUtf8()
        assertTrue("name=\"workspace_id\"" in body && "name=\"file\"; filename=\"a.jpg\"" in body)
    }

    @Test
    fun `albums docs sheets forms canvas calendar`() = runTest {
        routes.on("GET /api/v1/gallery/albums") { json("""{"albums":[{"id":"a1","name":"Summer","count":3}]}""") }
        routes.on("GET /api/v1/docs") { json("""{"docs":[{"id":"d1","title":"Plan","workspace_id":"w1","updated_at":"2026-01-01T00:00:00Z"}]}""") }
        routes.on("GET /api/v1/docs/d1") { json("""{"id":"d1","title":"Plan","markdown":"# Hi","workspace_id":"w1","updated_at":"x"}""") }
        routes.on("GET /api/v1/sheets/s1") { json("""{"id":"s1","title":"Budget","cells":{"A1":"Rent","B1":950,"C1":true,"D1":null}}""") }
        routes.on("PATCH /api/v1/sheets/s1/cells") { json("{}") }
        routes.on("GET /api/v1/forms/f1") { json("""{"id":"f1","title":"RSVP","fields":[{"type":"text"}],"closes_at":"x"}""") }
        routes.on("GET /api/v1/forms/f1/responses") { json("""{"responses":[{"id":"r1"}]}""") }
        routes.on("GET /api/v1/canvas") { json("""[{"id":"c1","title":"Board"}]""") }
        routes.on("GET /api/v1/calendar/events") {
            json("""{"events":[{"id":"e1","title":"Dinner","start":"2026-01-01T18:00:00Z","end":"2026-01-01T20:00:00Z","all_day":false}]}""")
        }
        routes.on("POST /api/v1/calendar/events") { json("""{"id":"e2","title":"Lunch","start":"s","end":"e","all_day":true}""") }

        assertEquals(3, nemi.gallery.albums("w1").single().count)
        assertEquals("Plan", nemi.docs.list("w1").single().title)
        assertEquals("# Hi", nemi.docs.get("d1").markdown)
        val sheet = nemi.sheets.get("s1")
        assertEquals(JsonPrimitive(950), sheet.cells["B1"])
        nemi.sheets.updateCells("s1", mapOf("A2" to "Food", "B2" to 300, "C2" to null))
        val form = nemi.forms.get("f1")
        assertEquals(1, form.fields!!.size)
        assertEquals("x", form.raw["closes_at"]!!.jsonPrimitive.content)
        assertEquals("r1", nemi.forms.responses("f1").single()["id"]!!.jsonPrimitive.content)
        assertEquals("Board", nemi.canvas.list().single().title)
        assertEquals("Dinner", nemi.calendar.events("2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z").single().title)
        assertEquals(true, nemi.calendar.create(NewCalendarEvent("Lunch", "s", "e", allDay = true)).allDay)

        val patch = server.takeRequestFor("PATCH /api/v1/sheets/s1/cells")
        assertEquals("""{"cells":{"A2":"Food","B2":300,"C2":null}}""", patch.body.readUtf8())
    }

    @Test
    fun `ids cannot escape the path`() = runTest {
        assertFailsWith<IllegalArgumentException> { nemi.files.get("../me") }
    }
}
