package com.mi.common.router

import android.util.ArrayMap
import android.util.Log
import java.util.LinkedList

/**
 * Record path manager 全局路径记录器（根据子模块进行分组）
 * 注册的方式写在Application的onCreate中
 *  组名： app, order, login
 *          order[OrderMainActivity,...]
 * @constructor Create empty Record path manager
 */
object RecordPathManager {
    // 方法2. 使用全局Map的方式管理路由
    val maps = ArrayMap<String, MutableList<PathBean>>()

    /**
     * Add group info
     * 将Activity信息存储到全局的管理中
     * @param groupName
     * @param pathName
     * @param clazz
     */
    fun addGroupInfo(groupName: String, pathName: String, clazz: Class<*>) {
        var list = maps.get(groupName)
        if (list == null) {
            list = LinkedList<PathBean>()
            maps.put(groupName, list)
        }
        list.add(PathBean(pathName, clazz))
    }

    fun getTargetActivity(groupName: String, pathName: String): Class<*>? {
        val list = maps.get(groupName)
        if (list == null) {
            Log.d("RecordPathManager", "startTargetActivity 此组名得到的信息，并没有注册进来哦...")
        } else {
            return list.find {
                pathName.equals(it.path, true)
            }?.clazz
        }
        return null
    }

}