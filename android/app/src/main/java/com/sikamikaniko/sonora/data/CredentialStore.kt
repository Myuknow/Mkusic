package com.sikamikaniko.sonora.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials are never stored in plain text or Android backup. */
class CredentialStore(context: Context) {
    private val preferences = context.getSharedPreferences("workstation_connection", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun save(connection: ServerConnection) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(connection.password.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("server", connection.baseUrl.toString())
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit()) {
            "无法保存连接配置"
        }
    }

    fun load(): ServerConnection? {
        val server = preferences.getString("server", null) ?: return null
        val iv = preferences.getString("iv", null) ?: return null
        val ciphertext = preferences.getString("ciphertext", null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            val password = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
            ServerConnection.create(server, password)
        } catch (_: GeneralSecurityException) {
            null // An invalidated Keystore key requires the user to reconnect.
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun clear() { preferences.edit().clear().apply() }

    companion object { private const val KEY_ALIAS = "workstation_server_credentials_v1" }
}
