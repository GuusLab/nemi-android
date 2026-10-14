package com.guuslab.nemi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.OutputStream

/** Files (`files.read`, `files.write`). Vault files are never reachable. */
public class FilesApi internal constructor(private val client: NemiClient) {
    /** `GET /files`. Leave [folderId] null for the top level. */
    public suspend fun list(workspaceId: String, folderId: String? = null, limit: Int? = null): NemiFileListing =
        client.get(
            "files",
            NemiFileListing.serializer(),
            listOf("workspace_id" to workspaceId, "folder_id" to folderId, "limit" to limit),
        )

    /** `GET /files/search`. */
    public suspend fun search(query: String, workspaceId: String? = null, limit: Int? = null): List<NemiFile> =
        client.get(
            "files/search",
            FilesResponse.serializer(),
            listOf("q" to query, "workspace_id" to workspaceId, "limit" to limit),
        ).files

    /** `GET /files/{id}`. */
    public suspend fun get(id: String): NemiFile = client.get("files/${seg(id)}", NemiFile.serializer())

    /** `GET /files/{id}/download`: a link that works for 15 minutes without a token. */
    public suspend fun downloadLink(id: String): NemiDownloadLink =
        client.get("files/${seg(id)}/download", NemiDownloadLink.serializer())

    /** Downloads the file into memory. Use [downloadTo] for large files. */
    public suspend fun download(id: String): ByteArray {
        val link = downloadLink(id)
        return fetchUnauthenticated(link.url) { it.bytes() }
    }

    /** Streams the file into [out]. Returns the number of bytes written. */
    public suspend fun downloadTo(id: String, out: OutputStream): Long {
        val link = downloadLink(id)
        return fetchUnauthenticated(link.url) { body -> body.byteStream().use { it.copyTo(out) } }
    }

    /** Step 1: `POST /files/uploads`. Max 50 MB per file. */
    public suspend fun createUpload(
        workspaceId: String,
        name: String,
        size: Long,
        type: String? = null,
        folderId: String? = null,
    ): NemiUploadTicket = client.send(
        "POST",
        "files/uploads",
        CreateUploadBody.serializer(),
        CreateUploadBody(workspaceId, folderId, name, type, size),
        NemiUploadTicket.serializer(),
    )

    /** Step 3: `POST /files/uploads/{upload_id}/complete`. */
    public suspend fun completeUpload(uploadId: String): NemiFile =
        client.send("POST", "files/uploads/${seg(uploadId)}/complete", JsonObject.serializer(), JsonObject(emptyMap()), NemiFile.serializer())

    /** Uploads [bytes] in the three steps (create, PUT, complete) and returns the new file. */
    public suspend fun upload(
        workspaceId: String,
        name: String,
        bytes: ByteArray,
        type: String? = null,
        folderId: String? = null,
    ): NemiFile = upload(workspaceId, name, bytes.size.toLong(), type, folderId, bytes.toRequestBody(type?.toMediaTypeOrNull()))

    /** Uploads a local [file] in the three steps and returns the new file. */
    public suspend fun upload(
        workspaceId: String,
        file: File,
        type: String? = null,
        folderId: String? = null,
        name: String = file.name,
    ): NemiFile = upload(workspaceId, name, file.length(), type, folderId, file.asRequestBody(type?.toMediaTypeOrNull()))

    /** Uploads any [body] of [size] bytes in the three steps. */
    public suspend fun upload(
        workspaceId: String,
        name: String,
        size: Long,
        type: String?,
        folderId: String?,
        body: RequestBody,
    ): NemiFile {
        val ticket = createUpload(workspaceId, name, size, type, folderId)
        if (ticket.maxSize > 0 && size > ticket.maxSize) {
            throw NemiApiException(413, NemiApiException.TOO_LARGE, "The file is larger than ${ticket.maxSize} bytes")
        }
        // Presigned: send exactly the headers the server asked for, and never the bearer token.
        val put = Request.Builder().url(ticket.uploadUrl).method(ticket.method.uppercase(), body)
            .apply { ticket.headers.forEach { (key, value) -> header(key, value) } }
            .build()
        client.httpClient.newCall(put).await().use { response ->
            if (!response.isSuccessful) {
                throw NemiApiException(response.code, "upload_failed", "Uploading the bytes failed (HTTP ${response.code})")
            }
        }
        return completeUpload(ticket.uploadId)
    }

    /**
     * `PATCH /files/{id}`: rename and/or move. Pass [ROOT_FOLDER] as [folderId] to move to
     * the top level. Returns the updated file.
     */
    public suspend fun update(id: String, name: String? = null, folderId: String? = null): NemiFile {
        require(name != null || folderId != null) { "Pass a name, a folderId or both" }
        return client.send("PATCH", "files/${seg(id)}", UpdateFileBody.serializer(), UpdateFileBody(name, folderId), NemiFile.serializer())
    }

