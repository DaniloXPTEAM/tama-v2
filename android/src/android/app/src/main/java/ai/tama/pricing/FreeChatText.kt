package ai.tama.pricing

import com.openminis.app.R

internal fun freeStatusLabel(status: DocumentedFreeChat.Status): Int = when (status) {
    DocumentedFreeChat.Status.FREE -> R.string.nm_free_status_limited
    DocumentedFreeChat.Status.PAID -> R.string.nm_free_status_priced
    DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED -> R.string.nm_free_status_plan_unconfirmed
    DocumentedFreeChat.Status.PAYMENT_REQUIRED -> R.string.nm_free_status_payment
    DocumentedFreeChat.Status.UNKNOWN -> R.string.nm_free_status_unknown
}

internal fun freeStatusAction(status: DocumentedFreeChat.Status?): Int = when (status) {
    DocumentedFreeChat.Status.PAID, DocumentedFreeChat.Status.PAYMENT_REQUIRED -> R.string.nm_free_paid_action
    DocumentedFreeChat.Status.FREE_PLAN_UNCONFIRMED -> R.string.nm_free_plan_action
    DocumentedFreeChat.Status.UNKNOWN -> R.string.nm_free_unknown_action
    else -> R.string.nm_free_chat_choose
}
