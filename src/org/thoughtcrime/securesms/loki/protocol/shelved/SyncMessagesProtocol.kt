package org.thoughtcrime.securesms.loki.protocol.shelved

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.contacts.ContactAccessor.ContactData
import org.thoughtcrime.securesms.contacts.ContactAccessor.NumberData
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.RecipientDatabase
import org.thoughtcrime.securesms.groups.GroupManager
import org.thoughtcrime.securesms.groups.GroupMessageProcessor
import org.thoughtcrime.securesms.jobs.MultiDeviceContactUpdateJob
import org.thoughtcrime.securesms.jobs.MultiDeviceGroupUpdateJob
import org.thoughtcrime.securesms.loki.utilities.ContactUtilities
import org.thoughtcrime.securesms.loki.utilities.OpenGroupUtilities
import org.thoughtcrime.securesms.loki.utilities.recipient
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.signalservice.api.messages.SignalServiceAttachment
import org.whispersystems.signalservice.api.messages.SignalServiceContent
import org.whispersystems.signalservice.api.messages.SignalServiceDataMessage
import org.whispersystems.signalservice.api.messages.SignalServiceGroup
import org.whispersystems.signalservice.api.messages.multidevice.BlockedListMessage
import org.whispersystems.signalservice.api.messages.multidevice.ContactsMessage
import org.whispersystems.signalservice.api.messages.multidevice.DeviceContactsInputStream
import org.whispersystems.signalservice.api.messages.multidevice.DeviceGroupsInputStream
import org.whispersystems.signalservice.loki.api.opengroups.PublicChat
import org.whispersystems.signalservice.loki.protocol.shelved.multidevice.MultiDeviceProtocol
import org.whispersystems.signalservice.loki.utilities.PublicKeyValidation

object SyncMessagesProtocol {

    @JvmStatic
    fun shouldIgnoreSyncMessage(context: Context, sender: Recipient): Boolean {
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        return userPublicKey == sender.address.serialize() // return !MultiDeviceProtocol.shared.getAllLinkedDevices(userPublicKey).contains(sender.address.serialize())
    }

    @JvmStatic
    fun syncContact(context: Context, address: Address) {
        ApplicationContext.getInstance(context).jobManager.add(MultiDeviceContactUpdateJob(context, address, true))
    }

    @JvmStatic
    fun syncAllContacts(context: Context) {
        ApplicationContext.getInstance(context).jobManager.add(MultiDeviceContactUpdateJob(context, true))
    }

    @JvmStatic
    fun getContactsToSync(context: Context): List<ContactData> {
        val contacts = ContactUtilities.getAllContacts(context)
        val result = mutableSetOf<ContactData>()
        for (contact in contacts) {
            val contactPublicKey = contact.recipient.address.serialize()
            if (!shouldSyncContact(context, contactPublicKey)) { continue }
            val threadID = DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(recipient(context, contactPublicKey))
            if (threadID < 0) { continue }
            val displayName = DatabaseFactory.getLokiUserDatabase(context).getDisplayName(contactPublicKey)
            val contactData = ContactData(threadID, displayName)
            contactData.numbers.add(NumberData("TextSecure", contactPublicKey))
            result.add(contactData)
        }
        return result.toList()
    }

    @JvmStatic
    fun shouldSyncContact(context: Context, publicKey: String): Boolean {
        if (!PublicKeyValidation.isValid(publicKey)) { return false }
        if (publicKey == TextSecurePreferences.getMasterHexEncodedPublicKey(context)) { return false }
        if (publicKey == TextSecurePreferences.getLocalNumber(context)) { return false }
        if (MultiDeviceProtocol.shared.getSlaveDevices(publicKey).contains(publicKey)) { return false }
        return true
    }

    @JvmStatic
    fun syncAllClosedGroups(context: Context) {
        ApplicationContext.getInstance(context).jobManager.add(MultiDeviceGroupUpdateJob())
    }

    @JvmStatic
    fun syncAllOpenGroups(context: Context) {
        ApplicationContext.getInstance(context).jobManager.add(MultiDeviceOpenGroupUpdateJob())
    }

