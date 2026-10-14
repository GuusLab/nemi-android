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
    @SerialName("workspace_id") val workspaceId: String,
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

/** Step 1 of an upload: PUT the bytes to [uploadUrl], then complete it. */
@Serializable
public data class NemiUploadTicket(
    @SerialName("upload_id") val uploadId: String,
    @SerialName("upload_url") val uploadUrl: String,
    @SerialName("method") val method: String = "PUT",
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
public data class NemiPhoto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String? = null,
    @SerialName("width") val width: Int = 0,
    @SerialName("height") val height: Int = 0,
    @SerialName("size") val size: Long = 0,
    @SerialName("captured_at") val capturedAt: String? = null,
    @SerialName("favorite") val favorite: Boolean = false,
    /** Expires after 15 minutes. Fetch again rather than storing it. */
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    /** Full size, only on `GET /gallery/photos/{id}`. Expires after 15 minutes. */
    @SerialName("url") val url: String? = null,
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
)

/** A doc. List calls may leave out [markdown]. */
@Serializable
public data class NemiDoc(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("markdown") val markdown: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A sheet. [cells] maps A1 references to JSON values (string, number, boolean or null). */
@Serializable
public data class NemiSheet(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("cells") val cells: Map<String, JsonElement> = emptyMap(),
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A form. [fields] are passed through as JSON; [raw] holds every property as sent. */
@Serializable
public data class NemiForm(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("fields") val fields: JsonArray? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

/** A canvas board. [raw] holds every property as sent (elements and so on). */
@Serializable
public data class NemiCanvas(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @Transient val raw: JsonObject = JsonObject(emptyMap()),
)

/** An event in a calendar made in Nemi. [start] and [end] are ISO 8601. */
@Serializable
public data class NemiCalendarEvent(
    @SerialName("id") val id: String,
    @SerialName("title") val title: String = "",
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
    @SerialName("location") val location: String? = null,
    @SerialName("description") val description: String? = null,
    @SerialName("calendar_id") val calendarId: String? = null,
)

/** Body for `POST /calendar/events`. */
@Serializable
public data class NewCalendarEvent(
    @SerialName("title") val title: String,
    @SerialName("start") val start: String,
    @SerialName("end") val end: String,
    @SerialName("all_day") val allDay: Boolean? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("description") val description: String? = null,
)

/** Body for `PATCH /calendar/events/{id}`. Null fields are left out and stay unchanged. */
@Serializable
public data class CalendarEventUpdate(
    @SerialName("title") val title: String? = null,
    @SerialName("start") val start: String? = null,
    @SerialName("end") val end: String? = null,
    @SerialName("all_day") val allDay: Boolean? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("description") val description: String? = null,
)

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
    @SerialName("workspace_id") val workspaceId: String,
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
)

@Serializable
internal data class CreateSheetBody(
    @SerialName("workspace_id") val workspaceId: String?,
    @SerialName("title") val title: String,
)

@Serializable
internal data class CellsBody(@SerialName("cells") val cells: Map<String, JsonElement>)

@Serializable
internal data class CreateFormBody(
    @SerialName("workspace_id") val workspaceId: String?,
    @SerialName("title") val title: String,
    @SerialName("fields") val fields: JsonArray?,
)

@Serializable
internal data class ApiErrorEnvelope(@SerialName("error") val error: ApiErrorBody? = null)

@Serializable
internal data class ApiErrorBody(
    @SerialName("code") val code: String? = null,
    @SerialName("message") val message: String? = null,
    @SerialName("required_scope") val requiredScope: String? = null,
)
