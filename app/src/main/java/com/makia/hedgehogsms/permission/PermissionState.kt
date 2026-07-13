package com.makia.hedgehogsms.permission

enum class PermissionStep { EXPLANATION, READ_SMS, READ_PHONE_STATE, RECEIVE_SMS, COMPLETE }

data class PermissionSnapshot(
    val explained: Boolean = false,
    val readSms: Boolean = false,
    val readPhoneState: Boolean = false,
    val receiveSms: Boolean = false,
    val requested: Set<PermissionStep> = emptySet(),
) {
    val canReadInbox: Boolean get() = explained && readSms
    val nextStep: PermissionStep get() = when {
        !explained -> PermissionStep.EXPLANATION
        PermissionStep.READ_SMS !in requested -> PermissionStep.READ_SMS
        PermissionStep.READ_PHONE_STATE !in requested -> PermissionStep.READ_PHONE_STATE
        PermissionStep.RECEIVE_SMS !in requested -> PermissionStep.RECEIVE_SMS
        else -> PermissionStep.COMPLETE
    }
    val phoneStateDegraded: Boolean get() = PermissionStep.READ_PHONE_STATE in requested && !readPhoneState
    val receiveSmsDegraded: Boolean get() = PermissionStep.RECEIVE_SMS in requested && !receiveSms
}

sealed interface PermissionEvent {
    data object ExplanationAccepted : PermissionEvent
    data class Result(val step: PermissionStep, val granted: Boolean) : PermissionEvent
    data class Refreshed(val readSms: Boolean, val readPhoneState: Boolean, val receiveSms: Boolean) : PermissionEvent
}

fun PermissionSnapshot.reduce(event: PermissionEvent): PermissionSnapshot = when (event) {
    PermissionEvent.ExplanationAccepted -> copy(explained = true)
    is PermissionEvent.Result -> copy(
        readSms = if (event.step == PermissionStep.READ_SMS) event.granted else readSms,
        readPhoneState = if (event.step == PermissionStep.READ_PHONE_STATE) event.granted else readPhoneState,
        receiveSms = if (event.step == PermissionStep.RECEIVE_SMS) event.granted else receiveSms,
        requested = requested + event.step,
    )
    is PermissionEvent.Refreshed -> copy(
        readSms = event.readSms,
        readPhoneState = event.readPhoneState,
        receiveSms = event.receiveSms,
    )
}
