package com.snake.mobilespoofing.core.location

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers whether this app is currently the system's selected mock-location app.
 *
 * That is an AppOps setting the user changes in Developer Options, not a runtime permission, so it
 * can change at any time while the app is backgrounded — re-check it before starting a session and
 * periodically during one rather than trusting a value captured when the screen was rendered.
 *
 * An interface so the runner and the LocationManager engine can be tested on the JVM without a
 * device; [AndroidMockPermissionChecker] is the production binding.
 */
fun interface MockPermissionChecker {
    fun canMockLocation(): Boolean
}

@Singleton
class AndroidMockPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : MockPermissionChecker {

    override fun canMockLocation(): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
