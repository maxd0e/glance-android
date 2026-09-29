package app.glance.wallet.core.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DuressProvisioningTransactionTest {
    @Test
    fun `failed provisioning rolls back the new decoy store`() = runBlocking {
        val events = mutableListOf<String>()

        assertFailure<IllegalStateException> {
            provisionDecoyAtomically(
                provision = {
                    events += "create"
                    throw IllegalStateException("preference persistence failed")
                },
                rollback = { events += "delete" },
            )
        }

        assertEquals(listOf("create", "delete"), events)
    }

    @Test
    fun `cancelled provisioning rolls back the new decoy store`() = runBlocking {
        val events = mutableListOf<String>()

        assertFailure<CancellationException> {
            provisionDecoyAtomically(
                provision = {
                    events += "create"
                    throw CancellationException("setup interrupted")
                },
                rollback = { events += "delete" },
            )
        }

        assertEquals(listOf("create", "delete"), events)
    }

    @Test
    fun `successful provisioning retains the new decoy store`() = runBlocking {
        val events = mutableListOf<String>()

        provisionDecoyAtomically(
            provision = { events += "create" },
            rollback = { events += "delete" },
        )

        assertEquals(listOf("create"), events)
    }

    @Test
    fun `failed decoy database deletion retains its key material for retry`() {
        var databasePresent = true
        var keyDeleted = false

        assertFailure<IllegalStateException> {
            deleteProfileStorage(
                databaseExists = { databasePresent },
                deleteDatabase = { false },
                deleteKeyMaterial = { keyDeleted = true },
            )
        }

        assertTrue(databasePresent)
        assertFalse(keyDeleted)
    }

    @Test
    fun `successful decoy deletion removes database before its key material`() {
        var databasePresent = true
        val events = mutableListOf<String>()

        deleteProfileStorage(
            databaseExists = { databasePresent },
            deleteDatabase = {
                events += "database"
                databasePresent = false
                true
            },
            deleteKeyMaterial = { events += "key" },
        )

        assertEquals(listOf("database", "key"), events)
        assertFalse(databasePresent)
    }

    private inline fun <reified T : Throwable> assertFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }
}
