package com.mi.compiler.utils

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/**
 * 全局的扩展函数用以判断是否为空
 * @return
 */
@OptIn(ExperimentalContracts::class)
fun Any?.isNullOrEmptyKt(): Boolean {
    contract {
        returns(false) implies (this@isNullOrEmptyKt != null)
    }
    return when (this) {
        null -> true
        is String -> this.toString().isEmpty()
        is Collection<*> -> this.isEmpty()
        is Map<*, *> -> this.isEmpty()
        is Array<*> -> this.isEmpty()
        else -> false
    }
}

/**
 * 全局的扩展函数用以判断是否非空
 * @return
 */
@OptIn(ExperimentalContracts::class)
fun Any?.isNotNullOrEmptyKt(): Boolean {
    contract {
        returns(true) implies (this@isNotNullOrEmptyKt != null)
    }
    return this != null && when (this) {
        is String -> this.toString().isNotEmpty()
        is Collection<*> -> this.isNotEmpty()
        is Map<*, *> -> this.isNotEmpty()
        is Array<*> -> this.isNotEmpty()
        else -> true
    }
}

/**
 * 去除字符串中的空白符
 */
fun String.filterWhiteSpace() = this.filter { !it.isWhitespace() }