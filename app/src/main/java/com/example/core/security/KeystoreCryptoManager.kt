package com.example.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Android Keystore AES-256-GCM cipher helper.
 * Separate Keystore aliases can be used per credential domain for strict isolation.
 */
class KeystoreCryptoManager(private val keyAlias: String) {

    private val keyStore: KeyStore? by lazy {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey? {
        val ks = keyStore ?: return null
        val existing = runCatching {
            (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
        }.getOrNull()
        if (existing != null) return existing

        return runCatching {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }.getOrNull()
    }

    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val key = getOrCreateSecretKey() ?: return ""
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataB64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$PREFIX_V1:$ivB64:$dataB64"
        }.getOrDefault("")
    }

    fun decrypt(cipherText: String): String {
        if (cipherText.isBlank()) return ""
        if (!cipherText.startsWith("$PREFIX_V1:")) return ""
        val parts = cipherText.split(":")
        if (parts.size != 3) return ""
        val key = getOrCreateSecretKey() ?: return ""
        return runCatching {
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrDefault("")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val PREFIX_V1 = "v1"
    }
}
