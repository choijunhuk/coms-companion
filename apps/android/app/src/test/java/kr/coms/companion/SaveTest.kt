package kr.coms.companion

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SaveTest {
    @Test fun failureKeepsFormOpenAndSuccessClosesIt() = runBlocking {
        var closes = 0
        var error = ""
        commitSave({ throw IllegalStateException("disk full") }, { closes++ }, { error = it })
        assertEquals(0, closes)
        assertEquals("disk full", error)
        commitSave({ }, { closes++ }, { error = it })
        assertEquals(1, closes)
    }

    @Test fun cancellationIsNotReportedAsSaveFailure() {
        var errors = 0
        assertThrows(CancellationException::class.java) {
            runBlocking { commitSave({ throw CancellationException("leave") }, { error("should not close") }, { errors++ }) }
        }
        assertEquals(0, errors)
    }
}
