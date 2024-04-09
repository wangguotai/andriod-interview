package com.mi.mrouter_api;

import java.util.Map;

/**
 * Group分组的领头 带头大哥
 * <p>
 * <p>
 * *    key:   app
 * *    value:  ARouterPath
 */
public interface MRouterGroup {

    /**
     * 例如：order分组下有这些信息，personal分组下有这些信息
     * 例如："order" --- ARouterPath的实现类 -->（APT生成出来的 ARouter$$Path$$order）
     *
     * @return key:"order/app/personal"      value:系列的order组下面所有的（path---class）
     */
    Map<String, Class<? extends MRouterPath>> getGroupMap();

}
