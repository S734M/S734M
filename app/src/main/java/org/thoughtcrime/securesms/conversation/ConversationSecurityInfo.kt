package org.thoughtcrime.securesms.conversation

import org.thoughtcrime.securesms.recipients.RecipientId

data class ConversationSecurityInfo(
  val recipientId: RecipientId = RecipientId.UNKNOWN,
  val isPushAvailable: Boolean = false,
  val isInitialized: Boolean = false,
  val isClientExpired: Boolean = false,
  val isUnauthorized: Boolean = false
)