    @JvmStatic
    fun shouldSyncReadReceipt(address: Address): Boolean {
        return !address.isGroup
    }

    @JvmStatic
    fun handleContactSyncMessage(context: Context, content: SignalServiceContent, message: ContactsMessage) {
        if (!message.contactsStream.isStream) { return }
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val allUserDevices = MultiDeviceProtocol.shared.getAllLinkedDevices(userPublicKey)
        if (!allUserDevices.contains(content.sender)) { return }
        Log.d("Loki", "Received a contact sync message.")
        val contactsInputStream = DeviceContactsInputStream(message.contactsStream.asStream().inputStream)
        val contacts = contactsInputStream.readAll()
        for (contact in contacts) {
            val contactPublicKey = contact.number
            if (contactPublicKey == userPublicKey || !PublicKeyValidation.isValid(contactPublicKey)) { return }
            val applicationContext = context.applicationContext as ApplicationContext
            applicationContext.sendSessionRequestIfNeeded(contactPublicKey)
            DatabaseFactory.getRecipientDatabase(context).setBlocked(recipient(context, contactPublicKey), contact.isBlocked)
        }
    }

    @JvmStatic
    fun handleClosedGroupSyncMessage(context: Context, content: SignalServiceContent, message: SignalServiceAttachment) {
        if (!message.isStream) { return }
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val allUserDevices = MultiDeviceProtocol.shared.getAllLinkedDevices(userPublicKey)
        if (!allUserDevices.contains(content.sender)) { return }
        Log.d("Loki", "Received a closed group sync message.")
        val closedGroupsInputStream = DeviceGroupsInputStream(message.asStream().inputStream)
        val closedGroups = closedGroupsInputStream.readAll()
        for (closedGroup in closedGroups) {
            val signalServiceGroup = SignalServiceGroup(
                SignalServiceGroup.Type.UPDATE,
                closedGroup.id,
                SignalServiceGroup.GroupType.SIGNAL,
                closedGroup.name.orNull(),
                closedGroup.members,
                closedGroup.avatar.orNull(),
                closedGroup.admins
            )
            val dataMessage = SignalServiceDataMessage(content.timestamp, signalServiceGroup, null, null)
            // This establishes sessions internally
            GroupMessageProcessor.process(context, content, dataMessage, false)
        }
    }

    @JvmStatic
    @WorkerThread
    fun handleOpenGroupSyncMessage(context: Context, content: SignalServiceContent, openGroups: List<PublicChat>) {
        val userPublicKey = TextSecurePreferences.getLocalNumber(context)
        val allUserDevices = MultiDeviceProtocol.shared.getAllLinkedDevices(userPublicKey)
        if (!allUserDevices.contains(content.sender)) { return }
        Log.d("Loki", "Received an open group sync message.")
        for (openGroup in openGroups) {
            val threadID: Long = GroupManager.getOpenGroupThreadID(openGroup.id, context)
            if (threadID > -1) { continue } // Skip existing open groups
            val url = openGroup.server
            val channel = openGroup.channel
            OpenGroupUtilities.addGroup(context, url, channel)
        }
    }

    @JvmStatic
    fun handleBlockedContactsSyncMessage(context: Context, content: SignalServiceContent, blockedContacts: BlockedListMessage) {
        val recipientDB = DatabaseFactory.getRecipientDatabase(context)
        val cursor = recipientDB.blocked
        val blockedPublicKeys = blockedContacts.numbers.toSet()
        val publicKeysToUnblock = mutableSetOf<String>()
        fun addToUnblockListIfNeeded() {
            val publicKey = cursor.getString(cursor.getColumnIndex(RecipientDatabase.ADDRESS)) ?: return
            if (blockedPublicKeys.contains(publicKey)) { return }
            publicKeysToUnblock.add(publicKey)
        }
        while (cursor.moveToNext()) {
            addToUnblockListIfNeeded()
        }
        publicKeysToUnblock.forEach {
            recipientDB.setBlocked(recipient(context, it), false)
        }
        blockedPublicKeys.forEach {
            recipientDB.setBlocked(recipient(context, it), true)
        }
        ApplicationContext.getInstance(context).broadcaster.broadcast("blockedContactsChanged")
    }
}