package org.thoughtcrime.securesms.loki.api

import android.content.Context
import android.util.Log
import com.goterl.lazycode.lazysodium.LazySodiumAndroid
import com.goterl.lazycode.lazysodium.SodiumAndroid
import com.goterl.lazycode.lazysodium.interfaces.Box
import com.goterl.lazycode.lazysodium.interfaces.Sign
import com.goterl.lazycode.lazysodium.utils.KeyPair
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.loki.utilities.KeyPairUtilities
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.libsignal.util.Hex
import org.whispersystems.signalservice.api.messages.SignalServiceEnvelope
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope.Type.CLOSED_GROUP_CIPHERTEXT_VALUE
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Envelope.Type.UNIDENTIFIED_SENDER_VALUE
import org.whispersystems.signalservice.loki.api.SnodeAPI
import org.whispersystems.signalservice.loki.api.crypto.SessionProtocol
import org.whispersystems.signalservice.loki.utilities.removing05PrefixIfNeeded
import org.whispersystems.signalservice.loki.utilities.toHexString

class SessionProtocolImpl(private val context: Context) : SessionProtocol {

    override fun encrypt(plaintext: ByteArray, recipientHexEncodedX25519PublicKey: String): ByteArray {
        val userED25519KeyPair = KeyPairUtilities.getUserED25519KeyPair(context) ?: throw SessionProtocol.Exception.NoUserED25519KeyPair
        val recipientX25519PublicKey = Hex.fromStringCondensed(recipientHexEncodedX25519PublicKey.removing05PrefixIfNeeded())
        val sodium = LazySodiumAndroid(SodiumAndroid())

        val verificationData = plaintext + userED25519KeyPair.publicKey.asBytes + recipientX25519PublicKey
        val signature = ByteArray(Sign.BYTES)
        try {
            sodium.cryptoSignDetached(signature, verificationData, verificationData.size.toLong(), userED25519KeyPair.secretKey.asBytes)
        } catch (exception: Exception) {
            Log.d("Loki", "Couldn't sign message due to error: $exception.")
            throw SessionProtocol.Exception.SigningFailed
        }
        val plaintextWithMetadata = plaintext + userED25519KeyPair.publicKey.asBytes + signature
        val ciphertext = ByteArray(plaintextWithMetadata.size + Box.SEALBYTES)
        try {
            sodium.cryptoBoxSeal(ciphertext, plaintextWithMetadata, plaintextWithMetadata.size.toLong(), recipientX25519PublicKey)
        } catch (exception: Exception) {
            Log.d("Loki", "Couldn't encrypt message due to error: $exception.")
            throw SessionProtocol.Exception.EncryptionFailed
        }

        return ciphertext
    }

    override fun decrypt(envelope: SignalServiceEnvelope): Pair<ByteArray, String> {
        val ciphertext = envelope.content ?: throw SessionProtocol.Exception.NoData
        val recipientX25519PrivateKey: ByteArray
        val recipientX25519PublicKey: ByteArray
        when (envelope.type) {
            UNIDENTIFIED_SENDER_VALUE -> {
                recipientX25519PrivateKey = IdentityKeyUtil.getIdentityKeyPair(context).privateKey.serialize()
                recipientX25519PublicKey = Hex.fromStringCondensed(TextSecurePreferences.getLocalNumber(context).removing05PrefixIfNeeded())
            }
            CLOSED_GROUP_CIPHERTEXT_VALUE -> {
                val hexEncodedGroupPublicKey = envelope.source
                val sskDB = DatabaseFactory.getSSKDatabase(context)
                if (!sskDB.isSSKBasedClosedGroup(hexEncodedGroupPublicKey)) { throw SessionProtocol.Exception.InvalidGroupPublicKey }
                val hexEncodedGroupPrivateKey = sskDB.getClosedGroupPrivateKey(hexEncodedGroupPublicKey) ?: throw SessionProtocol.Exception.NoGroupPrivateKey
                recipientX25519PrivateKey = Hex.fromStringCondensed(hexEncodedGroupPrivateKey)
                recipientX25519PublicKey = Hex.fromStringCondensed(hexEncodedGroupPublicKey.removing05PrefixIfNeeded())
            }
            else -> throw AssertionError()
        }
        val sodium = LazySodiumAndroid(SodiumAndroid())
        val signatureSize = Sign.BYTES
        val ed25519PublicKeySize = Sign.PUBLICKEYBYTES

        // 1. ) Decrypt the message
        val plaintextWithMetadata = ByteArray(ciphertext.size - Box.SEALBYTES)
        try {
            sodium.cryptoBoxSealOpen(plaintextWithMetadata, ciphertext, ciphertext.size.toLong(), recipientX25519PublicKey, recipientX25519PrivateKey)
        } catch (exception: Exception) {
            Log.d("Loki", "Couldn't decrypt message due to error: $exception.")
            throw SessionProtocol.Exception.DecryptionFailed
        }
        if (plaintextWithMetadata.size <= (signatureSize + ed25519PublicKeySize)) { throw SessionProtocol.Exception.DecryptionFailed }
        // 2. ) Get the message parts
        val signature = plaintextWithMetadata.sliceArray(plaintextWithMetadata.size - signatureSize until plaintextWithMetadata.size)
        val senderED25519PublicKey = plaintextWithMetadata.sliceArray(plaintextWithMetadata.size - (signatureSize + ed25519PublicKeySize) until plaintextWithMetadata.size - signatureSize)
        val plaintext = plaintextWithMetadata.sliceArray(0 until plaintextWithMetadata.size - (signatureSize + ed25519PublicKeySize))
        // 3. ) Verify the signature
        val verificationData = (plaintext + senderED25519PublicKey + recipientX25519PublicKey)
        try {
            val isValid = sodium.cryptoSignVerifyDetached(signature, verificationData, verificationData.size, senderED25519PublicKey)
            if (!isValid) { throw SessionProtocol.Exception.InvalidSignature }
        } catch (exception: Exception) {
            Log.d("Loki", "Couldn't verify message signature due to error: $exception.")
            throw SessionProtocol.Exception.InvalidSignature
        }
        // 4. ) Get the sender's X25519 public key
        val senderX25519PublicKey = ByteArray(Sign.CURVE25519_PUBLICKEYBYTES)
        sodium.convertPublicKeyEd25519ToCurve25519(senderX25519PublicKey, senderED25519PublicKey)

        return Pair(plaintext, "05" + senderX25519PublicKey.toHexString())
    }
}