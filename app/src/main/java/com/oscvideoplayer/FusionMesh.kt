package com.oscvideoplayer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class FusionMesh(
    var cols: Int = 16,
    var rows: Int = 16
) {
    companion object {
        private const val TAG = "FusionMesh"
    }

    data class Point(var x: Float, var y: Float)

    val points: Array<Array<Point>> = Array(rows) { r ->
        Array(cols) { c ->
            Point(c.toFloat() / (cols - 1).coerceAtLeast(1),
                  r.toFloat() / (rows - 1).coerceAtLeast(1))
        }
    }

    fun reset() {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                points[r][c].x = c.toFloat() / (cols - 1).coerceAtLeast(1)
                points[r][c].y = r.toFloat() / (rows - 1).coerceAtLeast(1)
            }
        }
    }

    fun setPoint(row: Int, col: Int, x: Float, y: Float) {
        if (row in 0 until rows && col in 0 until cols) {
            points[row][col].x = x.coerceIn(0f, 1f)
            points[row][col].y = y.coerceIn(0f, 1f)
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
        val uClamped = u.coerceIn(0f, 1f)
        val vClamped = v.coerceIn(0f, 1f)

        val colIdx = (uClamped * (cols - 1)).toInt().coerceIn(0, cols - 2)
        val rowIdx = (vClamped * (rows - 1)).toInt().coerceIn(0, rows - 2)

        val cu = uClamped * (cols - 1) - colIdx
        val rv = vClamped * (rows - 1) - rowIdx

        val p00 = points[rowIdx][colIdx]
        val p10 = points[rowIdx][colIdx + 1]
        val p01 = points[rowIdx + 1][colIdx]
        val p11 = points[rowIdx + 1][colIdx + 1]

        val wx = lerp(lerp(p00.x, p10.x, cu), lerp(p01.x, p11.x, cu), rv)
        val wy = lerp(lerp(p00.y, p10.y, cu), lerp(p01.y, p11.y, cu), rv)

        return wx.coerceIn(0f, 1f) to wy.coerceIn(0f, 1f)
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
        root.put("points", arr)
        return root
    }

    fun fromJson(json: JSONObject) {
        cols = json.optInt("cols", 16)
        rows = json.optInt("rows", 16)
        val arr = json.optJSONArray("points") ?: return
        for (r in 0 until minOf(arr.length(), rows)) {
            val rowArr = arr.optJSONArray(r) ?: continue
            for (c in 0 until minOf(rowArr.length(), cols)) {
                val obj = rowArr.optJSONObject(c) ?: continue
                points[r][c].x = obj.optDouble("x", 0.0).toFloat()
                points[r][c].y = obj.optDouble("y", 0.0).toFloat()
            }
        }
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
}
