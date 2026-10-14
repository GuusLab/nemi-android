package com.guuslab.nemi.android

import android.app.Activity
import android.os.Bundle

/**
 * Receives `<nemiRedirectScheme>:/...` from the browser and hands it to
 * [NemiAuthorizationActivity], which returns it to the app. Declared in the library
 * manifest; the app only sets the `nemiRedirectScheme` manifest placeholder.
 */
public class NemiRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { startActivity(NemiAuthorizationActivity.redirectIntent(this, it)) }
        finish()
    }
}
