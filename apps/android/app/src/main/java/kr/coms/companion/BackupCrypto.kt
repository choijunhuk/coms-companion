package kr.coms.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.BadPaddingException
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable encrypted file format. The entire fixed header is authenticated as GCM AAD. */
object BackupCrypto {
    private val magic = "COMSBAK1".toByteArray(Charsets.US_ASCII)
    private const val iterations = 600_000
    private const val headerSize = 8 + 4 + 4 + 4 + 16 + 12
    private const val maxPlaintext = 32 * 1024 * 1024
    private const val tagSize = 16

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 10) { "백업 암호를 10자 이상 입력해 주세요" }
        require(plaintext.size in 1..maxPlaintext) { "백업 크기가 허용 범위를 벗어났어요" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val header = ByteBuffer.allocate(headerSize).put(magic).putInt(1).putInt(iterations)
            .putInt(plaintext.size).put(salt).put(iv).array()
        val key = derive(password, salt, iterations)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            ByteArrayOutputStream(header.size + plaintext.size + tagSize).apply {
                write(header)
                write(cipher.doFinal(plaintext))
            }.toByteArray()
        } finally { key.encoded.fill(0) }
    }

    fun decrypt(file: ByteArray, password: CharArray): ByteArray {
        require(file.size in (headerSize + tagSize + 1)..(headerSize + maxPlaintext + tagSize)) { "백업 파일 크기가 잘못됐어요" }
        val header = file.copyOfRange(0, headerSize)
        val data = ByteBuffer.wrap(header)
        val prefix = ByteArray(magic.size).also(data::get)
        require(prefix.contentEquals(magic)) { "지원하지 않는 백업 파일이에요" }
        require(data.int == 1) { "지원하지 않는 백업 버전이에요" }
        val rounds = data.int
        require(rounds in 100_000..1_000_000) { "백업 암호화 설정이 잘못됐어요" }
        val size = data.int
        require(size in 1..maxPlaintext && file.size == headerSize + size + tagSize) { "백업 내용 길이가 잘못됐어요" }
        val salt = ByteArray(16).also(data::get)
        val iv = ByteArray(12).also(data::get)
        val key = derive(password, salt, rounds)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(header)
            try { cipher.doFinal(file, headerSize, file.size - headerSize) }
            catch (_: BadPaddingException) { throw IllegalArgumentException("암호가 다르거나 백업 파일이 손상됐어요") }
        } finally { key.encoded.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray, rounds: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, rounds, 256)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            try { SecretKeySpec(bytes, "AES") } finally { bytes.fill(0) }
        } finally { spec.clearPassword() }
    }
}
