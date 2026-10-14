# Nemi for Android and Kotlin

"Sign in with Nemi" and the Nemi REST API for Android apps and Kotlin servers.

| Module | What it is |
| --- | --- |
| `nemi-core` | Pure Kotlin/JVM. OAuth 2.0 + OpenID Connect client (PKCE S256, id_token verification) and `NemiClient` for REST API v1. Works on Android and on servers. |
| `nemi-android` | Android library (minSdk 23). Custom Tabs sign in, `SignInWithNemi` activity result contract, Keystore encrypted token storage and a Jetpack Compose button. Depends on `nemi-core`. |

The SDK follows the Nemi developer platform contract: issuer `https://nemilab.com/oauth`,
API `https://nemilab.com/api/v1`.

## Install

The library is published on [JitPack](https://jitpack.io/#GuusLab/nemi-android).

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    // Both modules:
    implementation("com.github.GuusLab:nemi-android:0.1.0")

    // Or pick one (a server only needs nemi-core):
    // implementation("com.github.GuusLab.nemi-android:nemi-android:0.1.0")
    // implementation("com.github.GuusLab.nemi-android:nemi-core:0.1.0")
}
```

The Maven Central coordinates are `com.guuslab.nemi:nemi-android` and
`com.guuslab.nemi:nemi-core`.

## Set up your app

1. Create an app at [dev.nemilab.com](https://dev.nemilab.com). Pick type **public**
   (mobile apps cannot keep a secret; PKCE protects the flow).
2. Register the redirect URI. Use a private-use scheme based on your application id:

   ```
   com.example.app:/oauth2redirect
   ```

   Redirect URIs are matched exactly, so the value in your code must be identical.
3. Allow the scopes your app will ask for (for example `openid profile email offline_access gallery.read`).
4. While the app is in `development`, only you and up to 25 test users can sign in.

Then tell the SDK which scheme to listen on. `nemi-android` declares
`NemiRedirectActivity` in its manifest; you only fill in the placeholder:

```kotlin
// app/build.gradle.kts
android {
    defaultConfig {
        manifestPlaceholders["nemiRedirectScheme"] = "com.example.app"
    }
}
```

Create one `NemiAuth` for the whole app, for example in your `Application`:

```kotlin
class App : Application() {
    val nemiAuth by lazy {
        NemiAuth(
            context = this,
            config = NemiAuthConfig(
                clientId = "nemi_app_xxxxxxxxxxxxxxxxxxxxxxxx",
                redirectUri = "com.example.app:/oauth2redirect",
                scopes = NemiScope.SIGN_IN + NemiScope.GALLERY_READ,
            ),
        )
    }
}
```

`NemiScope.SIGN_IN` is `openid profile email offline_access`. Without `offline_access`
there is no refresh token and the person has to sign in again after an hour.

## Compose quick start

```kotlin
@Composable
fun SignInScreen(nemiAuth: NemiAuth, onSignedIn: (IdTokenClaims?) -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val signIn = rememberSignInWithNemi(nemiAuth) { result ->
        when (result) {
            is NemiSignInResult.SignedIn -> onSignedIn(result.idToken)
            NemiSignInResult.Cancelled -> Unit // The person closed the browser tab.
            is NemiSignInResult.Failed ->
                error = if (result.isAccessDenied) "You declined access." else result.error.message
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        SignInWithNemiButton(onClick = { signIn(null) }, style = NemiButtonStyle.Auto)
        error?.let { Text(it) }
    }
}
```

`SignInWithNemiButton` is a black pill (`NemiButtonStyle.Black`, the default), a white pill
for dark backgrounds (`White`), or follows the system theme (`Auto`). It is announced to
TalkBack as a button labelled "Sign in with Nemi".

Pass a `NemiSignInRequest` to ask for more scopes later or to force the consent screen:

```kotlin
signIn(NemiSignInRequest(scopes = NemiScope.SIGN_IN + NemiScope.FILES_READ, prompt = "consent"))
```

## Views quick start

```kotlin
class SignInActivity : AppCompatActivity() {
    private val nemiAuth get() = (application as App).nemiAuth

    private val signIn = registerForActivityResult(SignInWithNemi(nemiAuth.config)) { browserResult ->
        lifecycleScope.launch {
            when (val result = nemiAuth.completeSignIn(browserResult)) {
                is NemiSignInResult.SignedIn -> showHome(result.idToken?.name)
                NemiSignInResult.Cancelled -> Unit
                is NemiSignInResult.Failed -> showError(result.error)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)
        findViewById<Button>(R.id.sign_in).setOnClickListener { signIn.launch(null) }
    }
}
```

To use the Compose button in a View layout, put it in a `ComposeView`.

### What happens

1. The contract creates `state`, `nonce` and a PKCE verifier, stores them encrypted, and
   opens the consent screen in a Custom Tab.
2. Nemi redirects to `com.example.app:/oauth2redirect?code=...&state=...&iss=...`.
   `NemiRedirectActivity` catches it and returns it to your activity.
3. `completeSignIn` checks the state and issuer, exchanges the code with the verifier,
   verifies the id_token (ES256 signature, `iss`, `aud`, `exp`, `nonce`, `at_hash`) and
   saves the tokens in `EncryptedTokenStore`.

If Android kills your app while the browser is open, the pending sign in is restored from
disk and the result is delivered to the recreated activity. Closing the tab yields
`NemiSignInResult.Cancelled`; pressing Deny yields `Failed` with `isAccessDenied == true`.

### Calling the API, signing out

```kotlin
val me = nemiAuth.client.me()
val workspaces = nemiAuth.client.workspaces()

nemiAuth.signOut() // Revokes the refresh token and deletes the stored tokens.
```

Access tokens are refreshed automatically a minute before they expire. Refreshes are
single flight, which matters because refresh tokens rotate and presenting a rotated one
revokes the whole session. If the refresh token is no longer valid the stored tokens are
cleared and calls throw `NemiOAuthException` with error `invalid_grant`: show the sign in
button again.

Tokens are encrypted with an Android Keystore key, which is not part of backups. Exclude
the `nemi_tokens` and `nemi_pending_authorization` shared preferences from Auto Backup.

## Gallery example

```kotlin
val nemi = nemiAuth.client
val workspaceId = nemi.workspaces().first().id

// Every photo, newest first. Pages of up to 100 are fetched as you collect.
nemi.gallery.photos(workspaceId = workspaceId)
    .take(300)
    .collect { photo -> println("${photo.name} ${photo.width}x${photo.height}") }

// Favourites only, one page at a time.
val page = nemi.gallery.photosPage(workspaceId = workspaceId, favorites = true, limit = 50)
val next = page.nextCursor?.let { nemi.gallery.photosPage(workspaceId, favorites = true, cursor = it) }

// Full size URL (valid for 15 minutes: fetch again rather than storing it).
val full = nemi.gallery.photo(page.photos.first().id).url

// Upload (gallery.write). JPEG, PNG, HEIC, WebP or AVIF, up to 50 MB.
val uploaded = nemi.gallery.upload(workspaceId, file = File(path), mimeType = "image/jpeg")
val album = nemi.gallery.createAlbum(workspaceId, "Summer")
val added = nemi.gallery.addToAlbum(album.id, listOf(uploaded.id))   // { album_id, added, skipped }
nemi.gallery.setFavorite(uploaded.id, true)                           // returns the updated photo
```

Every `Photo` carries `workspaceId`, `color` (a placeholder while the thumbnail loads) and
`thumbnailUrl`; `gallery.photo(id)` adds the full size `url` and the `albums` it is in.

## Files, docs and calendar examples

```kotlin
// Upload: the SDK creates the upload, PUTs the bytes to the presigned URL with the
// headers the server returned (never the bearer token) and completes it.
val file = nemi.files.upload(workspaceId, File(path), type = "application/pdf")
nemi.files.update(file.id, folderId = FilesApi.ROOT_FOLDER)            // move to the top level

val folder = nemi.folders.create(workspaceId, "Taxes")               // { id, name, workspace_id, parent_id }

val doc = nemi.docs.create("Trip plan", markdown = "# Day 1")
nemi.docs.update(doc.id, markdown = "\n# Day 2", mode = DocUpdateMode.Append)

val sheet = nemi.sheets.create("Budget", cells = mapOf("A1" to "Rent", "B1" to 950))
nemi.sheets.updateCells(sheet.id, mapOf("A2" to "Food", "B2" to 300))   // { id, updated }

val page = nemi.forms.responses(formId, limit = 50, offset = 0)        // { form_id, total, responses }

val primary = nemi.calendar.calendars().first { it.primary }
nemi.calendar.create(
    NewCalendarEvent(
        title = "Dinner",
        start = "2026-11-01T18:00:00Z",
        end = "2026-11-01T20:00:00Z",
        // calendarId is optional: without it the event goes to the primary Nemi calendar.
        calendarId = primary.id,
        timezone = "Europe/Amsterdam",
    ),
)
```

## Server-side Kotlin

`nemi-core` has no Android dependencies. A confidential app (with a server) uses the
same flow with its client secret:

```kotlin
val oauth = NemiOAuthClient(
    clientId = System.getenv("NEMI_CLIENT_ID"),
    clientSecret = System.getenv("NEMI_CLIENT_SECRET"),
    redirectUri = "https://example.com/auth/nemi/callback",
)

// GET /auth/nemi: start.
val request = oauth.createAuthorizationRequest(NemiScope.SIGN_IN + NemiScope.DOCS_READ)
session["nemi"] = request.toJson()          // Server side session, never a plain cookie.
redirect(request.url)

// GET /auth/nemi/callback: finish.
val pending = AuthorizationRequest.fromJson(session.remove("nemi")!!)
val credentials = oauth.handleCallback(fullCallbackUrl, pending)
val subject = credentials.idToken?.subject   // Pairwise: stable for your developer account.
saveTokens(subject, credentials.tokens)
```

Then call the API with a token provider that refreshes and persists rotated tokens:

```kotlin
val store: NemiTokenStore = MyDatabaseTokenStore(userId)   // implement read, write, clear
val nemi = NemiClient(RefreshingTokenProvider(oauth, store))
val docs = nemi.docs.list()
```

Or, for a script that already holds an access token: `NemiClient("nemi_oat_...")`.

## API overview

| Namespace | Calls | Scope |
| --- | --- | --- |
| `client.me()`, `client.workspaces()` | the person, their workspaces | any, any `*.read` |
| `client.files` | `list`, `search`, `get`, `downloadLink`, `download`, `downloadTo`, `upload` (create, PUT, complete), `update`, `delete` | `files.read`, `files.write` |
| `client.folders` | `create` | `files.write` |
| `client.gallery` | `photos` (Flow), `photosPage`, `photo`, `upload`, `setFavorite`, `albums`, `createAlbum`, `addToAlbum` | `gallery.read`, `gallery.write` |
| `client.docs` | `list` (summaries with `preview`), `get`, `create`, `update` (replace or append), `delete` | `docs.read`, `docs.write` |
| `client.sheets` | `list` (summaries), `get` (first tab with `rows`, `cols`, `cells`), `create`, `updateCells`, `delete` | `sheets.read`, `sheets.write` |
| `client.forms` | `list` (summaries with `published`), `get`, `responses` (paged, with `total`), `create`, `delete` | `forms.read`, `forms.write` |
| `client.canvas` | `list`, `get` (optionally with strokes) | `canvas.read` |
| `client.calendar` | `calendars`, `events`, `create`, `update`, `delete` | `calendar.read`, `calendar.write` |

Lists without a `workspaceId` span every workspace the person can read; creates without
one go to the first workspace they can write in. Updates return the updated object,
deletes answer 204. Form questions and canvas boards are free form: read them from
`NemiForm.raw` and `NemiCanvas.raw`. Times are ISO 8601 UTC strings as the API sends them.
Ids are opaque strings.

### Errors

- `NemiApiException`: `status`, `code` (`invalid_request`, `invalid_token`,
  `insufficient_scope`, `forbidden`, `not_found`, `conflict`, `too_large`, `rate_limited`,
  `server_error`), `requiredScope` for `insufficient_scope`, `retryAfterSeconds` for 429.
- `NemiOAuthException`: `error` (RFC 6749 codes such as `invalid_grant` and
  `access_denied`, plus `invalid_state`, `invalid_issuer`, `invalid_id_token`,
  `not_signed_in`), `errorDescription`, `status`.

A 429 is retried at most twice after waiting for `Retry-After`. A 401 triggers one token
refresh and one retry.

```kotlin
try {
    nemi.files.list(workspaceId)
} catch (e: NemiApiException) {
    if (e.code == NemiApiException.INSUFFICIENT_SCOPE) {
        signIn(NemiSignInRequest(scopes = NemiScope.SIGN_IN + e.requiredScope!!))
    }
}
```

## Building

```bash
./gradlew :nemi-core:test                       # JVM tests, no Android SDK needed to run them
./gradlew build                                 # everything (needs an Android SDK with platform 35)
./gradlew -Pnemi.skipAndroid=true build         # core only, on a machine without an Android SDK
./gradlew -Pnemi.compileSdk=34 build            # build Android against an older installed platform
./gradlew publishToMavenLocal
```

Signed releases for Maven Central: pass `-PsigningInMemoryKey=...` and
`-PsigningInMemoryKeyPassword=...`; POM metadata, sources and javadoc jars are already
configured.

## License

MIT, see [LICENSE](LICENSE).
