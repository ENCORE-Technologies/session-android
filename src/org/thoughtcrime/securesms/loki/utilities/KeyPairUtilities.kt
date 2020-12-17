package org.thoughtcrime.securesms.loki.utilities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.goterl.lazycode.lazysodium.LazySodiumAndroid
import com.goterl.lazycode.lazysodium.SodiumAndroid
import com.goterl.lazycode.lazysodium.utils.Key
import com.goterl.lazycode.lazysodium.utils.KeyPair
import kotlinx.android.synthetic.main.activity_pn_mode.*
import org.thoughtcrime.securesms.ApplicationContext
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil
import org.thoughtcrime.securesms.database.Address
import org.thoughtcrime.securesms.database.DatabaseFactory
import org.thoughtcrime.securesms.database.IdentityDatabase
import org.thoughtcrime.securesms.loki.activities.HomeActivity
import org.thoughtcrime.securesms.util.Base64
import org.thoughtcrime.securesms.util.Hex
import org.thoughtcrime.securesms.util.TextSecurePreferences
import org.whispersystems.curve25519.Curve25519
import org.whispersystems.libsignal.ecc.DjbECPrivateKey
import org.whispersystems.libsignal.ecc.DjbECPublicKey
import org.whispersystems.libsignal.ecc.ECKeyPair
import org.whispersystems.libsignal.util.KeyHelper
import org.whispersystems.signalservice.loki.utilities.hexEncodedPublicKey
import org.whispersystems.signalservice.loki.utilities.toHexString

object KeyPairUtilities {

    data class KeyPairGenerationResult(
        val seed: ByteArray,
        val ed25519KeyPair: KeyPair,
        val x25519KeyPair: ECKeyPair
    )

    fun generate(): KeyPairGenerationResult {
        val seed = Curve25519.getInstance(Curve25519.BEST).generateSeed(16)
        try {
            return generate(seed)
        } catch (exception: Exception) {
            return generate()
        }
    }

    fun generate(seed: ByteArray): KeyPairGenerationResult {
        val sodium = LazySodiumAndroid(SodiumAndroid())
        val padding = ByteArray(16) { 0 }
        val ed25519KeyPair = sodium.cryptoSignSeedKeypair(seed + padding)
        val sodiumX25519KeyPair = sodium.convertKeyPairEd25519ToCurve25519(ed25519KeyPair)
        val x25519KeyPair = ECKeyPair(DjbECPublicKey(sodiumX25519KeyPair.publicKey.asBytes), DjbECPrivateKey(sodiumX25519KeyPair.secretKey.asBytes))
        return KeyPairGenerationResult(seed, ed25519KeyPair, x25519KeyPair)
    }

    fun store(context: Context, seed: ByteArray, ed25519KeyPair: KeyPair, x25519KeyPair: ECKeyPair) {
        IdentityKeyUtil.save(context, IdentityKeyUtil.LOKI_SEED, Hex.toStringCondensed(seed))
        IdentityKeyUtil.save(context, IdentityKeyUtil.IDENTITY_PUBLIC_KEY_PREF, Base64.encodeBytes(x25519KeyPair.publicKey.serialize()))
        IdentityKeyUtil.save(context, IdentityKeyUtil.IDENTITY_PRIVATE_KEY_PREF, Base64.encodeBytes(x25519KeyPair.privateKey.serialize()))
        IdentityKeyUtil.save(context, IdentityKeyUtil.ED25519_PUBLIC_KEY, Base64.encodeBytes(ed25519KeyPair.publicKey.asBytes))
        IdentityKeyUtil.save(context, IdentityKeyUtil.ED25519_SECRET_KEY, Base64.encodeBytes(ed25519KeyPair.secretKey.asBytes))
    }

    fun hasV2KeyPair(context: Context): Boolean {
        return (IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_SECRET_KEY) != null)
    }

    fun getUserED25519KeyPair(context: Context): KeyPair? {
        val hexEncodedED25519PublicKey = IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_PUBLIC_KEY) ?: return null
        val hexEncodedED25519SecretKey = IdentityKeyUtil.retrieve(context, IdentityKeyUtil.ED25519_SECRET_KEY) ?: return null
        val ed25519PublicKey = Key.fromBase64String(hexEncodedED25519PublicKey)
        val ed25519SecretKey = Key.fromBase64String(hexEncodedED25519SecretKey)
        return KeyPair(ed25519PublicKey, ed25519SecretKey)
    }

    fun migrateToV2KeyPair(context: AppCompatActivity) {
        val keyPairGenerationResult = generate()
        val seed = keyPairGenerationResult.seed
        val ed25519KeyPair = keyPairGenerationResult.ed25519KeyPair
        val x25519KeyPair = keyPairGenerationResult.x25519KeyPair
        store(context, seed, ed25519KeyPair, x25519KeyPair)
        val userHexEncodedPublicKey = x25519KeyPair.hexEncodedPublicKey
        val registrationID = KeyHelper.generateRegistrationId(false)
        TextSecurePreferences.setLocalRegistrationId(context, registrationID)
        DatabaseFactory.getIdentityDatabase(context).saveIdentity(Address.fromSerialized(userHexEncodedPublicKey),
            IdentityKeyUtil.getIdentityKeyPair(context).publicKey, IdentityDatabase.VerifiedStatus.VERIFIED,
            true, System.currentTimeMillis(), true)
        TextSecurePreferences.setLocalNumber(context, userHexEncodedPublicKey)
        TextSecurePreferences.setRestorationTime(context, 0)
        TextSecurePreferences.setHasViewedSeed(context, false)
        TextSecurePreferences.setHasSeenWelcomeScreen(context, true)
        TextSecurePreferences.setPromptedPushRegistration(context, true)
        TextSecurePreferences.setIsUsingFCM(context, TextSecurePreferences.isUsingFCM(context))
        TextSecurePreferences.setHasSeenMultiDeviceRemovalSheet(context)
        TextSecurePreferences.setHasSeenLightThemeIntroSheet(context)
        val application = ApplicationContext.getInstance(context)
        application.setUpStorageAPIIfNeeded()
        application.setUpP2PAPIIfNeeded()
        val intent = Intent(context, HomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        context.show(intent)
    }
}