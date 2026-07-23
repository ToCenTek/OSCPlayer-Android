package com.oscvideoplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class FusionMesh {
    companion object {
        private const val TAG = "FusionMesh"
        private const val MAX_SUBDIV = 6
    }

    data class Point(var x: Float, var y: Float)
    data class Handle(var x: Float, var y: Float)

    // Handle directions
    enum class Dir { RIGHT, LEFT, DOWN, UP }

    var points: Array<Array<Point>> = arrayOf(
        arrayOf(Point(0f, 0f), Point(1f, 0f)),
        arrayOf(Point(0f, 1f), Point(1f, 1f))
    )
        private set
    // Handles[r][c][dir.ordinal]
    private var handleData: Array<Array<Array<Handle?>>> = emptyArray()

    var cols: Int = 2
        private set
    var rows: Int = 2
        private set
    var subdivX: Int = 0
        private set
    var subdivY: Int = 0
        private set

    init { rebuildHandles() }

    fun setSubdiv(sx: Int, sy: Int) {
        val nsx = sx.coerceIn(0, MAX_SUBDIV)
        val nsy = sy.coerceIn(0, MAX_SUBDIV)
        val newCols = (1 shl nsx) + 1
        val newRows = (1 shl nsy) + 1
        resize(newCols, newRows)
        subdivX = nsx; subdivY = nsy
    }

    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceIn(2, (1 shl MAX_SUBDIV) + 1)
        val nr = newRows.coerceIn(2, (1 shl MAX_SUBDIV) + 1)
        if (nc == cols && nr == rows) return
        val old = points; val oc = cols; val or = rows
        cols = nc; rows = nr
        points = Array(rows) { r -> Array(cols) { c ->
            val u = if (oc > 1) c.toFloat() / (cols - 1) * (oc - 1) else 0f
            val v = if (or > 1) r.toFloat() / (rows - 1) * (or - 1) else 0f
            val ci = u.toInt().coerceIn(0, oc - 2); val ri = v.toInt().coerceIn(0, or - 2)
            Point(
                lerp(lerp(old[ri][ci].x, old[ri][(ci + 1).coerceAtMost(oc - 1)].x, u - ci),
                     lerp(old[(ri + 1).coerceAtMost(or - 1)][ci].x, old[(ri + 1).coerceAtMost(or - 1)][(ci + 1).coerceAtMost(oc - 1)].x, u - ci), v - ri),
                lerp(lerp(old[ri][ci].y, old[ri][(ci + 1).coerceAtMost(oc - 1)].y, u - ci),
                     lerp(old[(ri + 1).coerceAtMost(or - 1)][ci].y, old[(ri + 1).coerceAtMost(or - 1)][(ci + 1).coerceAtMost(oc - 1)].y, u - ci), v - ri)
            )
        }}
        rebuildHandles()
    }

    fun reset() {
        subdivX = 0; subdivY = 0; cols = 2; rows = 2
        points = arrayOf(
            arrayOf(Point(0f, 0f), Point(1f, 0f)),
            arrayOf(Point(0f, 1f), Point(1f, 1f))
        )
        rebuildHandles()
    }

    fun setPoint(row: Int, col: Int, x: Float, y: Float) {
        if (row in 0 until rows && col in 0 until cols) {
            val old = points[row][col]
            val dx = x - old.x; val dy = y - old.y
            points[row][col] = Point(x, y)
            // Move handles with point (preserve relative offset)
            for (d in Dir.values()) {
                val h = handleData.getOrNull(row)?.getOrNull(col)?.getOrNull(d.ordinal) ?: continue
                h.x += dx; h.y += dy
            }
        }
    }

    fun getPoint(row: Int, col: Int): Pair<Float, Float>? =
        if (row in 0 until rows && col in 0 until cols) points[row][col].x to points[row][col].y else null

    fun getHandle(row: Int, col: Int, dir: Dir): Handle? =
        if (row in 0 until rows && col in 0 until cols) handleData.getOrNull(row)?.getOrNull(col)?.getOrNull(dir.ordinal) else null

    fun setHandle(row: Int, col: Int, dir: Dir, x: Float, y: Float) {
        if (row in 0 until rows && col in 0 until cols) {
            val h = handleData.getOrNull(row)?.getOrNull(col)?.getOrNull(dir.ordinal) ?: return
            h.x = x; h.y = y
        }
    }

    private fun rebuildHandles() {
        handleData = Array(rows) { r -> Array(cols) { c ->
            val dirs = mutableListOf<Handle?>()
            for (d in Dir.values()) {
                dirs.add(if (hasHandle(r, c, d)) Handle(0f, 0f) else null)
            }
            dirs.toTypedArray()
        }}
        // Auto-set handle positions to 1/3 of segment length (smooth default)
        for (r in 0 until rows) for (c in 0 until cols) autoHandles(r, c)
    }

    private fun autoHandles(r: Int, c: Int) {
        val p = points[r][c]
        // Right handle: 1/3 toward right neighbor
        if (hasHandle(r, c, Dir.RIGHT) && c + 1 < cols) {
            val nr = points[r][c + 1]; val h = handleData[r][c][Dir.RIGHT.ordinal]!!
            h.x = p.x + (nr.x - p.x) / 3f; h.y = p.y + (nr.y - p.y) / 3f
        }
        // Left handle: 1/3 toward left neighbor
        if (hasHandle(r, c, Dir.LEFT) && c - 1 >= 0) {
            val nl = points[r][c - 1]; val h = handleData[r][c][Dir.LEFT.ordinal]!!
            h.x = p.x + (nl.x - p.x) / 3f; h.y = p.y + (nl.y - p.y) / 3f
        }
        // Down handle: 1/3 toward bottom neighbor
        if (hasHandle(r, c, Dir.DOWN) && r + 1 < rows) {
            val nd = points[r + 1][c]; val h = handleData[r][c][Dir.DOWN.ordinal]!!
            h.x = p.x + (nd.x - p.x) / 3f; h.y = p.y + (nd.y - p.y) / 3f
        }
        // Up handle: 1/3 toward top neighbor
        if (hasHandle(r, c, Dir.UP) && r - 1 >= 0) {
            val nu = points[r - 1][c]; val h = handleData[r][c][Dir.UP.ordinal]!!
            h.x = p.x + (nu.x - p.x) / 3f; h.y = p.y + (nu.y - p.y) / 3f
        }
    }

    private fun hasHandle(r: Int, c: Int, dir: Dir): Boolean = when (dir) {
        Dir.RIGHT -> c < cols - 1
        Dir.LEFT -> c > 0
        Dir.DOWN -> r < rows - 1
        Dir.UP -> r > 0
    }

    // Evaluate cubic bezier: B(t) = (1-t)³·P0 + 3(1-t)²·t·P1 + 3(1-t)·t²·P2 + t³·P3
    fun bezierX(r: Int, c: Int, dir: Dir, t: Float, useX: Boolean): Float {
        val p0 = points[r][c]
        val p3 = when (dir) {
            Dir.RIGHT -> points[r][c + 1]; Dir.LEFT -> points[r][c - 1]
            Dir.DOWN -> points[r + 1][c]; Dir.UP -> points[r - 1][c]
        }
        val h0 = handleData[r][c][dir.ordinal] ?: return if (useX) lerp(p0.x, p3.x, t) else lerp(p0.y, p3.y, t)
        val opposite = when (dir) {
            Dir.RIGHT -> Dir.LEFT; Dir.LEFT -> Dir.RIGHT
            Dir.DOWN -> Dir.UP; Dir.UP -> Dir.DOWN
        }
        val oppRow = when (dir) { Dir.RIGHT -> r; Dir.LEFT -> r; Dir.DOWN -> r + 1; Dir.UP -> r - 1 }
        val oppCol = when (dir) { Dir.RIGHT -> c + 1; Dir.LEFT -> c - 1; Dir.DOWN -> c; Dir.UP -> c }
        val h1 = if (oppRow in 0 until rows && oppCol in 0 until cols)
            handleData[oppRow][oppCol][opposite.ordinal] else null
        val p1 = h0; val p2 = h1 ?: return if (useX) lerp(p0.x, p3.x, t) else lerp(p0.y, p3.y, t)
        val a = if (useX) p0.x else p0.y
        val b = if (useX) p1.x else p1.y
        val c_ = if (useX) p2.x else p2.y
        val d = if (useX) p3.x else p3.y
        val u = 1f - t
        return u * u * u * a + 3f * u * u * t * b + 3f * u * t * t * c_ + t * t * t * d
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun regularize(iterations: Int = 50, lambda: Float = 0.5f) {
        for (iter in 0 until iterations) {
            for (r in 1 until rows - 1) for (c in 1 until cols - 1) {
                val p = points[r][c]
                p.x += ((points[r - 1][c].x + points[r + 1][c].x + points[r][c - 1].x + points[r][c + 1].x) / 4f - p.x) * lambda
                p.y += ((points[r - 1][c].y + points[r + 1][c].y + points[r][c - 1].y + points[r][c + 1].y) / 4f - p.y) * lambda
            }
        }
        for (r in 0 until rows) for (c in 0 until cols) autoHandles(r, c)
        Log.d(TAG, "Regularized ${iterations} iterations")
    }

    fun toJson(): JSONObject {
        val ptsArr = JSONArray()
        for (r in 0 until rows) {
            val rowArr = JSONArray()
            for (c in 0 until cols) {
                val obj = JSONObject()
                obj.put("x", points[r][c].x.toDouble())
                obj.put("y", points[r][c].y.toDouble())
                // Handles
                val hArr = JSONArray()
                for (d in Dir.values()) {
                    val h = handleData[r][c][d.ordinal]
                    if (h != null) {
                        val hObj = JSONObject()
                        hObj.put("x", h.x.toDouble()); hObj.put("y", h.y.toDouble())
                        hArr.put(hObj)
                    } else hArr.put(JSONObject.NULL)
                }
                obj.put("h", hArr)
                rowArr.put(obj)
            }
            ptsArr.put(rowArr)
        }
        val root = JSONObject()
        root.put("cols", cols); root.put("rows", rows)
        root.put("subdivX", subdivX); root.put("subdivY", subdivY)
        root.put("points", ptsArr)
        return root
    }

    fun fromJson(json: JSONObject) {
        cols = json.optInt("cols", 2)
        rows = json.optInt("rows", 2)
        subdivX = json.optInt("subdivX", 0)
        subdivY = json.optInt("subdivY", 0)
        val arr = json.optJSONArray("points") ?: return
        points = Array(rows) { r -> Array(cols) { c ->
            if (r < arr.length()) {
                val rowArr = arr.optJSONArray(r)
                if (rowArr != null && c < rowArr.length()) {
                    val obj = rowArr.optJSONObject(c)
                    if (obj != null) Point(
                        obj.optDouble("x", 0.0).toFloat(),
                        obj.optDouble("y", 0.0).toFloat()
                    ) else Point(0f, 0f)
                } else Point(0f, 0f)
            } else Point(0f, 0f)
        }}
        rebuildHandles()
        // Restore handles from JSON
        for (r in 0 until rows) {
            val rowArr = arr.optJSONArray(r) ?: continue
            for (c in 0 until cols) {
                val obj = rowArr.optJSONObject(c) ?: continue
                val hArr = obj.optJSONArray("h") ?: continue
                for (di in 0 until minOf(hArr.length(), Dir.values().size)) {
                    val hObj = hArr.optJSONObject(di) ?: continue
                    val h = handleData.getOrNull(r)?.getOrNull(c)?.getOrNull(di) ?: continue
                    h.x = hObj.optDouble("x", 0.0).toFloat()
                    h.y = hObj.optDouble("y", 0.0).toFloat()
                }
            }
        }
    }

    fun colAt(r: Int, c: Int): Float {
        val rr = r.coerceIn(0, rows - 1)
        return points[rr][c.coerceIn(0, cols - 1)].x
    }
    fun rowAt(r: Int, c: Int): Float {
        val rr = r.coerceIn(0, rows - 1)
        return points[rr][c.coerceIn(0, cols - 1)].y
    }
}
