package com.mi.mrouter_api.manager

import com.mi.mrouter_api.ParameterGet
import com.mi.mrouter_api.widget.LruCache

/**
 * 参数的 加载管理器
 * 这是用于接收参数的
 *
 *  第一步：查找 Personal_MainActivity$$Parameter
 *  第二步：使用 Personal_MainActivity$$Parameter  this 给他
 */
class ParameterManager {
    companion object {
        private var instance: ParameterManager? = null

        const val FILE_SUFFIX_NAME = "\$\$Parameter"
        fun getInstance(): ParameterManager {
            if (instance == null) {
                synchronized(ParameterManager::class.java) {
                    if (instance == null) {
                        instance = ParameterManager()
                    }
                }
            }
            return instance!!
        }
    }

    private var cache: LruCache<String, ParameterGet> = LruCache(100)


    fun loadParameter(activity: Any) {
        val className = activity.javaClass.name
        var parameterLoad = cache.get(className) // 先从缓存中获取
        if (parameterLoad == null) { // 缓存中获取失败
            try {
                // 类加载Personal_MainActivity + $$Parameter
                val clazz = Class.forName(className + FILE_SUFFIX_NAME)
//                parameterLoad = clazz.newInstance() as ParameterGet
                parameterLoad = clazz.getDeclaredConstructor().newInstance() as ParameterGet
                cache.put(className, parameterLoad)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        parameterLoad.getParameter(activity)
    }
}