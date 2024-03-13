package org.thoughtcrime.securesms.messages

import android.content.Context
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.orNull
import org.thoughtcrime.securesms.database.MessageTable.InsertResult
import org.thoughtcrime.securesms.database.MessageType
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.MessageId
import org.thoughtcrime.securesms.database.model.MmsMessageRecord
import org.thoughtcrime.securesms.database.model.databaseprotos.BodyRangeList
import org.thoughtcrime.securesms.database.model.toBodyRangeList
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.groups.GroupId
import org.thoughtcrime.securesms.jobs.AttachmentDownloadJob
import org.thoughtcrime.securesms.jobs.PushProcessEarlyMessagesJob
import org.thoughtcrime.securesms.jobs.SendDeliveryReceiptJob
import org.thoughtcrime.securesms.messages.MessageContentProcessor.Companion.log
import org.thoughtcrime.securesms.messages.MessageContentProcessor.Companion.warn
import org.thoughtcrime.securesms.messages.SignalServiceProtoUtil.groupId
import org.thoughtcrime.securesms.messages.SignalServiceProtoUtil.isMediaMessage
import org.thoughtcrime.securesms.messages.SignalServiceProtoUtil.toPointersWithinLimit
import org.thoughtcrime.securesms.mms.IncomingMessage
import org.thoughtcrime.securesms.mms.OutgoingMessage
import org.thoughtcrime.securesms.mms.QuoteModel
import org.thoughtcrime.securesms.notifications.v2.ConversationId.Companion.forConversation
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.recipients.RecipientId
import org.thoughtcrime.securesms.util.EarlyMessageCacheEntry
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.MessageConstraintsUtil
import org.thoughtcrime.securesms.util.hasAudio
import org.thoughtcrime.securesms.util.hasSharedContact
import org.whispersystems.signalservice.api.crypto.EnvelopeMetadata
import org.whispersystems.signalservice.internal.push.Content
import org.whispersystems.signalservice.internal.push.DataMessage
import org.whispersystems.signalservice.internal.push.Envelope

