package com.fromwau.kortex.wayland

import java.lang.foreign.MemorySegment
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * That a request sent on a proxy that is not there fails here rather than in native code.
 *
 * `wl_proxy_marshal_flags` reads the proxy's interface before anything else, so a null one is a SIGSEGV
 * inside libwayland that takes the whole process with it and names nobody: `wayland/hs_err_pid1136614.log`
 * and `wayland/hs_err_pid1143251.log` are two of those, both `wl_data_source.destroy` on a source the
 * clipboard was left holding after an assertion failed. The test that left it there has been fixed; this
 * pins what happens the next time anything hands [LibWayland.marshal] a proxy it never got.
 */
class NullProxyRequestTest {
    @Test
    fun `a request on a proxy that is not there fails rather than killing the process`() {
        assertFailsWith<IllegalArgumentException> {
            LibWayland.marshal(MemorySegment.NULL, OPCODE)
        }
    }

    private companion object {
        // Any opcode: the guard runs before the opcode means anything, since reading its since needs a proxy.
        const val OPCODE = 0
    }
}
