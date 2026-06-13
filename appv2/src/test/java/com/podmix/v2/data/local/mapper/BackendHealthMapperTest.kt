package com.podmix.v2.data.local.mapper

import com.podmix.v2.data.remote.dto.BackendHealthDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendHealthMapperTest {
    private val mapper = BackendHealthMapper()

    @Test
    fun `fromDto maps ok status to reachable entity`() {
        val result = mapper.fromDto(
            dto = BackendHealthDto(status = "ok", message = "alive"),
            checkedAtEpochMillis = 123L,
            endpoint = "https://podmix.mb4.fr"
        )

        assertEquals("ok", result.status)
        assertEquals("https://podmix.mb4.fr", result.endpoint)
        assertEquals(123L, result.checkedAtEpochMillis)
        assertTrue(result.isReachable)
        assertEquals("alive", result.message)
    }

    @Test
    fun `toDomain preserves offline fallback state`() {
        val result = mapper.toDomain(
            entity = com.podmix.v2.data.local.entity.BackendHealthEntity(
                endpoint = "https://podmix.mb4.fr",
                status = "offline",
                checkedAtEpochMillis = 456L,
                isReachable = false,
                message = "network down"
            )
        )

        assertEquals("offline", result.status)
        assertFalse(result.isReachable)
        assertEquals("network down", result.message)
    }
}