object EditMessageProcessor {
  fun process(
    context: Context,
    senderRecipient: Recipient,
    threadRecipient: Recipient,
    envelope: Envelope,
    content: Content,
    metadata: EnvelopeMetadata,
    earlyMessageCacheEntry: EarlyMessageCacheEntry?
  ) {
    val editMessage = content.editMessage!!

    log(envelope.timestamp!!, "[handleEditMessage] Edit message for " + editMessage.targetSentTimestamp)
    
    var targetMessage: MmsMessageRecord? = SignalDatabase.messages.getMessageFor(editMessage.targetSentTimestamp!!, senderRecipient.id) as? MmsMessageRecord
    var targetMessage2: MmsMessageRecord? = SignalDatabase.messages.getMessageFor2(editMessage.targetSentTimestamp!!) as? MmsMessageRecord
    
    var targetThreadRecipient: Recipient? = if (targetMessage != null) SignalDatabase.threads.getRecipientForThreadId(targetMessage.threadId) else null
    var targetThreadRecipient2: Recipient? = if (targetMessage2 != null) SignalDatabase.threads.getRecipientForThreadId(targetMessage2.threadId) else null
    var target2 = false

    if (targetMessage == null || targetThreadRecipient == null) {
      warn(envelope.timestamp!!, "[handleEditMessage] Could not find matching message! timestamp: ${editMessage.targetSentTimestamp}  author: ${senderRecipient.id}")

      if (targetMessage2 == null || targetThreadRecipient2 == null) {
        warn(envelope.timestamp!!, "[handleEditMessage] Could not find ANY matching message! timestamp: ${editMessage.targetSentTimestamp}")

        if (earlyMessageCacheEntry != null) {
          ApplicationDependencies.getEarlyMessageCache().store(senderRecipient.id, editMessage.targetSentTimestamp!!, earlyMessageCacheEntry)
          PushProcessEarlyMessagesJob.enqueue()
        }
        return
      }
      targetMessage = targetMessage2
      targetThreadRecipient = targetThreadRecipient2
      target2 = true
    }

    val message = editMessage.dataMessage!!
    val isMediaMessage = message.isMediaMessage
    val groupId: GroupId.V2? = message.groupV2?.groupId

    val originalMessage = targetMessage.originalMessageId?.let { SignalDatabase.messages.getMessageRecord(it.id) } ?: targetMessage
    val validTiming = MessageConstraintsUtil.isValidEditMessageReceive(originalMessage, senderRecipient, envelope.serverTimestamp!!)
    val validAuthor = senderRecipient.id == originalMessage.fromRecipient.id
    val validGroup = groupId == targetThreadRecipient.groupId.orNull()
    val validTarget = !originalMessage.isViewOnce && !originalMessage.hasAudio() && !originalMessage.hasSharedContact()

    warn(envelope.timestamp!!, "[handleEditMessage] Message edit! editTime: ${envelope.serverTimestamp}, targetTime: ${originalMessage.serverTimestamp}, editAuthor: ${senderRecipient.id}, targetAuthor: ${originalMessage.fromRecipient.id}, editThread: ${threadRecipient.id}, targetThread: ${targetThreadRecipient.id}, target2: ${target2} validity: (timing: $validTiming, author: $validAuthor, group: $validGroup, target: $validTarget)")

    if (!validGroup || !validTarget) {
      warn(envelope.timestamp!!, "[handleEditMessage] Invalid message edit!)")
      return
    }

    if (groupId != null && MessageContentProcessor.handleGv2PreProcessing(context, envelope.timestamp!!, content, metadata, groupId, message.groupV2!!, senderRecipient) == MessageContentProcessor.Gv2PreProcessResult.IGNORE) {
      warn(envelope.timestamp!!, "[handleEditMessage] Group processor indicated we should ignore this.")
      return
    }

    DataMessageProcessor.notifyTypingStoppedFromIncomingMessage(context, senderRecipient, threadRecipient.id, metadata.sourceDeviceId)

    targetMessage = targetMessage.withAttachments(SignalDatabase.attachments.getAttachmentsForMessage(targetMessage.id))
    
    val insertResult: InsertResult? = if (isMediaMessage || targetMessage.quote != null || targetMessage.slideDeck.slides.isNotEmpty()) {
      if (originalMessage.fromRecipient.isSelf) {
        handleEditMediaMessageOutgoing(originalMessage.fromRecipient.id, groupId, envelope, metadata, message, targetMessage)
      } else {
        handleEditMediaMessage(originalMessage.fromRecipient.id, groupId, envelope, metadata, message, targetMessage)
      }
    } else {
      if (originalMessage.fromRecipient.isSelf) {
        handleEditTextMessageOutgoing(originalMessage.fromRecipient.id, groupId, envelope, metadata, message, targetMessage)
      } else {
        handleEditTextMessage(originalMessage.fromRecipient.id, groupId, envelope, metadata, message, targetMessage)
      }
    }

    if (insertResult != null) {
      SignalExecutors.BOUNDED.execute {
        ApplicationDependencies.getJobManager().add(SendDeliveryReceiptJob(senderRecipient.id, message.timestamp!!, MessageId(insertResult.messageId)))
      }

      if (targetMessage.expireStarted > 0) {
        ApplicationDependencies.getExpiringMessageManager()
          .scheduleDeletion(
            insertResult.messageId,
            true,
            targetMessage.expireStarted,
            targetMessage.expiresIn
          )
      }

      ApplicationDependencies.getMessageNotifier().updateNotification(context, forConversation(insertResult.threadId))
    }
  }

  private fun handleEditMediaMessage(
    senderRecipientId: RecipientId,
    groupId: GroupId.V2?,
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    message: DataMessage,
    targetMessage: MmsMessageRecord
  ): InsertResult? {
    val messageRanges: BodyRangeList? = message.bodyRanges.filter { it.mentionAci == null }.toList().toBodyRangeList()
    val targetQuote = targetMessage.quote
    val quote: QuoteModel? = if (targetQuote != null && message.quote != null) {
      QuoteModel(
        targetQuote.id,
        targetQuote.author,
        targetQuote.displayText.toString(),
        targetQuote.isOriginalMissing,
        emptyList(),
        null,
        targetQuote.quoteType,
        null
      )
    } else {
      null
    }
    val attachments = message.attachments.toPointersWithinLimit()
    attachments.filter {
      MediaUtil.SlideType.LONG_TEXT == MediaUtil.getSlideTypeFromContentType(it.contentType)
    }
    val mediaMessage = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderRecipientId,
      sentTimeMillis = message.timestamp!!,
      serverTimeMillis = envelope.serverTimestamp!!,
      receivedTimeMillis = targetMessage.dateReceived,
      expiresIn = targetMessage.expiresIn,
      isViewOnce = message.isViewOnce == true,
      isUnidentified = metadata.sealedSender,
      body = message.body,
      groupId = groupId,
      attachments = attachments,
      quote = quote,
      sharedContacts = emptyList(),
      linkPreviews = DataMessageProcessor.getLinkPreviews(message.preview, message.body ?: "", false),
      mentions = DataMessageProcessor.getMentions(message.bodyRanges),
      serverGuid = envelope.serverGuid,
      messageRanges = messageRanges
    )