    public companion object {
        /** `folder_id` value for the top level of a workspace. */
        public const val ROOT_FOLDER: String = "root"
    }

    /** `DELETE /files/{id}`: moves to trash where the plan has one. */
    public suspend fun delete(id: String) {
        client.delete("files/${seg(id)}")
    }

    private suspend fun <T> fetchUnauthenticated(url: String, read: (okhttp3.ResponseBody) -> T): T {
        val request = Request.Builder().url(url).build()
        return client.httpClient.newCall(request).await().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) {
                throw NemiApiException(response.code, NemiApiException.codeForStatus(response.code), "Download failed (HTTP ${response.code})")
            }
            read(body)
        }
    }
}

/** Folders (`files.write`). */
public class FoldersApi internal constructor(private val client: NemiClient) {
    /** `POST /folders`. Leave [parentId] null for the top level. */
    public suspend fun create(workspaceId: String, name: String, parentId: String? = null): NemiFolder =
        client.send(
            "POST",
            "folders",
            CreateFolderBody.serializer(),
            CreateFolderBody(workspaceId, name, parentId),
            NemiFolder.serializer(),
        )
}

/** Gallery (`gallery.read`, `gallery.write`). Image URLs expire after 15 minutes. */
public class GalleryApi internal constructor(private val client: NemiClient) {
    /** One page of `GET /gallery/photos`, newest first. [limit] max 100. */
    public suspend fun photosPage(
        workspaceId: String? = null,
        albumId: String? = null,
        favorites: Boolean? = null,
        cursor: String? = null,
        limit: Int? = null,
    ): NemiPhotoPage = client.get(
        "gallery/photos",
        NemiPhotoPage.serializer(),
        listOf(
            "workspace_id" to workspaceId,
            "album_id" to albumId,
            "favorites" to favorites,
            "cursor" to cursor,
            "limit" to limit?.coerceIn(1, 100),
        ),
    )

    /** Every photo, newest first, fetching pages of [pageSize] lazily as the flow is collected. */
    public fun photos(
        workspaceId: String? = null,
        albumId: String? = null,
        favorites: Boolean? = null,
        pageSize: Int = 100,
    ): Flow<NemiPhoto> = flow {
        var cursor: String? = null
        val seen = HashSet<String>()
        do {
            val page = photosPage(workspaceId, albumId, favorites, cursor, pageSize)
            page.photos.forEach { emit(it) }
            cursor = page.nextCursor?.takeIf { seen.add(it) }
        } while (cursor != null)
    }

    /** `GET /gallery/photos/{id}`, including the full size [NemiPhoto.url]. */
    public suspend fun photo(id: String): NemiPhoto = client.get("gallery/photos/${seg(id)}", NemiPhoto.serializer())

    /** `POST /gallery/photos` (multipart). JPEG, PNG, HEIC, WebP or AVIF, up to 50 MB. Re-encoded by Nemi. */
    public suspend fun upload(workspaceId: String, fileName: String, bytes: ByteArray, mimeType: String): NemiPhoto =
        upload(workspaceId, fileName, bytes.toRequestBody(mimeType.toMediaTypeOrNull()))

    /** Uploads a local image [file]. */
    public suspend fun upload(workspaceId: String, file: File, mimeType: String): NemiPhoto =
        upload(workspaceId, file.name, file.asRequestBody(mimeType.toMediaTypeOrNull()))

    /** Uploads any image [body]. */
    public suspend fun upload(workspaceId: String, fileName: String, body: RequestBody): NemiPhoto {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("workspace_id", workspaceId)
            .addFormDataPart("file", fileName, body)
            .build()
        return client.decode(NemiPhoto.serializer(), client.execute("POST", "gallery/photos", emptyList(), multipart))
    }

    /** `PATCH /gallery/photos/{id}`. Returns the updated photo. */
    public suspend fun setFavorite(id: String, favorite: Boolean): NemiPhoto =
        client.send("PATCH", "gallery/photos/${seg(id)}", FavoriteBody.serializer(), FavoriteBody(favorite), NemiPhoto.serializer())

    /** `GET /gallery/albums`. */
    public suspend fun albums(workspaceId: String? = null): List<NemiAlbum> =
        client.get("gallery/albums", AlbumsResponse.serializer(), listOf("workspace_id" to workspaceId)).albums

