package com.mi.mrouter_api

/**
 * 参数的 加载管理器
 * 这是用于接收参数的
 *
 *  第一步：查找 Personal_MainActivity$$Parameter
 *  第二步：使用 Personal_MainActivity$$Parameter  this 给他
 */
//class ParameterManager {
//    private var instance: ParameterManager? = null
//    fun getInstance(): ParameterManager? {
//        if (instance == null) {
//            synchronized(ParameterManager::class.java) {
//                if (instance == null) {
//                    instance = ParameterManager()
//                }
//            }
//        }
//        return instance
//    }
//    private lateinit var cache: LruCache
//}