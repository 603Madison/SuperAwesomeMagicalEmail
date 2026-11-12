package com.email.app

import android.net.Uri
import com.email.api.Address
import com.email.app.ui.parseAddress
import com.email.app.ui.shortDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnitsTest {
    @Test
    fun `mail links from other apps become messages`() {
        val (to, subject, body) = MainActivity.mailto(Uri.parse("mailto:jo@example.com,sam@example.com?subject=Hello%20there&body=Line%201%0ALine%202&cc=x@example.com"))
        assertEquals(listOf(Address("jo@example.com"), Address("sam@example.com")), to)
        assertEquals("Hello there", subject)
        assertEquals("Line 1\nLine 2", body)
        assertEquals(listOf(Address("a@example.com")), MainActivity.mailto(Uri.parse("mailto:?to=a@example.com")).first)
    }

    @Test
    fun `typed addresses are understood`() {
        assertEquals(Address("jo@example.com", "Grandma Jo"), parseAddress("Grandma Jo <jo@example.com>"))
        assertEquals(Address("jo@example.com", "Grandma Jo"), parseAddress("\"Grandma Jo\" <jo@example.com>,"))
        assertEquals(Address("jo@example.com"), parseAddress(" jo@example.com "))
        assertNull(parseAddress("grandma"))
    }

    @Test
    fun `dates in the list are short`() {
        val zone = ZoneId.of("UTC")
        val today = LocalDate.of(2026, 11, 12)
        fun at(date: LocalDate, hour: Int) = date.atTime(hour, 5).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Mon", shortDate(at(today.minusDays(3), 9), zone, today))
        assertEquals("3 Aug", shortDate(at(LocalDate.of(2026, 8, 3), 9), zone, today))
        assertEquals("", shortDate(0, zone, today))
    }
}
