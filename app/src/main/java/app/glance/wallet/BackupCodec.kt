package app.glance.wallet

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val BACKUP_FORMAT_VERSION = 1
private const val BACKUP_ITERATIONS = 310_000
private const val SALT_BYTES = 16
private const val NONCE_BYTES = 12

internal data class BackupWalletGroup(
    val id: String,
    val label: String,
    val utxoView: String,
    val dustThresholdSats: Long,
    val preferredReceiveScriptType: String,
)
internal data class BackupWatchedKey(
    val id: String,
    val label: String,
    val keyMaterial: String,
    val scriptType: String,
    val targetType: String,
    val walletGroupId: String?,
    val utxoView: String = "BUBBLES",
    val dustThresholdSats: Long = 5_000L,
)
internal data class BackupLabel(val referenceType: String, val referenceId: String, val text: String)
internal data class BackupServerConfig(val id: String, val protocol: String, val host: String, val port: Int, val useTls: Boolean, val isCustom: Boolean)
internal data class BackupSettings(
    val showBalanceChart: Boolean = true,
    val utxoView: String = "BUBBLES",
    val fiatCurrency: String = "USD",
    val explorerPreset: String = "MEMPOOL_SPACE",
    val torEnabled: Boolean = true,
    val offlineMode: Boolean = false,
    val stealthMode: String = "OFF",
    val streetModeEnabled: Boolean = false,
)
internal data class BackupSnapshot(
    val walletGroups: List<BackupWalletGroup> = emptyList(),
    val watchedKeys: List<BackupWatchedKey> = emptyList(),
    val labels: List<BackupLabel> = emptyList(),
    val serverConfigs: List<BackupServerConfig> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

internal class InvalidBackupException : Exception("Backup cannot be opened")
internal interface BackupRandom { fun nextBytes(size: Int): ByteArray }
internal object SecureBackupRandom : BackupRandom { override fun nextBytes(size: Int) = ByteArray(size).also(SecureRandom()::nextBytes) }
internal object DeterministicBackupRandom : BackupRandom {
    private var counter = 0
    override fun nextBytes(size: Int) = ByteArray(size) { (counter++ and 0xff).toByte() }
}

/** Password-encrypted, versioned configuration backup. Never include security or cache state. */
internal object BackupCodec {
    fun encrypt(snapshot: BackupSnapshot, passphrase: CharArray, random: BackupRandom = SecureBackupRandom): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase is required" }
        val salt = random.nextBytes(SALT_BYTES)
        val nonce = random.nextBytes(NONCE_BYTES)
        val payload = encodeSnapshot(snapshot).encodeToByteArray()
        val key = deriveKey(passphrase, salt, BACKUP_ITERATIONS)
        return try {
            val metadata = metadata(salt, nonce, BACKUP_ITERATIONS)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(metadata.encodeToByteArray())
            val ciphertext = cipher.doFinal(payload)
            Json.encodeToString(JsonObject.serializer(), buildJsonObject {
                put("version", BACKUP_FORMAT_VERSION); put("kdf", "PBKDF2-HMAC-SHA256")
                put("iterations", BACKUP_ITERATIONS); put("salt", salt.base64()); put("nonce", nonce.base64()); put("ciphertext", ciphertext.base64())
            }).encodeToByteArray()
        } finally { key.fill(0); payload.fill(0) }
    }

    fun decrypt(document: ByteArray, passphrase: CharArray): BackupSnapshot {
        if (passphrase.isEmpty()) throw InvalidBackupException()
        val root = try { Json.parseToJsonElement(document.decodeToString()).jsonObject } catch (_: Exception) { throw InvalidBackupException() }
        val version = root.int("version") ?: throw InvalidBackupException()
        val kdf = root.string("kdf") ?: throw InvalidBackupException()
        val iterations = root.int("iterations") ?: throw InvalidBackupException()
        if (version != BACKUP_FORMAT_VERSION || kdf != "PBKDF2-HMAC-SHA256" || iterations !in 100_000..1_000_000) throw InvalidBackupException()
        val salt = root.bytes("salt", SALT_BYTES) ?: throw InvalidBackupException()
        val nonce = root.bytes("nonce", NONCE_BYTES) ?: throw InvalidBackupException()
        val ciphertext = root.bytes("ciphertext", 17, maximum = 1_000_000) ?: throw InvalidBackupException()
        val key = deriveKey(passphrase, salt, iterations)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(metadata(salt, nonce, iterations).encodeToByteArray())
            cipher.doFinal(ciphertext)
        } catch (_: Exception) { throw InvalidBackupException() } finally { key.fill(0) }
        return try { decodeSnapshot(plaintext.decodeToString()) } catch (_: Exception) { throw InvalidBackupException() } finally { plaintext.fill(0) }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val password = passphrase.concatToString().encodeToByteArray()
        return try {
            val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(password, "HmacSHA256"))
            val result = ByteArray(32); var previous = ByteArray(0); var offset = 0; var block = 1
            while (offset < result.size) {
                mac.update(salt); mac.update(byteArrayOf(0, 0, 0, block.toByte())); var u = mac.doFinal(); previous = u.copyOf()
                repeat(iterations - 1) { mac.update(u); u = mac.doFinal(); for (i in result.indices) if (i < u.size) previous[i] = (previous[i].toInt() xor u[i].toInt()).toByte() }
                val count = minOf(previous.size, result.size - offset); previous.copyInto(result, offset, 0, count); offset += count; block++
            }; previous.fill(0); result
        } finally { password.fill(0) }
    }

    private fun metadata(salt: ByteArray, nonce: ByteArray, iterations: Int) = "glance-backup|$BACKUP_FORMAT_VERSION|PBKDF2-HMAC-SHA256|$iterations|${salt.base64()}|${nonce.base64()}"
    private fun ByteArray.base64() = java.util.Base64.getEncoder().encodeToString(this)
    private fun JsonObject.string(name: String) = this[name]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.int(name: String) = string(name)?.toIntOrNull()
    private fun JsonObject.bytes(name: String, minimum: Int, maximum: Int = minimum): ByteArray? {
        val encoded = string(name) ?: return null
        return try { java.util.Base64.getDecoder().decode(encoded).takeIf { it.size in minimum..maximum } } catch (_: Exception) { null }
    }

    private fun encodeSnapshot(s: BackupSnapshot): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("walletGroups", s.walletGroups.toJson { g -> obj("id" to g.id, "label" to g.label, "utxoView" to g.utxoView, "dust" to g.dustThresholdSats, "receive" to g.preferredReceiveScriptType) })
        put("watchedKeys", s.watchedKeys.toJson { k -> obj("id" to k.id, "label" to k.label, "source" to k.keyMaterial, "script" to k.scriptType, "target" to k.targetType, "group" to k.walletGroupId, "utxoView" to k.utxoView, "dust" to k.dustThresholdSats) })
        put("labels", s.labels.toJson { l -> obj("type" to l.referenceType, "id" to l.referenceId, "text" to l.text) })
        put("serverConfigs", s.serverConfigs.toJson { c -> obj("id" to c.id, "protocol" to c.protocol, "host" to c.host, "port" to c.port, "tls" to c.useTls, "custom" to c.isCustom) })
        put("settings", obj("chart" to s.settings.showBalanceChart, "utxoView" to s.settings.utxoView, "fiat" to s.settings.fiatCurrency, "explorer" to s.settings.explorerPreset, "tor" to s.settings.torEnabled, "offline" to s.settings.offlineMode, "stealth" to s.settings.stealthMode, "street" to s.settings.streetModeEnabled))
    })
    private fun <T> List<T>.toJson(transform: (T) -> JsonObject) = buildJsonArray { forEach { add(transform(it)) } }
    private fun obj(vararg fields: Pair<String, Any?>) = buildJsonObject { fields.forEach { (k, v) -> when (v) { null -> put(k, JsonPrimitive("")); is String -> put(k, v); is Boolean -> put(k, v); is Int -> put(k, v); is Long -> put(k, v) } } }
    private fun decodeSnapshot(text: String): BackupSnapshot {
        val root = Json.parseToJsonElement(text).jsonObject
        fun array(name: String) = root[name]?.jsonArray ?: throw IllegalArgumentException()
        fun JsonObject.required(name: String) = string(name) ?: throw IllegalArgumentException()
        return BackupSnapshot(
            walletGroups = array("walletGroups").map { it.jsonObject.let { g -> BackupWalletGroup(g.required("id"), g.required("label"), g.required("utxoView"), g.required("dust").toLong(), g.required("receive")) } },
            watchedKeys = array("watchedKeys").map { it.jsonObject.let { k -> BackupWatchedKey(k.required("id"), k.required("label"), k.required("source"), k.required("script"), k.required("target"), k.string("group")?.ifBlank { null }, k.required("utxoView"), k.required("dust").toLong()) } },
            labels = array("labels").map { it.jsonObject.let { l -> BackupLabel(l.required("type"), l.required("id"), l.required("text")) } },
            serverConfigs = array("serverConfigs").map { it.jsonObject.let { c -> BackupServerConfig(c.required("id"), c.required("protocol"), c.required("host"), c.required("port").toInt(), c.required("tls").toBooleanStrict(), c.required("custom").toBooleanStrict()) } },
            settings = root["settings"]!!.jsonObject.let { p -> BackupSettings(p.required("chart").toBooleanStrict(), p.required("utxoView"), p.required("fiat"), p.required("explorer"), p.required("tor").toBooleanStrict(), p.required("offline").toBooleanStrict(), p.required("stealth"), p.required("street").toBooleanStrict()) },
        )
    }
}