    val insertResult = SignalDatabase.messages.insertEditMessageInbox(mediaMessage, targetMessage).orNull()
    if (insertResult?.insertedAttachments != null) {
      SignalDatabase.runPostSuccessfulTransaction {
        val downloadJobs: List<AttachmentDownloadJob> = insertResult.insertedAttachments.mapNotNull { (_, attachmentId) ->
          AttachmentDownloadJob(insertResult.messageId, attachmentId, false)
        }
        ApplicationDependencies.getJobManager().addAll(downloadJobs)
      }
    }
    return insertResult
  }

  private fun handleEditMediaMessageOutgoing(
    senderRecipientId: RecipientId,
    groupId: GroupId.V2?,
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    message: DataMessage,
    targetMessage: MmsMessageRecord
  ): InsertResult? {
    val messageRanges: BodyRangeList? = message.bodyRanges.filter { it.mentionAci == null }.toList().toBodyRangeList()
    val targetQuote = targetMessage.quote
    val quote: QuoteModel? = if (targetQuote != null && message.quote != null) {
      QuoteModel(
        targetQuote.id,
        targetQuote.author,
        targetQuote.displayText.toString(),
        targetQuote.isOriginalMissing,
        emptyList(),
        null,
        targetQuote.quoteType,
        null
      )
    } else {
      null
    }
    val attachments = message.attachments.toPointersWithinLimit()
    attachments.filter {
      MediaUtil.SlideType.LONG_TEXT == MediaUtil.getSlideTypeFromContentType(it.contentType)
    }
    val mediaMessage = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderRecipientId,
      sentTimeMillis = message.timestamp!!,
      serverTimeMillis = envelope.serverTimestamp!!,
      receivedTimeMillis = targetMessage.dateReceived,
      expiresIn = targetMessage.expiresIn,
      isViewOnce = message.isViewOnce == true,
      isUnidentified = metadata.sealedSender,
      body = message.body,
      groupId = groupId,
      attachments = attachments,
      quote = quote,
      sharedContacts = emptyList(),
      linkPreviews = DataMessageProcessor.getLinkPreviews(message.preview, message.body ?: "", false),
      mentions = DataMessageProcessor.getMentions(message.bodyRanges),
      serverGuid = envelope.serverGuid,
      messageRanges = messageRanges
    )

    val insertResult = SignalDatabase.messages.insertEditMessageInbox(mediaMessage, targetMessage).orNull()
    if (insertResult?.insertedAttachments != null) {
      SignalDatabase.runPostSuccessfulTransaction {
        val downloadJobs: List<AttachmentDownloadJob> = insertResult.insertedAttachments.mapNotNull { (_, attachmentId) ->
          AttachmentDownloadJob(insertResult.messageId, attachmentId, false)
        }
        ApplicationDependencies.getJobManager().addAll(downloadJobs)
      }
    }
    return insertResult
  }

  private fun handleEditTextMessage(
    senderRecipientId: RecipientId,
    groupId: GroupId.V2?,
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    message: DataMessage,
    targetMessage: MmsMessageRecord
  ): InsertResult? {
    val textMessage = IncomingMessage(
      type = MessageType.NORMAL,
      from = senderRecipientId,
      sentTimeMillis = envelope.timestamp!!,
      serverTimeMillis = envelope.timestamp!!,
      receivedTimeMillis = targetMessage.dateReceived,
      body = message.body,
      groupId = groupId,
      expiresIn = targetMessage.expiresIn,
      isUnidentified = metadata.sealedSender,
      serverGuid = envelope.serverGuid
    )

    return SignalDatabase.messages.insertEditMessageInbox(textMessage, targetMessage).orNull()
  }
  
  private fun handleEditTextMessageOutgoing(
    senderRecipientId: RecipientId,
    groupId: GroupId.V2?,
    envelope: Envelope,
    metadata: EnvelopeMetadata,
    message: DataMessage,
    targetMessage: MmsMessageRecord
  ): InsertResult? {
    val textMessage = OutgoingMessage(
      threadRecipient = if (targetMessage != null) SignalDatabase.threads.getRecipientForThreadId(targetMessage.threadId) else null,
      sentTimeMillis = envelope.timestamp!!,
      body = message.body,
      expiresIn = targetMessage.expiresIn,
      isUrgent = true,
      isSecure = true,
      bodyRanges =  message.bodyRanges,
      messagetoEdit = targetMessage.originalMessageId 
    )

    return SignalDatabase.messages.insertEditMessageInbox(textMessage, targetMessage).orNull()
    #val editMessageId = SignalDatabase.messages.insertEditMessageInbox(textMessage, targetMessage.threadId,false,null).orNull()
    #SignalDatabase.messages.markAsSent(editMessageId,true)
    #if ((targetMessage.expiresIn ?: 0) > 0) {
    #  SignalDatabase.messages.markExpireStarted(editMessageId,envelope.timestamp!!)
    #}
    #return editMessageId
  }
}
