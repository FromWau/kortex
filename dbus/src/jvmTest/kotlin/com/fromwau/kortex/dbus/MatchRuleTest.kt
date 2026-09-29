package com.fromwau.kortex.dbus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a rule tells the bus, and which signals it takes back out of what the bus routes. */
class MatchRuleTest {
    @Test
    fun `a rule names the type even when it constrains nothing else`() {
        assertEquals("type='signal'", MatchRule().asExpression)
    }

    @Test
    fun `every field a rule sets reaches the expression under the name the bus uses`() {
        val rule = MatchRule(
            sender = "org.kde.StatusNotifierWatcher",
            iface = "org.kde.StatusNotifierWatcher",
            member = "StatusNotifierItemRegistered",
            path = "/StatusNotifierWatcher",
        )

        assertEquals(
            "type='signal',sender='org.kde.StatusNotifierWatcher'," +
                "interface='org.kde.StatusNotifierWatcher'," +
                "member='StatusNotifierItemRegistered',path='/StatusNotifierWatcher'",
            rule.asExpression,
            "the key for an interface is 'interface', which is not what the property is called in Kotlin",
        )
    }

    /** A value is single-quoted, and a single-quoted run is the one thing that cannot hold a quote. */
    @Test
    fun `a quote inside a value closes the run and is escaped outside it`() {
        assertEquals("type='signal',member='it'\\''s'", MatchRule(member = "it's").asExpression)
    }

    @Test
    fun `a rule takes a signal that satisfies every field it set`() {
        val rule = MatchRule(iface = ITEM, member = "NewStatus")

        assertTrue(rule.matches(signal(iface = ITEM, member = "NewStatus")))
        assertFalse(rule.matches(signal(iface = ITEM, member = "NewIcon")))
        assertFalse(rule.matches(signal(iface = "org.kde.Other", member = "NewStatus")))
    }

    @Test
    fun `a field a rule left unset matches anything`() {
        assertTrue(MatchRule().matches(signal(iface = ITEM, member = "NewIcon")))
        assertTrue(MatchRule(member = "NewIcon").matches(signal(iface = "anything", member = "NewIcon")))
    }

    @Test
    fun `a sender is matched against the unique name the bus filled in`() {
        val rule = MatchRule(sender = ":1.31")

        assertTrue(rule.matches(signal(iface = ITEM, member = "NewIcon", sender = ":1.31")))
        assertFalse(rule.matches(signal(iface = ITEM, member = "NewIcon", sender = ":1.32")))
        assertFalse(rule.matches(signal(iface = ITEM, member = "NewIcon", sender = null)))
    }

    /**
     * A namespace ends at a separator.
     *
     * Without that, `path_namespace='/org/ayatana'` would also take `/org/ayatanaOther`, which is a
     * different application's object and not a child of anything.
     */
    @Test
    fun `a path namespace matches itself and its children but not a longer name`() {
        val rule = MatchRule(pathNamespace = "/org/ayatana")

        assertTrue(rule.matches(signal(path = "/org/ayatana")))
        assertTrue(rule.matches(signal(path = "/org/ayatana/item/1")))
        assertFalse(rule.matches(signal(path = "/org/ayatanaOther")))
        assertFalse(rule.matches(signal(path = "/org/other")))
    }

    @Test
    fun `the root namespace matches every path there is`() {
        val rule = MatchRule(pathNamespace = "/")

        assertTrue(rule.matches(signal(path = "/")))
        assertTrue(rule.matches(signal(path = "/StatusNotifierWatcher")))
        assertEquals("type='signal',path_namespace='/'", rule.asExpression)
    }

    private fun signal(
        path: String = "/StatusNotifierItem",
        iface: String = ITEM,
        member: String = "NewIcon",
        sender: String? = ":1.31",
    ) = Message.Signal(serial = 1u, path = path, iface = iface, member = member, sender = sender)

    private companion object {
        const val ITEM = "org.kde.StatusNotifierItem"
    }
}