    /** `POST /gallery/albums`. Without [workspaceId], the person's first writable library. */
    public suspend fun createAlbum(workspaceId: String?, name: String): NemiAlbum =
        client.send("POST", "gallery/albums", CreateAlbumBody.serializer(), CreateAlbumBody(workspaceId, name), NemiAlbum.serializer())

    /** `POST /gallery/albums/{id}/photos`. Photos from another library are skipped. */
    public suspend fun addToAlbum(albumId: String, photoIds: List<String>): NemiAlbumAddResult =
        client.send(
            "POST",
            "gallery/albums/${seg(albumId)}/photos",
            AddPhotosBody.serializer(),
            AddPhotosBody(photoIds),
            NemiAlbumAddResult.serializer(),
        )
}

/** Docs as Markdown (`docs.read`, `docs.write`). */
public class DocsApi internal constructor(private val client: NemiClient) {
    /** `GET /docs`: summaries (with [NemiDoc.preview], without markdown). Without [workspaceId], every readable workspace. */
    public suspend fun list(workspaceId: String? = null, limit: Int? = null): List<NemiDoc> =
        client.list("docs", "docs", NemiDoc.serializer(), listOf("workspace_id" to workspaceId, "limit" to limit))

    public suspend fun get(id: String): NemiDoc = client.get("docs/${seg(id)}", NemiDoc.serializer())

    public suspend fun create(title: String, markdown: String? = null, workspaceId: String? = null): NemiDoc =
        client.send("POST", "docs", CreateDocBody.serializer(), CreateDocBody(workspaceId, title, markdown), NemiDoc.serializer())

    /** `PATCH /docs/{id}`. [mode] decides whether [markdown] replaces the body or is appended. */
    public suspend fun update(
        id: String,
        title: String? = null,
        markdown: String? = null,
        mode: DocUpdateMode? = null,
    ): NemiDoc = client.send(
        "PATCH",
        "docs/${seg(id)}",
        UpdateDocBody.serializer(),
        UpdateDocBody(title, markdown, mode),
        NemiDoc.serializer(),
    )

    public suspend fun delete(id: String) {
        client.delete("docs/${seg(id)}")
    }
}

/** Sheets as cells (`sheets.read`, `sheets.write`). */
public class SheetsApi internal constructor(private val client: NemiClient) {
    /** `GET /sheets`: summaries without cells. */
    public suspend fun list(workspaceId: String? = null, limit: Int? = null): List<NemiSheet> =
        client.list("sheets", "sheets", NemiSheet.serializer(), listOf("workspace_id" to workspaceId, "limit" to limit))

    public suspend fun get(id: String): NemiSheet = client.get("sheets/${seg(id)}", NemiSheet.serializer())

    /** `POST /sheets`, optionally with initial [cells] (String, Number, Boolean, null or JsonElement values). */
    public suspend fun create(title: String, workspaceId: String? = null, cells: Map<String, Any?>? = null): NemiSheet =
        client.send(
            "POST",
            "sheets",
            CreateSheetBody.serializer(),
            CreateSheetBody(workspaceId, title, cells?.let(::toJsonCells)),
            NemiSheet.serializer(),
        )

    /** `PATCH /sheets/{id}/cells` with JSON values. */
    public suspend fun updateCells(id: String, cells: Map<String, JsonElement>): NemiCellsUpdate =
        client.send("PATCH", "sheets/${seg(id)}/cells", CellsBody.serializer(), CellsBody(cells), NemiCellsUpdate.serializer())

    /** Convenience overload: values may be String, Number, Boolean or null. */
    @JvmName("updateCellValues")
    public suspend fun updateCells(id: String, cells: Map<String, Any?>): NemiCellsUpdate =
        updateCells(id, toJsonCells(cells))

    private fun toJsonCells(cells: Map<String, Any?>): Map<String, JsonElement> =
        cells.mapValues { (ref, value) -> cellValue(ref, value) }

    public suspend fun delete(id: String) {
        client.delete("sheets/${seg(id)}")
    }

    private fun cellValue(ref: String, value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        else -> throw IllegalArgumentException("Cell $ref: unsupported value type ${value::class.java.name}")
    }
}

/** Forms, their questions and their responses (`forms.read`, `forms.write`). */
public class FormsApi internal constructor(private val client: NemiClient) {
    /** `GET /forms`: summaries. */
    public suspend fun list(workspaceId: String? = null, limit: Int? = null): List<NemiForm> =
        client.list("forms", "forms", NemiForm.serializer(), listOf("workspace_id" to workspaceId, "limit" to limit))

    public suspend fun get(id: String): NemiForm {
        val raw = client.getObject("forms/${seg(id)}")
        return NemiJson.decodeFromJsonElement(NemiForm.serializer(), raw).copy(raw = raw)
    }

