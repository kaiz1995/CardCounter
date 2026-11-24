package com.example.cardcounter.eval

/**
 * 输入数据格式错误。message 已带文件名与行号，直接打印给使用者。
 * 一次解析尽量收集全部错误，而不是遇到第一处就退出。
 */
class DataFormatException(message: String) : Exception(message)

internal fun fail(errors: List<String>, source: String): Nothing {
    val head = if (errors.size == 1) "$source 格式错误：" else "$source 有 ${errors.size} 处格式错误："
    throw DataFormatException(head + "\n" + errors.joinToString("\n"))
}

internal fun parseLong(field: String, name: String, line: Int, errors: MutableList<String>): Long? {
    val value = field.trim().toLongOrNull()
    if (value == null) errors += "第 $line 行: $name 不是整数: '$field'"
    return value
}

internal fun parseInt(field: String, name: String, line: Int, errors: MutableList<String>): Int? {
    val value = field.trim().toIntOrNull()
    if (value == null) errors += "第 $line 行: $name 不是整数: '$field'"
    return value
}
