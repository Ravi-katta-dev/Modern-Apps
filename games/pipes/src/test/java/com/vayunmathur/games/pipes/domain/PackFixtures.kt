package com.vayunmathur.games.pipes.domain

import com.vayunmathur.games.pipes.data.CellPos
import com.vayunmathur.games.pipes.data.EndpointPair
import com.vayunmathur.games.pipes.data.LevelData
import com.vayunmathur.games.pipes.data.RenderPos
import com.vayunmathur.games.pipes.data.computeAdjacency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads the shipped packs straight off disk. `LevelPack.init` needs a Context to reach the asset
 * manager, which a JVM unit test has no way to provide.
 */
object PackFixtures {

    private val packsDir = File("src/main/assets/packs")

    fun packNames(): List<String> = packsDir.listFiles()!!.map { it.name }.sorted()

    fun levels(packName: String): List<LevelData> {
        val obj = Json.parseToJsonElement(packsDir.resolve(packName).readText()).jsonObject
        return obj["levels"]!!.jsonArray.map { levelFrom(it.jsonObject) }
    }

    fun allLevels(): List<LevelData> = packNames().flatMap { levels(it) }

    private fun levelFrom(json: kotlinx.serialization.json.JsonObject): LevelData {
        val rows = json["rows"]!!.jsonPrimitive.int
        val cols = json["cols"]!!.jsonPrimitive.int
        val grid = json["grid"]?.jsonPrimitive?.content ?: "square"
        val cells = json["cells"]?.jsonArray?.map { cellFrom(it) }?.toSet()
            ?: buildSet { for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c)) }
        val adjacency = json["adjacency"]?.jsonObject?.map { (key, value) ->
            val parts = key.split(",")
            val cell = CellPos(parts[0].toInt(), parts[1].toInt())
            cell to value.jsonArray.map { cellFrom(it) }
        }?.toMap() ?: if (grid == "hex") {
            com.vayunmathur.games.pipes.data.computeHexAdjacency(cells)
        } else {
            computeAdjacency(cells)
        }
        val portals = json["portals"]?.jsonArray?.flatMap { pair ->
            val ends = pair.jsonArray.map { cellFrom(it) }
            if (ends.size == 2) listOf(ends[0] to ends[1], ends[1] to ends[0]) else emptyList()
        }?.toMap() ?: emptyMap()
        val extraEdges = json["extraEdges"]?.jsonArray?.flatMap { pair ->
            val ends = pair.jsonArray.map { cellFrom(it) }
            if (ends.size == 2) listOf(ends[0] to ends[1], ends[1] to ends[0]) else emptyList()
        } ?: emptyList()
        val fullAdjacency = if (portals.isEmpty() && extraEdges.isEmpty()) {
            adjacency
        } else {
            val merged = adjacency.mapValues { (_, neighbors) -> neighbors.toMutableSet() }.toMutableMap()
            for ((a, b) in portals) merged.getOrPut(a) { mutableSetOf() }.add(b)
            for ((a, b) in extraEdges) merged.getOrPut(a) { mutableSetOf() }.add(b)
            for (cell in cells) merged.putIfAbsent(cell, mutableSetOf())
            merged.mapValues { (_, neighbors) -> neighbors.toList() }
        }
        val renderPositions = json["renderPositions"]?.jsonObject?.map { (key, value) ->
            val parts = key.split(",")
            val cell = CellPos(parts[0].toInt(), parts[1].toInt())
            val posObj = value.jsonObject
            cell to RenderPos(posObj["x"]!!.jsonPrimitive.float, posObj["y"]!!.jsonPrimitive.float)
        }?.toMap() ?: if (grid == "hex") {
            com.vayunmathur.games.pipes.data.hexRenderPositions(cells)
        } else {
            null
        }
        val endpoints = json["endpoints"]!!.jsonArray.mapIndexed { index, element ->
            val ep = element.jsonObject
            EndpointPair(
                ep["color"]?.jsonPrimitive?.intOrNull ?: index,
                ep["cells"]!!.jsonArray.map { cellFrom(it) },
            )
        }
        val bridges = json["bridges"]?.jsonArray?.map { cellFrom(it) }?.toSet() ?: emptySet()
        return LevelData(
            id = json["id"]!!.jsonPrimitive.content,
            rows = rows,
            cols = cols,
            cells = cells,
            adjacency = fullAdjacency,
            renderPositions = renderPositions,
            endpoints = endpoints,
            bridges = bridges,
            optimalMoves = json["optimalMoves"]!!.jsonPrimitive.int,
            portals = portals,
            grid = grid,
        )
    }

    private fun cellFrom(element: kotlinx.serialization.json.JsonElement): CellPos {
        val pair = element.jsonArray
        return CellPos(pair[0].jsonPrimitive.int, pair[1].jsonPrimitive.int)
    }
}