    /** `GET /forms/{id}/responses`: one page of responses (each as JSON) and the total count. */
    public suspend fun responses(id: String, limit: Int? = null, offset: Int? = null): NemiFormResponses =
        client.get("forms/${seg(id)}/responses", NemiFormResponses.serializer(), listOf("limit" to limit, "offset" to offset))

    /** `POST /forms`. [fields] are the questions, in the format the Nemi docs describe. */
    public suspend fun create(
        title: String,
        description: String? = null,
        fields: JsonArray? = null,
        workspaceId: String? = null,
    ): NemiForm {
        val body = client.jsonBody(CreateFormBody.serializer(), CreateFormBody(workspaceId, title, description, fields))
        val raw = NemiJson.parseToJsonElement(client.execute("POST", "forms", emptyList(), body)).jsonObject
        return NemiJson.decodeFromJsonElement(NemiForm.serializer(), raw).copy(raw = raw)
    }

    public suspend fun delete(id: String) {
        client.delete("forms/${seg(id)}")
    }
}

/** Canvas boards (`canvas.read`, `canvas.write`). */
public class CanvasApi internal constructor(private val client: NemiClient) {
    /** `GET /canvas`: summaries (`{ canvases: [...] }`). */
    public suspend fun list(workspaceId: String? = null, limit: Int? = null): List<NemiCanvas> =
        client.list("canvas", "canvases", NemiCanvas.serializer(), listOf("workspace_id" to workspaceId, "limit" to limit))

    /** `GET /canvas/{id}`. Set [strokes] to include the freehand strokes in [NemiCanvas.raw]. */
    public suspend fun get(id: String, strokes: Boolean = false): NemiCanvas {
        val raw = client.getObject("canvas/${seg(id)}", listOf("strokes" to if (strokes) true else null))
        return NemiJson.decodeFromJsonElement(NemiCanvas.serializer(), raw).copy(raw = raw)
    }

    /** `POST /canvas`: a new, empty board. Returns its summary. */
    public suspend fun create(workspaceId: String? = null, title: String? = null): NemiCanvas =
        client.send("POST", "canvas", CreateCanvasBody.serializer(), CreateCanvasBody(workspaceId, title), NemiCanvas.serializer())

    /**
     * `PATCH /canvas/{id}`. [elements] REPLACES every element on the board: read the board
     * first, change the array and send it back whole. Returns the board.
     */
    public suspend fun update(id: String, title: String? = null, elements: JsonArray? = null): NemiCanvas {
        require(title != null || elements != null) { "Pass a title, elements or both" }
        val body = client.jsonBody(UpdateCanvasBody.serializer(), UpdateCanvasBody(title, elements))
        val raw = NemiJson.parseToJsonElement(client.execute("PATCH", "canvas/${seg(id)}", emptyList(), body)).jsonObject
        return NemiJson.decodeFromJsonElement(NemiCanvas.serializer(), raw).copy(raw = raw)
    }

    /** `DELETE /canvas/{id}`. Final. */
    public suspend fun delete(id: String) {
        client.delete("canvas/${seg(id)}")
    }
}

/** Calendars made in Nemi only (`calendar.read`, `calendar.write`). Never synced Google or Outlook events. */
public class CalendarApi internal constructor(private val client: NemiClient) {
    /** `GET /calendar/calendars`: the person's Nemi calendars. */
    public suspend fun calendars(): List<NemiCalendar> =
        client.list("calendar/calendars", "calendars", NemiCalendar.serializer())

    /** `GET /calendar/events` between two ISO 8601 instants, optionally in one calendar. */
    public suspend fun events(start: String, end: String, calendarId: String? = null): List<NemiCalendarEvent> =
        client.list(
            "calendar/events",
            "events",
            NemiCalendarEvent.serializer(),
            listOf("start" to start, "end" to end, "calendar_id" to calendarId),
        )

    public suspend fun create(event: NewCalendarEvent): NemiCalendarEvent =
        client.send("POST", "calendar/events", NewCalendarEvent.serializer(), event, NemiCalendarEvent.serializer())

    /** `PATCH /calendar/events/{id}`. Returns the updated event. */
    public suspend fun update(id: String, update: CalendarEventUpdate): NemiCalendarEvent =
        client.send("PATCH", "calendar/events/${seg(id)}", CalendarEventUpdate.serializer(), update, NemiCalendarEvent.serializer())

    public suspend fun delete(id: String) {
        client.delete("calendar/events/${seg(id)}")
    }
}

/** Ids are opaque: never let one change the path. */
private fun seg(id: String): String {
    require(id.isNotEmpty() && '/' !in id && id != "." && id != "..") { "Invalid id" }
    return id
}
