package com.xingheyuzhuan.shiguangschedule.service.qzh5

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * qzh5 自动同步凭据存储。
 *
 * 学号、加密 pwd 和目标课表 ID 会先使用 Android Keystore 中的 AES/GCM 密钥加密，
 * 再写入应用私有 SharedPreferences。
 */
class Qzh5CredentialStore(context: Context) {
    data class StoredCredentials(
        val userNo: String,
        val encryptedPwd: String,
        val tableId: String
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(userNo: String, encryptedPwd: String, tableId: String) {
        require(userNo.isNotBlank())
        require(encryptedPwd.isNotBlank())

        val payload = JSONObject()
            .put("userNo", userNo.trim())
            .put("encryptedPwd", encryptedPwd.trim())
            .put("tableId", tableId.trim())
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())

        prefs.edit()
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_DATA, Base64.encodeToString(cipher.doFinal(payload), Base64.NO_WRAP))
            .apply()
    }

    fun load(): StoredCredentials? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY_DATA, null) ?: return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
            )

            val plain = cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
            val json = JSONObject(String(plain, Charsets.UTF_8))

            StoredCredentials(
                userNo = json.getString("userNo"),
                encryptedPwd = json.getString("encryptedPwd"),
                tableId = json.optString("tableId", "")
            )
        }.getOrNull()
    }

    fun hasCredentials(): Boolean = load() != null

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "qzh5_auto_sync_credentials"
        private const val KEY_ALIAS = "shiguang_ynvct_qzh5_sync_key_v1"
        private const val KEY_IV = "iv"
        private const val KEY_DATA = "data"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
