package kr.coms.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    private val password = "correct horse battery".toCharArray()
    private val plaintext = "private members and invite links".toByteArray()

    @Test fun roundTripUsesFreshRandomness() {
        val first = BackupCrypto.encrypt(plaintext, password)
        val second = BackupCrypto.encrypt(plaintext, password)
        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, BackupCrypto.decrypt(first, password))
        assertArrayEquals(plaintext, BackupCrypto.decrypt(second, password))
    }

    @Test fun wrongPasswordAndTamperingFail() {
        val file = BackupCrypto.encrypt(plaintext, password)
        assertEquals("암호가 다르거나 백업 파일이 손상됐어요", assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(file, "wrong password".toCharArray())
        }.message)
        file[20] = (file[20].toInt() xor 1).toByte()
        assertEquals("암호가 다르거나 백업 파일이 손상됐어요", assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(file, password)
        }.message)
    }

    @Test fun rejectsTruncationUnsupportedVersionAndExcessiveKdfBeforeDecrypt() {
        val file = BackupCrypto.encrypt(plaintext, password)
        assertEquals("백업 파일 크기가 잘못됐어요", assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(file.copyOf(30), password)
        }.message)
        val unsupported = file.copyOf().also { it[11] = 2 }
        assertEquals("지원하지 않는 백업 버전이에요", assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(unsupported, password)
        }.message)
        val excessive = file.copyOf().also { it[12] = 0x7f }
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.decrypt(excessive, password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.encrypt(plaintext, "short".toCharArray()) }
    }
}
