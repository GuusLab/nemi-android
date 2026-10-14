package com.guuslab.nemi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Times are ISO 8601 UTC strings, exactly as the API sends them. Parse them with
// java.time (API 26+, or core library desugaring) or kotlinx-datetime if you need to.

/** `GET /me`. [id] is the pairwise subject. */
@Serializable
public data class NemiUser(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("username") val username: String? = null,
    @SerialName("picture") val picture: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null,
)

@Serializable
public data class NemiWorkspace(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("role") val role: String,
)

@Serializable
public data class NemiFile(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("size") val size: Long = 0,
    @SerialName("type") val type: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
public data class NemiFolder(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
)

/** `GET /files`: the folders and files in one folder (or the top level). */
@Serializable
public data class NemiFileListing(
    @SerialName("folders") val folders: List<NemiFolder> = emptyList(),
    @SerialName("files") val files: List<NemiFile> = emptyList(),
)

/** A short-lived link that works without a token. */
@Serializable
public data class NemiDownloadLink(
    @SerialName("url") val url: String,
    @SerialName("expires_at") val expiresAt: String,
)

/**
 * Step 1 of an upload: PUT the raw bytes to [uploadUrl] with [headers] and WITHOUT the
 * bearer token, then complete it.
 */
@Serializable
public data class NemiUploadTicket(
    @SerialName("upload_id") val uploadId: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("method") val method: String = "PUT",
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),
    /** Largest accepted size in bytes; 0 when the server did not say. */
    @SerialName("max_size") val maxSize: Long = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
public data class NemiPhoto(
    @SerialName("id") val id: String,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("name") val name: String? = null,
    @SerialName("width") val width: Int = 0,
    @SerialName("height") val height: Int = 0,
    @SerialName("size") val size: Long = 0,
    @SerialName("captured_at") val capturedAt: String? = null,
    @SerialName("favorite") val favorite: Boolean = false,
    /** Dominant color, for placeholders while the image loads. */
    @SerialName("color") val color: String? = null,
    /** Expires after 15 minutes. Fetch again rather than storing it. */
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    /** Full size, only on `GET /gallery/photos/{id}`. Expires after 15 minutes. */
    @SerialName("url") val url: String? = null,
    /** The albums holding this photo, only on `GET /gallery/photos/{id}`. */
    @SerialName("albums") val albums: List<NemiAlbumRef>? = null,
)

@Serializable
public data class NemiAlbumRef(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
)

@Serializable
public data class NemiPhotoPage(
    @SerialName("photos") val photos: List<NemiPhoto> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
public data class NemiAlbum(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("count") val count: Int = 0,
    /** Expires after 15 minutes. */
    @SerialName("cover_url") val coverUrl: String? = null,
)

/** Answer of `POST /gallery/albums/{id}/photos`. */
@Serializable
public data class NemiAlbumAddResult(
    @SerialName("album_id") val albumId: String,
    @SerialName("added") val added: Int = 0,
    /** Photos that were not in the album's library. */
    @SerialName("skipped") val skipped: Int = 0,
)

/** A doc. Lists return summaries: [preview] but no [markdown]. */
@Serializable
public data class NemiDoc(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("preview") val preview: String? = null,
    @SerialName("markdown") val markdown: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/**
 * A sheet (its first tab). [cells] maps A1 references to JSON values (string, number,
 * boolean or null). Lists return summaries without [rows], [cols] and [cells].
 */
@Serializable
public data class NemiSheet(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("rows") val rows: Int = 0,
    @SerialName("cols") val cols: Int = 0,
    @SerialName("cells") val cells: Map<String, JsonElement> = emptyMap(),
)

/** Answer of `PATCH /sheets/{id}/cells`. */
@Serializable
public data class NemiCellsUpdate(
    @SerialName("id") val id: String,
    @SerialName("updated") val updated: Int = 0,
)

/**
 * A form. Lists return the summary fields. `forms.get` and `forms.create` also fill [raw]
 * with the whole object, including the questions.
 */
@Serializable
public data class NemiForm(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("published") val published: Boolean = false,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

/** Answer of `GET /forms/{id}/responses`: one page of [responses] out of [total]. */
@Serializable
public data class NemiFormResponses(
    @SerialName("form_id") val formId: String,
    @SerialName("total") val total: Int = 0,
    @SerialName("responses") val responses: List<JsonObject> = emptyList(),
)

/** A canvas board. `canvas.get` fills [raw] with the whole board (elements, strokes and so on). */
@Serializable
public data class NemiCanvas(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

/** A calendar made in Nemi. */
@Serializable
public data class NemiCalendar(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String = "",
    @SerialName("color") val color: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("primary") val primary: Boolean = false,
)

/** An event in a calendar made in Nemi. [start] and [end] are ISO 8601. */
@Serializable
public data class NemiCalendarEvent(
    @SerialName("id") val id: String,
    @SerialName("calendar_id") val calendarId: String? = null,
    @SerialName("title") val title: String = "",
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("location") val location: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    /** RFC 5545 recurrence rule, for example `FREQ=WEEKLY;BYDAY=MO`. */
    @SerialName("rrule") val rrule: String? = null,
)

/** Body for `POST /calendar/events`. Without [calendarId] the event goes to the primary Nemi calendar. */
@Serializable
public data class NewCalendarEvent(
    @SerialName("title") val title: String,
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
    @SerialName("calendar_id") val calendarId: String? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("rrule") val rrule: String? = null,
)

/** Body for `PATCH /calendar/events/{id}`. Null fields are left out and stay unchanged. */
@Serializable
public data class CalendarEventUpdate(
    @SerialName("calendar_id") val calendarId: String? = null,
    @SerialName("title") val title: String? = null,
    @SerialName("start") val start: String? = null,
    @SerialName("end") val end: String? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("timezone") val timezone: String? = null,
    @SerialName("rrule") val rrule: String? = null,
)

/** How `docs.update` applies new Markdown. */
@Serializable
public enum class DocUpdateMode {
    @SerialName("replace")
    Replace,

    @SerialName("append")
    Append,
}

// Request bodies.

@Serializable
internal data class WorkspacesResponse(@SerialName("workspaces") val workspaces: List<NemiWorkspace> = emptyList())

@Serializable
internal data class FilesResponse(@SerialName("files") val files: List<NemiFile> = emptyList())

@Serializable
internal data class AlbumsResponse(@SerialName("albums") val albums: List<NemiAlbum> = emptyList())

@Serializable
internal data class CreateUploadBody(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("folder_id") val folderId: String?,
    @SerialName("name") val name: String,
    @SerialName("type") val type: String?,
    @SerialName("size") val size: Long,
)

@Serializable
internal data class UpdateFileBody(
    @SerialName("name") val name: String?,
    @SerialName("folder_id") val folderId: String?,
)

@Serializable
internal data class CreateFolderBody(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("name") val name: String,
    @SerialName("parent_id") val parentId: String?,
)

@Serializable
internal data class CreateAlbumBody(
    @SerialName("workspace_id") val workspaceId: String?,
    @SerialName("name") val name: String,
)

@Serializable
internal data class AddPhotosBody(@SerialName("photo_ids") val photoIds: List<String>)

@Serializable
internal data class FavoriteBody(@SerialName("favorite") val favorite: Boolean)

@Serializable
internal data class CreateDocBody(
    @SerialName("workspace_id") val workspaceId: String?,
    @SerialName("title") val title: String,
    @SerialName("markdown") val markdown: String?,
)

@Serializable
internal data class UpdateDocBody(
    @SerialName("title") val title: String?,
    @SerialName("markdown") val markdown: String?,
    @SerialName("mode") val mode: DocUpdateMode?,
)

@Serializable
internal data class CreateSheetBody(
    @SerialName("workspace_id") val workspaceId: String?,
    @SerialName("title") val title: String,
    @SerialName("cells") val cells: Map<String, JsonElement>?,
)

@Serializable
internal data class CellsBody(@SerialName("cells") val cells: Map<String, JsonElement>)

@Serializable
internal data class CreateFormBody(
    @SerialName("workspace_id") val workspaceId: String?,
    @SerialName("title") val title: String,
    @SerialName("description") val description: String?,
    @SerialName("items") val items: JsonArray?,
)

@Serializable
internal data class ApiErrorEnvelope(@SerialName("error") val error: ApiErrorBody? = null)

@Serializable
internal data class ApiErrorBody(
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("required_scope") val requiredScope: String? = null,
)
