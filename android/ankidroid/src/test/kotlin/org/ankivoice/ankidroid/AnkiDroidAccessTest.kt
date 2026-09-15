package org.ankivoice.ankidroid

import java.util.concurrent.Executor
import org.ankivoice.core.contracts.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AnkiDroidAccessTest {
    private class Tasks : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun run() { pending.removeFirst().run() }
    }
    private class Resolver : AccessPlatform {
        var available = true
        var api: Boolean? = true
        var database = true
        var microphone = true
        var decks: List<Deck>? = listOf(Deck(7, "Synthetic"))
        var selected: List<Deck>? = emptyList()
        var count = 1
        var queryError: RuntimeException? = null
        var updateError: RuntimeException? = null
        var duringQuery: (() -> Unit)? = null
        var duringUpdate: (() -> Unit)? = null
        val calls = mutableListOf<String>()
        override fun packageAvailable() = available
        override fun apiEnabled() = api
        override fun databasePermissionGranted() = database
        override fun microphonePermissionGranted() = microphone
        override fun queryDecks(): List<Deck>? {
            calls += "decks"
            duringQuery?.invoke()
            queryError?.let { throw it }
            return decks
        }
        override fun querySelectedDeck(): List<Deck>? { calls += "selected_deck:query"; return selected }
        override fun updateSelectedDeck(deckId: Long): Int {
            calls += "selected_deck:update:$deckId"
            updateError?.let { throw it }
            if (count == 1) selected = decks?.filter { it.id == deckId }
            duringUpdate?.invoke()
            return count
        }
    }
    private val resolver = Resolver()
    private val worker = Tasks()
    private val main = Tasks()
    private val access = AnkiDroidAccess(resolver, worker, main)

    private fun inspect(id: Long? = null): AccessSnapshot {
        var result: AccessSnapshot? = null
        access.inspect(id) { result = it }
        worker.run()
        main.run()
        return requireNotNull(result)
    }
    private fun select(id: Long = 7): AccessSnapshot {
        var result: AccessSnapshot? = null
        access.select(id) { result = it }
        worker.run()
        main.run()
        return requireNotNull(result)
    }

    @Test fun `resolver calls run on worker and callbacks wait for main delivery`() {
        var result: AccessSnapshot? = null
        access.inspect(null) { result = it }
        assertTrue(resolver.calls.isEmpty())
        assertNull(result)
        worker.run()
        assertEquals(listOf("decks"), resolver.calls)
        assertNull(result)
        main.run()
        assertEquals(resolver.decks, result?.decks)
    }

    @Test fun `missing or disabled package wins before any resolver call`() {
        resolver.available = false
        assertEquals(CardProviderFailure.PACKAGE_UNAVAILABLE, inspect().failure?.mode)
        assertTrue(resolver.calls.isEmpty())
    }
    @Test fun `disabled component identifies API disabled`() {
        resolver.api = false
        assertEquals(CardProviderFailure.API_DISABLED, inspect().failure?.mode)
        assertTrue(resolver.calls.isEmpty())
    }
    @Test fun `never granted and revoked database permission deny access`() {
        resolver.database = false
        assertEquals(CardProviderFailure.ACCESS_DENIED, inspect().failure?.mode)
        assertTrue(resolver.calls.isEmpty())
    }
    @Test fun `permission revoked between preflight and query is denied`() {
        resolver.queryError = SecurityException()
        assertEquals(CardProviderFailure.ACCESS_DENIED, inspect().failure?.mode)
    }
    @Test fun `denied microphone has its own speech failure`() {
        resolver.microphone = false
        assertEquals(SpeechInputFailure.PERMISSION_DENIED, inspect().failure?.mode)
    }
    @Test fun `null with unknown cause never becomes an empty result`() {
        resolver.decks = null
        for (api in listOf(true, null)) {
            resolver.api = api
            assertEquals(CardProviderFailure.NULL_CURSOR, inspect().failure?.mode)
        }
    }
    @Test fun `null rechecks a component disabled during query`() {
        resolver.decks = null
        resolver.duringQuery = { resolver.api = false }
        assertEquals(CardProviderFailure.API_DISABLED, inspect().failure?.mode)
    }
    @Test fun `valid empty cursor is successful access`() {
        resolver.decks = emptyList()
        assertEquals(AccessSnapshot(), inspect())
    }
    @Test fun `missing selected deck is not empty or exhausted`() {
        resolver.decks = emptyList()
        assertEquals(CardProviderFailure.DECK_MISSING, inspect(7).failure?.mode)
    }
    @Test fun `unexpected provider failure stays unknown`() {
        resolver.queryError = IllegalStateException("provider died")
        assertEquals(CardProviderFailure.NULL_CURSOR, inspect().failure?.mode)
    }
    @Test fun `selection only writes selected deck and reads it back`() {
        assertNull(select().failure)
        assertEquals(listOf("decks", "selected_deck:update:7", "decks", "selected_deck:query"), resolver.calls)
        assertEquals(listOf(Deck(7, "Synthetic")), resolver.selected)
    }
    @Test fun `missing deck never causes selection write`() {
        assertEquals(CardProviderFailure.DECK_MISSING, select(99).failure?.mode)
        assertEquals(listOf("decks"), resolver.calls)
    }
    @Test fun `unconfirmed update is not successful selection`() {
        resolver.count = 0
        assertEquals(CardProviderFailure.NULL_CURSOR, select().failure?.mode)
    }
    @Test fun `null selection readback is unavailable`() {
        resolver.duringUpdate = { resolver.selected = null }
        assertEquals(CardProviderFailure.NULL_CURSOR, select().failure?.mode)
    }
    @Test fun `deck removed during selection is missing`() {
        resolver.duringUpdate = { resolver.decks = emptyList() }
        assertEquals(CardProviderFailure.DECK_MISSING, select().failure?.mode)
    }
    @Test fun `permission revoked during selection is denied without retry`() {
        resolver.updateError = SecurityException()
        assertEquals(CardProviderFailure.ACCESS_DENIED, select().failure?.mode)
        assertEquals(listOf("decks", "selected_deck:update:7"), resolver.calls)
    }
}
