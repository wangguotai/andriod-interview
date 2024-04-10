package com.mi.mrouter_api.manager

import android.content.Context
import android.content.Intent
import android.util.Log
import android.util.LruCache
import com.mi.mrouter_api.MRouterGroup
import com.mi.mrouter_api.MRouterPath
import com.mi.mrouter_api.widget.isNotNullOrEmptyKt
import com.mi.router_annotation.bean.RouterBean.TypeEnum

/**
 * 整个目标
 * 第一步：查找 MRouter$$Group$$personal ---> MRouter$$Path$$personal
 * 第二步：使用 MRouter$$Group$$personal ---> MRouter$$Path$$personal
 */
object RouterManager {
    private const val FILE_GROUP_NAME = "MRouter$\$Group$$"
    private val regex = Regex("/[a-zA-Z0-9]+/[a-zA-Z0-9]+")
    private var group: String? = null // 路由的组名 app，order，personal ...
    private var path: String? = null // 路由的路径  例如：/order/Order_MainActivity

    // 提供性能  LRU缓存
    private val groupLruCache: LruCache<String?, MRouterGroup?> = LruCache(100)
    private val pathLruCache: LruCache<String?, MRouterPath?> = LruCache(100)

    /***
     * @param path 例如：/order/Order_MainActivity
     * * @return
     */
    fun build(path: String): BundleManager {
        require(path.isNotNullOrEmptyKt() || regex.matches(path)) {
            "@MRouter注解中的path值，必须为/{group}/{path}"
        }
        // 截取组名  /order/Order_MainActivity  finalGroup=order
        val finalGroup = path.substring(1, path.indexOf("/", 1)) // finalGroup = order
        this.path = path
        this.group = finalGroup
        return BundleManager() // Builder设计模式 之前是写里面的， 现在写外面吧
    }

    // 真正的导航
    fun navigation(context: Context, bundleManager: BundleManager) {
        // 例如：寻找 MRouter$$Group$$personal  寻址   MRouter$$Group$$order   MRouter$$Group$$app
//        val groupClassName = context.packageName + "." + FILE_GROUP_NAME + group
        val groupClassName = "com.wgt.mrouter.$FILE_GROUP_NAME$group"
        Log.e("wgt >>>", "navigation: groupClassName=$groupClassName")
        try {
            // 第一步 读取路由组Group类文件
            var loadGroup = groupLruCache[group]
            if (null == loadGroup) { // 缓存里面没有东东
                // 加载APT路由组Group类文件 例如：MRouter$$Group$$order
                val aClass = Class.forName(groupClassName)
                // 初始化类文件
                loadGroup = aClass.getDeclaredConstructor().newInstance() as MRouterGroup
                // 保存到缓存
                groupLruCache.put(group, loadGroup)
            }
            if (loadGroup.groupMap.isEmpty()) {
                throw RuntimeException("路由表Group报废了...") // Group这个类 加载失败
            }

            //  第二步 读取路由Path类文件
            var loadPath = pathLruCache[path]
            if (null == loadPath) { // 缓存里面没有东东 Path
                // 1.invoke loadGroup
                // 2.Map<String, Class<? extends MRouterLoadPath>>
                val clazz = loadGroup.groupMap[group]!!

                // 3.从map里面获取 MRouter$$Path$$personal.class
                loadPath = clazz.getDeclaredConstructor().newInstance() as MRouterPath
                // 保存到缓存
                pathLruCache.put(path, loadPath)
            }

            // 第三步 跳转
            // 健壮
            if (loadPath.pathMap.isEmpty()) { // pathMap.get("key") == null
                throw RuntimeException("路由表Path报废了...")
            }

            // 最后才执行操作
            val routerBean = loadPath.pathMap[path]
            if (routerBean != null) {
                when (routerBean.typeEnum) {
                    TypeEnum.ACTIVITY -> {
                        val intent = Intent(
                            context,
                            routerBean.myClass
                        ) // 例如：getClazz == Order_MainActivity.class
                        intent.putExtras(bundleManager.bundle) // 携带参数
                        context.startActivity(intent)
                    }

                    null -> {
                        throw RuntimeException("路由表Path报废了...")
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
