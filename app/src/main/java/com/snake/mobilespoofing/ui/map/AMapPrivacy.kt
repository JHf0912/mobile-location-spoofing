package com.snake.mobilespoofing.ui.map

import android.content.Context
import com.amap.api.maps.MapsInitializer

/**
 * AMap 10.x refuses to initialise a `MapView` until the host app has shown its privacy policy and
 * recorded the user's consent. Both calls must happen in every process before the map is created;
 * skipping them leaves the map blank.
 *
 * Consent is stored in a small preference file rather than in memory because the requirement
 * survives process restarts.
 */
object AMapPrivacy {

    private const val PREFS_NAME = "amap_privacy"
    private const val KEY_AGREED = "agreed"

    fun isAgreed(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AGREED, false)

    fun agree(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGREED, true)
            .apply()
        // On an ABI without AMap's native library this throws UnsatisfiedLinkError. The map card
        // catches the resulting creation failure and shows a fallback; consent itself is recorded.
        runCatching { applyToSdk(context) }
    }

    /** Idempotent; call before creating a `MapView` when consent was recorded earlier. */
    fun applyToSdk(context: Context) {
        MapsInitializer.updatePrivacyShow(context, true, true)
        MapsInitializer.updatePrivacyAgree(context, true)
    }
}
