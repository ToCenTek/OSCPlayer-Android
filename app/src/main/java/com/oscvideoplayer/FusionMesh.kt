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

    var points: Array<Array<Point>> = arrayOf(
        arrayOf(Point(0f, 0f), Point(1f, 0f)),
        arrayOf(Point(0f, 1f), Point(1f, 1f))
    )
        private set

    var cols: Int = 2
        private set
    var rows: Int = 2
        private set
    var subdivX: Int = 0
        private set
    var subdivY: Int = 0
        private set

    fun setSubdiv(sx: Int, sy: Int) {
        val nsx = sx.coerceIn(0, MAX_SUBDIV)
        val nsy = sy.coerceIn(0, MAX_SUBDIV)
        val newCols = (1 shl nsx) + 1
        val newRows = (1 shl nsy) + 1
        resize(newCols, newRows)
        subdivX = nsx
        subdivY = nsy
    }

    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceIn(2, (1 shl MAX_SUBDIV) + 1)
        val nr = newRows.coerceIn(2, (1 shl MAX_SUBDIV) + 1)
        if (nc == cols && nr == rows) return
        val old = points
        val oc = cols; val or = rows
        cols = nc; rows = nr
        points = Array(rows) { r -> Array(cols) { c ->
            val u = if (oc > 1) c.toFloat() / (cols - 1) * (oc - 1) else 0f
            val v = if (or > 1) r.toFloat() / (rows - 1) * (or - 1) else 0f
            val ci = u.toInt().coerceIn(0, oc - 2)
            val ri = v.toInt().coerceIn(0, or - 2)
            val fu = u - ci; val fv = v - ri
            val p00 = old[ri][ci]; val p10 = old[ri][(ci + 1).coerceAtMost(oc - 1)]
            val p01 = old[(ri + 1).coerceAtMost(or - 1)][ci]
            val p11 = old[(ri + 1).coerceAtMost(or - 1)][(ci + 1).coerceAtMost(oc - 1)]
            Point(
                lerp(lerp(p00.x, p10.x, fu), lerp(p01.x, p11.x, fu), fv),
                lerp(lerp(p00.y, p10.y, fu), lerp(p01.y, p11.y, fu), fv)
            )
        }}
        Log.d(TAG, "Resized ${oc}x${or} -> ${cols}x${rows}")
    }

    fun reset() {
        subdivX = 0; subdivY = 0
        cols = 2; rows = 2
        points = arrayOf(
            arrayOf(Point(0f, 0f), Point(1f, 0f)),
            arrayOf(Point(0f, 1f), Point(1f, 1f))
        )
    }

    fun setPoint(row: Int, col: Int, x: Float, y: Float) {
        if (row in 0 until rows && col in 0 until cols) {
            points[row][col] = Point(x, y)
        }
    }

    fun getPoint(row: Int, col: Int): Pair<Float, Float>? {
        if (row in 0 until rows && col in 0 until cols) {
            return points[row][col].x to points[row][col].y
        }
        return null
    }

    fun warp(uv: Pair<Float, Float>): Pair<Float, Float> {
        val (u, v) = uv
        val colIdx = (u * (cols - 1)).toInt().coerceIn(0, cols - 2)
        val rowIdx = (v * (rows - 1)).toInt().coerceIn(0, rows - 2)
        val cu = u * (cols - 1) - colIdx
        val rv = v * (rows - 1) - rowIdx
        val p00 = points[rowIdx][colIdx]
        val p10 = points[rowIdx][colIdx + 1]
        val p01 = points[rowIdx + 1][colIdx]
        val p11 = points[rowIdx + 1][colIdx + 1]
        val wx = lerp(lerp(p00.x, p10.x, cu), lerp(p01.x, p11.x, cu), rv)
        val wy = lerp(lerp(p00.y, p10.y, cu), lerp(p01.y, p11.y, cu), rv)
        return wx to wy
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun regularize(iterations: Int = 50, lambda: Float = 0.5f) {
        for (iter in 0 until iterations) {
            for (r in 1 until rows - 1) {
                for (c in 1 until cols - 1) {
                    val p = points[r][c]
                    val avgX = (points[r - 1][c].x + points[r + 1][c].x +
                                points[r][c - 1].x + points[r][c + 1].x) / 4f
                    val avgY = (points[r - 1][c].y + points[r + 1][c].y +
                                points[r][c - 1].y + points[r][c + 1].y) / 4f
                    p.x += (avgX - p.x) * lambda
                    p.y += (avgY - p.y) * lambda
                }
            }
        }
        Log.d(TAG, "Regularized ${iterations} iterations, lambda=$lambda")
    }

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (r in 0 until rows) {
            val rowArr = JSONArray()
            for (c in 0 until cols) {
                val obj = JSONObject()
                obj.put("x", points[r][c].x.toDouble())
                obj.put("y", points[r][c].y.toDouble())
                rowArr.put(obj)
            }
            arr.put(rowArr)
        }
        val root = JSONObject()
        root.put("cols", cols)
        root.put("rows", rows)
        root.put("subdivX", subdivX)
        root.put("subdivY", subdivY)
        root.put("points", arr)
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
    }

    fun copyFrom(other: FusionMesh) {
        val minRows = minOf(rows, other.rows)
        val minCols = minOf(cols, other.cols)
        for (r in 0 until minRows) {
            for (c in 0 until minCols) {
                points[r][c].x = other.points[r][c].x
                points[r][c].y = other.points[r][c].y
            }
        }
    }

    fun colAt(r: Int, c: Int): Float {
        if (c < 0) return points[r.coerceIn(0, rows - 1)][0].x
        if (c >= cols) return points[r.coerceIn(0, rows - 1)][cols - 1].x
        return points[r.coerceIn(0, rows - 1)][c].x
    }

    fun rowAt(r: Int, c: Int): Float {
        if (r < 0) return points[0][c.coerceIn(0, cols - 1)].y
        if (r >= rows) return points[rows - 1][c.coerceIn(0, cols - 1)].y
        return points[r][c.coerceIn(0, cols - 1)].y
    }
}
