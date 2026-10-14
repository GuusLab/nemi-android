package com.guuslab.nemi.android

import android.annotation.SuppressLint
import android.content.Context
import com.guuslab.nemi.NemiTokenStore
import com.guuslab.nemi.NemiTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stores tokens in private SharedPreferences, encrypted with AES-256-GCM under a key held
 * by the Android Keystore.
 *
 * Keystore keys are not part of backups, so a restored copy cannot be decrypted: it is
 * dropped and the person signs in again. Exclude the `nemi_tokens` preferences file from
 * Auto Backup to avoid restoring dead data.
 *
 * Writes use commit() on Dispatchers.IO so a rotated refresh token is on disk before the
 * call returns; losing it would revoke the session.
 */
@SuppressLint("ApplySharedPref")
public class EncryptedTokenStore @JvmOverloads constructor(
    context: Context,
    private val name: String = DEFAULT_NAME,
) : NemiTokenStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("com.guuslab.nemi.tokens.$name")
    private val mutex = Mutex()
    private var cached: NemiTokens? = null
    private var loaded = false

    override suspend fun read(): NemiTokens? = mutex.withLock {
        if (!loaded) {
            cached = withContext(Dispatchers.IO) { load() }
            loaded = true
        }
        cached
    }

    override suspend fun write(tokens: NemiTokens): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY, cipher.encrypt(tokens.toJson())).commit()
        }
        cached = tokens
        loaded = true
    }

    override suspend fun clear(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) { prefs.edit().remove(KEY).commit() }
        cached = null
        loaded = true
    }

    private fun load(): NemiTokens? {
        val encrypted = prefs.getString(KEY, null) ?: return null
        val json = cipher.decrypt(encrypted)
        val tokens = json?.let { runCatching { NemiTokens.fromJson(it) }.getOrNull() }
        if (tokens == null) prefs.edit().remove(KEY).commit()
        return tokens
    }

    public companion object {
        public const val DEFAULT_NAME: String = "nemi_tokens"
        private const val KEY = "tokens"
    }
}
