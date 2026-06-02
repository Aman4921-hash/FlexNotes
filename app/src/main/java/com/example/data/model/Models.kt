package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

data class Point(
    val x: Float,
    val y: Float,
    val pressure: Float = 1.0f
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("x", x.toDouble())
        obj.put("y", y.toDouble())
        obj.put("p", pressure.toDouble())
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): Point {
            return Point(
                x = obj.optDouble("x", 0.0).toFloat(),
                y = obj.optDouble("y", 0.0).toFloat(),
                pressure = obj.optDouble("p", 1.0).toFloat()
            )
        }
    }
}

data class Stroke(
    val id: String = java.util.UUID.randomUUID().toString(),
    val points: List<Point>,
    val color: Int,
    val thickness: Float,
    val tool: String, // "PEN", "ERASER", "HIGHLIGHTER", "SHAPE"
    val shape: String = "NONE", // "NONE", "RECTANGLE", "CIRCLE", "LINE", "ARROW"
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        val ptsArray = JSONArray()
        points.forEach { ptsArray.put(it.toJson()) }
        obj.put("points", ptsArray)
        obj.put("color", color)
        obj.put("thickness", thickness.toDouble())
        obj.put("tool", tool)
        obj.put("shape", shape)
        obj.put("timestamp", timestamp)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): Stroke {
            val ptsArray = obj.optJSONArray("points") ?: JSONArray()
            val pts = mutableListOf<Point>()
            for (i in 0 until ptsArray.length()) {
                val ptObj = ptsArray.optJSONObject(i)
                if (ptObj != null) {
                    pts.add(Point.fromJson(ptObj))
                }
            }
            return Stroke(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                points = pts,
                color = obj.optInt("color", -16777216), // default black
                thickness = obj.optDouble("thickness", 5.0).toFloat(),
                tool = obj.optString("tool", "PEN"),
                shape = obj.optString("shape", "NONE"),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }

        fun listToJson(strokes: List<Stroke>): String {
            val array = JSONArray()
            strokes.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(jsonStr: String?): List<Stroke> {
            if (jsonStr.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<Stroke>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        list.add(fromJson(obj))
                    }
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

data class Attachment(
    val id: String,
    val name: String,
    val type: String, // "IMAGE", "PDF", "TEXT"
    val path: String, // simulated content path or uri
    val x: Float,
    val y: Float,
    val width: Float = 250f,
    val height: Float = 250f,
    val hasBorder: Boolean = false,
    val borderStyle: String = "SOLID", // "SOLID", "ROUNDED", "SHADOW", "POLAROID", "NOTEBOOK", "MINIMAL", "ARTISTIC"
    val borderThickness: Float = 4f,
    val borderColor: Int = -16777216, // black
    val borderCornerRadius: Float = 8f,
    val shadowSize: Float = 4f,
    val borderOpacity: Float = 1.0f,
    val borderPadding: Float = 0f,
    val pdfPageIndex: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("type", type)
        obj.put("path", path)
        obj.put("x", x.toDouble())
        obj.put("y", y.toDouble())
        obj.put("w", width.toDouble())
        obj.put("h", height.toDouble())
        obj.put("hasBorder", hasBorder)
        obj.put("borderStyle", borderStyle)
        obj.put("borderThickness", borderThickness.toDouble())
        obj.put("borderColor", borderColor)
        obj.put("borderCornerRadius", borderCornerRadius.toDouble())
        obj.put("shadowSize", shadowSize.toDouble())
        obj.put("borderOpacity", borderOpacity.toDouble())
        obj.put("borderPadding", borderPadding.toDouble())
        obj.put("pdfPageIndex", pdfPageIndex)
        obj.put("timestamp", timestamp)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): Attachment {
            return Attachment(
                id = obj.optString("id", ""),
                name = obj.optString("name", ""),
                type = obj.optString("type", "IMAGE"),
                path = obj.optString("path", ""),
                x = obj.optDouble("x", 50.0).toFloat(),
                y = obj.optDouble("y", 50.0).toFloat(),
                width = obj.optDouble("w", 250.0).toFloat(),
                height = obj.optDouble("h", 250.0).toFloat(),
                hasBorder = obj.optBoolean("hasBorder", false),
                borderStyle = obj.optString("borderStyle", "SOLID"),
                borderThickness = obj.optDouble("borderThickness", 4.0).toFloat(),
                borderColor = obj.optInt("borderColor", -16777216),
                borderCornerRadius = obj.optDouble("borderCornerRadius", 8.0).toFloat(),
                shadowSize = obj.optDouble("shadowSize", 4.0).toFloat(),
                borderOpacity = obj.optDouble("borderOpacity", 1.0).toFloat(),
                borderPadding = obj.optDouble("borderPadding", 0.0).toFloat(),
                pdfPageIndex = obj.optInt("pdfPageIndex", 0),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            )
        }

        fun listToJson(attachments: List<Attachment>): String {
            val array = JSONArray()
            attachments.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJson(jsonStr: String?): List<Attachment> {
            if (jsonStr.isNullOrEmpty()) return emptyList()
            return try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<Attachment>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i)
                    if (obj != null) {
                        list.add(fromJson(obj))
                    }
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
