package com.mi.mrouter_api;

import com.mi.router_annotation.bean.RouterBean;

import java.util.Map;

/**
 * 其实就是 路由组 Group 对应的 ---- 详细Path加载数据接口 ARouterPath
 * 例如：order分组 对应 ---- 有那些类需要加载（Order_MainActivity  Order_MainActivity2 ...）
 * <p>
 * <p>
 * key:   /app/MainActivity1
 * value:  RouterBean(MainActivity1.class)
 */
public interface MRouterPath {

    /**
     * 例如：order分组下有这些信息，personal分组下有这些信息
     *
     * @return key:"/order/Order_MainActivity"   或  "/personal/Personal_MainActivity"
     * value: RouterBean==Order_MainActivity.class 或 RouterBean=Personal_MainActivity.class
     */
    Map<String, RouterBean> getPathMap();

}
