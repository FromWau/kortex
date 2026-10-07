package com.fromwau.kortex.bar.system

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProcfsTest {
    @Test
    fun `cpu times come off the aggregate line and leave idle out of the busy total`() {
        val times = parseCpuTimes(STAT).getOrNull()

        // user 10 + nice 2 + system 5 + irq 1 + softirq 1 + steal 0 = 19 busy, idle 100 + iowait 3 = 103.
        assertEquals(CpuTimes(busy = 19, total = 122), times)
    }

    @Test
    fun `a load is the share of the elapsed jiffies that was busy`() {
        val earlier = CpuTimes(busy = 100, total = 1000)
        val later = CpuTimes(busy = 125, total = 1100)

        assertEquals(0.25f, CpuTimes.load(earlier, later).getOrNull())
    }

    @Test
    fun `counters that went backwards are out of range rather than a negative load`() {
        val earlier = CpuTimes(busy = 500, total = 1000)
        val later = CpuTimes(busy = 10, total = 20)

        CpuTimes.load(earlier, later).assertError<ParseFailure.OutOfRange>()
    }

    @Test
    fun `two readings taken at the same moment are out of range rather than a division by zero`() {
        val same = CpuTimes(busy = 100, total = 1000)

        CpuTimes.load(same, same).assertError<ParseFailure>()
    }

    @Test
    fun `a stat file without the aggregate line names the line it wanted`() {
        val failure = parseCpuTimes("cpu0 1 2 3 4\nintr 0\n").assertError<ParseFailure>()

        assertEquals(ParseFailure.MissingLine("cpu "), failure)
    }

    @Test
    fun `memory in use is what is not available, out of the machine's total`() {
        val memory = parseMemoryUse(MEMINFO).assertSuccess()

        assertEquals(MemoryUse(usedKib = 24_641_820, totalKib = 65_706_932), memory)
        assertTrue(memory.fraction in 0.37f..0.38f, "fraction was ${memory.fraction}")
    }

    @Test
    fun `meminfo without MemAvailable names the line it wanted`() {
        val failure = parseMemoryUse("MemTotal:  100 kB\n").assertError<ParseFailure>()

        assertEquals(ParseFailure.MissingLine("MemAvailable:"), failure)
    }

    @Test
    fun `network totals skip the loopback, whose traffic never left the machine`() {
        val totals = parseNetworkTotals(NETDEV).assertSuccess()

        // enp4s0's zeros plus wlan0's counters, with lo's 163402877 left out of both directions.
        assertEquals(NetworkTotals(receivedBytes = 96_005_768_839, sentBytes = 4_287_140_026), totals)
    }

    @Test
    fun `a netdev file with only a header has no interface to report`() {
        val header = NETDEV.lineSequence().take(2).joinToString("\n")

        parseNetworkTotals(header).assertError<ParseFailure>()
    }

    @Test
    fun `a rate is the difference over the interval, and a counter reset reads as nothing moving`() {
        val earlier = NetworkTotals(receivedBytes = 1_000, sentBytes = 2_000)
        val later = NetworkTotals(receivedBytes = 3_048, sentBytes = 2_000)

        assertEquals(
            NetworkRate(downBytesPerSecond = 1_024, upBytesPerSecond = 0),
            NetworkRate.between(earlier, later, seconds = 2.0),
        )
        assertEquals(
            NetworkRate(downBytesPerSecond = 0, upBytesPerSecond = 0),
            NetworkRate.between(later, earlier, seconds = 2.0),
        )
    }

    @Test
    fun `a rate over no time at all is nothing moving, not an infinity`() {
        val earlier = NetworkTotals(receivedBytes = 1_000, sentBytes = 2_000)
        val later = NetworkTotals(receivedBytes = 9_000, sentBytes = 2_000)

        assertEquals(
            NetworkRate(downBytesPerSecond = 0, upBytesPerSecond = 0),
            NetworkRate.between(earlier, later, seconds = 0.0),
        )
    }

    @Test
    fun `a rate stretches over the real gap, which is not always the polling interval`() {
        val earlier = NetworkTotals(receivedBytes = 0, sentBytes = 0)
        val later = NetworkTotals(receivedBytes = 60_000, sentBytes = 0)

        // The same 60 kB is 30 kB a second over two seconds and 1 kB a second over the minute that
        // :watch stays silent while an idle interface reports unchanged counters.
        assertEquals(30_000, NetworkRate.between(earlier, later, seconds = 2.0).downBytesPerSecond)
        assertEquals(1_000, NetworkRate.between(earlier, later, seconds = 60.0).downBytesPerSecond)
    }

    @Test
    fun `an hwmon input is thousandths of a degree`() {
        assertEquals(Celsius(54), parseMilliCelsius("54250\n").getOrNull())
    }

    @Test
    fun `an hwmon input that is not a number says which field it was`() {
        val failure = parseMilliCelsius("n/a").assertError<ParseFailure>()

        assertEquals(ParseFailure.NotANumber("temp_input", "n/a"), failure)
    }
}

private val STAT = """
    cpu  10 2 5 100 3 1 1 0 0 0
    cpu0 5 1 2 50 1 0 0 0 0 0
    intr 1234
""".trimIndent()

private val MEMINFO = """
    MemTotal:       65706932 kB
    MemFree:         5362388 kB
    MemAvailable:   41065112 kB
    Buffers:          196684 kB
""".trimIndent()

private val NETDEV = """
    Inter-|   Receive                                                |  Transmit
     face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
        lo: 163402877 1675418    0    0    0     0          0         0 163402877 1675418    0    0    0     0       0          0
    enp4s0:       0       0    0    0    0     0          0         0        0       0    0    0    0     0       0          0
     wlan0: 96005768839 66461533    0 69734    0     0          0         0 4287140026 31312406    0    7    0     0       0          0
""".trimIndent()
