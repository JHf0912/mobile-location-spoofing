package com.snake.mobilespoofing

import android.content.Context
import androidx.annotation.StringRes
import com.snake.mobilespoofing.core.model.SessionDegradedReason
import com.snake.mobilespoofing.core.model.SessionErrorReason

/**
 * The only place where a [SessionErrorReason] becomes display text.
 *
 * `:core:model` deliberately stays free of localized strings, and `:core:location` only writes
 * reasons. The UI resolves them through [errorReasonRes]/[degradedReasonRes]; the service uses the
 * Context variants for notification text. Extending either reason enum obliges an entry here.
 */
@StringRes
fun errorReasonRes(reason: SessionErrorReason): Int = when (reason) {
    SessionErrorReason.MissingSpec -> R.string.reason_error_missing_spec
    SessionErrorReason.PermissionDenied -> R.string.reason_error_permission_denied
    SessionErrorReason.MockAppNotSelected -> R.string.reason_error_mock_app_not_selected
    SessionErrorReason.EngineStartFailed -> R.string.reason_error_engine_start_failed
    SessionErrorReason.SourceInvalid -> R.string.reason_error_source_invalid
    SessionErrorReason.TooManyConsecutiveFailures -> R.string.reason_error_too_many_consecutive_failures
    SessionErrorReason.ServiceStartFailed -> R.string.reason_error_service_start_failed
    SessionErrorReason.TeardownTimeout -> R.string.reason_error_teardown_timeout
    SessionErrorReason.Unknown -> R.string.reason_error_unknown
}

fun errorReasonText(context: Context, reason: SessionErrorReason): String =
    context.getString(errorReasonRes(reason))

@StringRes
fun degradedReasonRes(reason: SessionDegradedReason): Int = when (reason) {
    SessionDegradedReason.PushRejected -> R.string.reason_degraded_push_rejected
    SessionDegradedReason.PermissionRevoked -> R.string.reason_degraded_permission_revoked
    SessionDegradedReason.ProviderConflict -> R.string.reason_degraded_provider_conflict
    SessionDegradedReason.EngineTimeout -> R.string.reason_degraded_engine_timeout
}

fun degradedReasonText(context: Context, reason: SessionDegradedReason): String =
    context.getString(degradedReasonRes(reason))
