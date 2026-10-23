package app.ecoz

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The key that lets this phone unlock the laptop.
 *
 * It is an EC P-256 key created inside Android's secure hardware (StrongBox where the phone has
 * it): the private half can never be read out, not even by this app. It is created with
 * "unlocked device required", so Android refuses to use it while the phone is locked. The laptop
 * learns only the public half, once, after its owner approves it on the laptop.
 */
object UnlockKey {
    private const val ALIAS = "ecoz-unlock"
    private val PREFIX = "ecoz-unlock-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** The public key (X.509 DER), creating the key pair first if there isn't one. */
    fun publicKey(): ByteArray {
        val store = keystore()
        if (!store.containsAlias(ALIAS)) generate()
        return store.getCertificate(ALIAS).publicKey.encoded
    }

    private fun generate() {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUnlockedDeviceRequired(true) // unusable while the phone is locked
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        try {
            generator.initialize(spec(strongBox = true))
            generator.generateKeyPair()
        } catch (_: StrongBoxUnavailableException) {
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
        }
    }

    fun delete() {
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    /**
     * Signs the laptop's challenge, bound to that laptop's certificate fingerprint. Returns null if
     * the phone is locked (Android refuses the key) or the key no longer exists.
     */
    fun sign(nonce: ByteArray, laptopFingerprint: String): ByteArray? = try {
        val key = keystore().getKey(ALIAS, null) as? PrivateKey
        key?.let {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(it)
                update(PREFIX + nonce + laptopFingerprint.toByteArray(Charsets.US_ASCII))
                sign()
            }
        }
    } catch (_: Exception) {
        null // locked, invalidated or unavailable: simply don't answer
    }
}
