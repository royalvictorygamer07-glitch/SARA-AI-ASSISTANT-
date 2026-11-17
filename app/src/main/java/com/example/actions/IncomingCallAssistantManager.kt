package com.example.actions

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

data class IncomingCallCapabilityReport(
    val androidVersionLabel: String,
    val supportsAnswerPhoneCallsApi: Boolean,
    val isDefaultDialerApp: Boolean,
    val hasPhoneStatePermission: Boolean,
    val hasContactsPermission: Boolean,
    val summaryStatus: String,
    val platformLimitationNotice: String
)

/**
 * Architectural bridge for the future Incoming Call Assistant.
 * Accurately reports Android Telecom/RoleManager/Play Store requirements
 * instead of pretending a normal third-party app can silently answer calls.
 */
class IncomingCallAssistantManager(private val context: Context) {

    fun inspectCapabilities(): IncomingCallCapabilityReport {
        val sdkInt = Build.VERSION.SDK_INT
        val supportsAnswerApi = sdkInt >= Build.VERSION_CODES.O

        val isDefaultDialer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isRoleDialerHeldQ()
        } else {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecom?.defaultDialerPackage == context.packageName
        }

        val hasPhoneState = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasContacts = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val summary = when {
            isDefaultDialer && hasPhoneState && hasContacts ->
                "Device role & permissions ready for Telecom InCallService integration."
            !supportsAnswerApi ->
                "Android 8.0+ (API 26) required for Telecom acceptRingingCall()."
            else ->
                "Standard third-party mode: Default Phone App role (ROLE_DIALER) + ANSWER_PHONE_CALLS required for auto-answering."
        }

        val notice = buildString {
            append("On Android, a regular third-party app cannot silently answer incoming phone calls ")
            append("or override the system dialer without: (1) holding RoleManager.ROLE_DIALER / InCallService ")
            append("or CallScreeningService, (2) user-granted READ_PHONE_STATE, READ_CALL_LOG, and ANSWER_PHONE_CALLS ")
            append("permissions, and (3) Google Play Default Phone Handler policy approval.")
        }

        return IncomingCallCapabilityReport(
            androidVersionLabel = "Android ${Build.VERSION.RELEASE} (API $sdkInt)",
            supportsAnswerPhoneCallsApi = supportsAnswerApi,
            isDefaultDialerApp = isDefaultDialer,
            hasPhoneStatePermission = hasPhoneState,
            hasContactsPermission = hasContacts,
            summaryStatus = summary,
            platformLimitationNotice = notice
        )
    }

    fun evaluateVoiceAnswerDecision(affirmative: Boolean): String {
        val report = inspectCapabilities()
        return if (!affirmative) {
            "Call declined in preview. Standard OS ringtone continues."
        } else if (report.isDefaultDialerApp && report.supportsAnswerPhoneCallsApi) {
            "Telecom InCallService eligible: acceptRingingCall() would execute now."
        } else {
            "Voice confirmation received ('Ha'). Platform constraint: SARA is not the Default Phone App (ROLE_DIALER), so Android OS blocks automatic call answering for normal third-party apps."
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun isRoleDialerHeldQ(): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    }
}
