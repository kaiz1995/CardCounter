package com.example.cardcounter.eval

import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * 严格读取 JSON 字段：类型不对直接报错，不做 org.json 的宽松转换
 * （"true" → true、123.9 → 123、"NaN" → NaN 都算格式错误）。
 * 所有错误抛 IllegalArgumentException，message 带字段名，由调用方加上文件与行号。
 */
internal object JsonFields {
    fun has(json: JSONObject, key: String): Boolean = json.has(key) && !json.isNull(key)

    /** 一段文本必须恰好是一个 JSON 对象，后面不能再跟任何内容（防止一行拼了两条记录）。 */
    fun parseObject(text: String): JSONObject {
        val tokener = JSONTokener(text)
        val value = try {
            tokener.nextValue()
        } catch (error: JSONException) {
            throw IllegalArgumentException("不是合法 JSON: ${error.message}")
        }
        if (value !is JSONObject) throw IllegalArgumentException("不是 JSON 对象")
        if (tokener.nextClean() != 0.toChar()) {
            throw IllegalArgumentException("JSON 对象之后还有多余内容（一行只能有一个对象）")
        }
        return value
    }

    fun long(json: JSONObject, key: String, label: String = key): Long =
        when (val value = raw(json, key, label)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$label 不是整数: ${describe(value)}")
        }

    fun int(json: JSONObject, key: String, label: String = key): Int {
        val value = long(json, key, label)
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw IllegalArgumentException("$label 超出整数范围: $value")
        }
        return value.toInt()
    }

    fun double(json: JSONObject, key: String, label: String = key): Double {
        val value = raw(json, key, label)
        if (value !is Number) throw IllegalArgumentException("$label 不是数字: ${describe(value)}")
        val result = value.toDouble()
        if (!result.isFinite()) throw IllegalArgumentException("$label 不是有限数: $result")
        return result
    }

    fun boolean(json: JSONObject, key: String, label: String = key): Boolean {
        val value = raw(json, key, label)
        if (value !is Boolean) throw IllegalArgumentException("$label 不是布尔值: ${describe(value)}")
        return value
    }

    /** [default] 为 null 时字段必填。 */
    fun string(json: JSONObject, key: String, label: String = key, default: String? = null): String {
        if (!has(json, key)) {
            return default ?: throw IllegalArgumentException("缺少字段 $label")
        }
        val value = json.get(key)
        if (value !is String) throw IllegalArgumentException("$label 不是字符串: ${describe(value)}")
        return value
    }

    fun obj(json: JSONObject, key: String, label: String = key): JSONObject {
        val value = raw(json, key, label)
        if (value !is JSONObject) throw IllegalArgumentException("$label 不是对象: ${describe(value)}")
        return value
    }

    private fun raw(json: JSONObject, key: String, label: String): Any {
        if (!has(json, key)) throw IllegalArgumentException("缺少字段 $label")
        return json.get(key)
    }

    private fun describe(value: Any): String = if (value is String) "'$value'" else value.toString()
}
