package com.vayunmathur.games.chess.network

import com.vayunmathur.games.chess.data.PieceColor
import com.vayunmathur.games.chess.data.PieceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

class LichessModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // -- UCI parse/serialize --

    @Test
    fun parseSimpleMove() {
        val move = parseUci("e2e4")
        assertEquals(6 to 4, move.from.row to move.from.col)
        assertEquals(4 to 4, move.to.row to move.to.col)
        assertNull(move.promotion)
    }

    @Test
    fun parsePromotion() {
        val move = parseUci("e7e8q")
        assertEquals(1 to 4, move.from.row to move.from.col)
        assertEquals(0 to 4, move.to.row to move.to.col)
        assertEquals(PieceType.QUEEN, move.promotion)
    }

    @Test
    fun parseAllPromotions() {
        assertEquals(PieceType.QUEEN, parseUci("a7a8q").promotion)
        assertEquals(PieceType.ROOK, parseUci("a7a8r").promotion)
        assertEquals(PieceType.BISHOP, parseUci("a7a8b").promotion)
        assertEquals(PieceType.KNIGHT, parseUci("a7a8n").promotion)
    }

    @Test
    fun parseCastling() {
        val kingside = parseUci("e1g1")
        assertEquals(7 to 4, kingside.from.row to kingside.from.col)
        assertEquals(7 to 6, kingside.to.row to kingside.to.col)
        val queenside = parseUci("e8c8")
        assertEquals(0 to 4, queenside.from.row to queenside.from.col)
        assertEquals(0 to 2, queenside.to.row to queenside.to.col)
    }

    @Test
    fun parseRejectsMalformed() {
        assertFailsWith<IllegalArgumentException> { parseUci("e2") }
        assertFailsWith<IllegalArgumentException> { parseUci("e2e4qr") }
        assertFailsWith<IllegalArgumentException> { parseUci("e7e8x") }
    }

    @Test
    fun uciRoundTrip() {
        listOf("e2e4", "e7e8q", "e1g1", "e8c8", "g7g8n").forEach {
            assertEquals(it, parseUci(it).toString())
        }
    }

    @Test
    fun splitMovesEmpty() {
        assertEquals(emptyList(), splitUciMoves(""))
        assertEquals(emptyList(), splitUciMoves("   "))
    }

    @Test
    fun splitMovesSequence() {
        assertEquals(
            listOf("e2e4", "e7e5", "g1f3"),
            splitUciMoves("e2e4 e7e5 g1f3"),
        )
    }

    @Test
    fun colorMapping() {
        assertEquals(PieceColor.WHITE, colorFromLichess("white"))
        assertEquals(PieceColor.BLACK, colorFromLichess("black"))
        assertFailsWith<IllegalArgumentException> { colorFromLichess("red") }
    }

    // -- NDJSON event parsing --

    @Test
    fun gameStartEvent() {
        val envelope = json.decodeFromString(
            LichessEventEnvelope.serializer(),
            """{"type":"gameStart","game":{"gameId":"abc123","color":"white","opponent":{"username":"bob"},"isMyTurn":true}}""",
        )
        val event = envelope.toEvent()
        assertIs<LichessEvent.GameStart>(event)
        assertEquals("abc123", event.gameId)
        assertEquals(PieceColor.WHITE, event.color)
        assertEquals("bob", event.opponent)
    }

    @Test
    fun gameFinishEvent() {
        val envelope = json.decodeFromString(
            LichessEventEnvelope.serializer(),
            """{"type":"gameFinish","game":{"gameId":"abc123"}}""",
        )
        val event = envelope.toEvent()
        assertIs<LichessEvent.GameFinish>(event)
        assertEquals("abc123", event.gameId)
    }

    @Test
    fun unknownEventIgnored() {
        val envelope = json.decodeFromString(
            LichessEventEnvelope.serializer(),
            """{"type":"futureKind","whatever":42}""",
        )
        assertIs<LichessEvent.Ignored>(envelope.toEvent())
    }

    // -- NDJSON game-stream parsing --

    @Test
    fun gameFullDecodesWithState() {
        val update = decodeGameLine(
            json.parseToJsonElement(
                """{"type":"gameFull","id":"g1","rated":false,"variant":{"key":"standard"},"clock":{"initial":180000,"increment":2000},"white":{"id":"u1","name":"alice"},"black":{"id":"u2","name":"bob"},"initialFen":"startpos","state":{"type":"gameState","moves":"e2e4 e7e5","wtime":179000,"btime":180000,"status":"started"}}""",
            ),
            json,
        )
        assertIs<LichessGameUpdate.Full>(update)
        assertEquals("g1", update.game.id)
        assertEquals("e2e4 e7e5", update.game.state.moves)
        assertEquals(PieceColor.BLACK, update.game.myColor("u2"))
        assertEquals(PieceColor.WHITE, update.game.myColor("u1"))
    }

    @Test
    fun gameStateTerminalStatuses() {
        listOf("mate", "resign", "draw", "outoftime", "aborted", "stalemate", "timeout", "cheat")
            .forEach { status ->
                val update = decodeGameLine(
                    json.parseToJsonElement(
                        """{"type":"gameState","moves":"","wtime":0,"btime":0,"status":"$status"}""",
                    ),
                    json,
                )
                assertIs<LichessGameUpdate.State>(update)
                assertEquals(true, update.state.isTerminal, "status=$status")
            }
        val live = decodeGameLine(
            json.parseToJsonElement(
                """{"type":"gameState","moves":"e2e4","wtime":1,"btime":1,"status":"started"}""",
            ),
            json,
        )
        assertIs<LichessGameUpdate.State>(live)
        assertEquals(false, live.state.isTerminal)
    }

    @Test
    fun unknownGameLineIgnored() {
        assertIs<LichessGameUpdate.Ignored>(
            decodeGameLine(json.parseToJsonElement("""{"type":"chatLine","text":"gl"}"""), json)
        )
    }

    @Test
    fun timeControlLabels() {
        assertEquals("3+2", LichessTimeControl.BLITZ_3_2.label)
        assertEquals(4, LichessTimeControl.PRESETS.size)
    }
}
