# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0] - 2026-10-14

### Added

- `nemi-core` (Kotlin/JVM, usable on Android and on servers):
  - `NemiOAuthClient`: PKCE S256, authorize URL builder, redirect parsing with state
    and `iss` checks, code exchange, refresh with rotation, revoke, userinfo, discovery.
  - `IdTokenVerifier`: ES256 signature check with `java.security` (JWK to `ECPublicKey`,
    JOSE to DER), `iss`, `aud`, `azp`, `exp`, `iat`, `nonce` and `at_hash`, JWKS caching
    with one refetch for an unknown `kid`.
  - `NemiClient` for REST API v1 with suspend functions: `me`, `workspaces`, `files`
    (list, search, get, download, three step upload with the presigned headers, update,
    delete), `folders.create`, `gallery` (paging `Flow`, photo, multipart upload, albums,
    favourites), `docs` (replace or append), `sheets`, `forms` (paged responses),
    `canvas` (read, create, update, delete) and `calendar` (calendars and events, with an
    optional calendar on create).
  - `NemiUser.preferredUsername` from `preferred_username`; sheet cells as
    `NemiCell(value, display)`; `forms.create` sends `fields`.
  - `NemiPrompt.CONSENT` and `NemiPrompt.NONE`, the only `prompt` values Nemi accepts.
  - Models match the v1 response shapes, including list wrappers, photo color and albums,
    album covers and 204 deletes.
  - Typed errors `NemiApiException` (status, code, requiredScope) and `NemiOAuthException`.
  - Automatic retry of 429 responses honoring `Retry-After` (at most 2 retries).
  - `RefreshingTokenProvider` with single flight refresh and one retry after a 401.
- `nemi-android` (minSdk 23):
  - `NemiAuth`, the `SignInWithNemi` activity result contract and a manifest merged
    `NemiRedirectActivity` configured with the `nemiRedirectScheme` placeholder.
  - Custom Tabs, a distinct cancelled result, and sign in state that survives process death.
  - `EncryptedTokenStore` backed by an Android Keystore AES-256-GCM key.
  - Jetpack Compose `SignInWithNemiButton` and `rememberSignInWithNemi`.

[0.1.0]: https://github.com/GuusLab/nemi-android/releases/tag/0.1.0
