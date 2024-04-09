package com.mi.compiler.utils

import com.mi.router_annotation.bean.RouterBean
import javax.annotation.processing.Messager
import javax.tools.Diagnostic

object RouterHelper {
    val regex = Regex("/[a-zA-Z0-9]+/[a-zA-Z0-9]+")

    /**
     * Checkout router path
     *
     * @param bean
     * @param messager
     * @param options
     * @return
     */
    fun checkoutRouterPath(bean: RouterBean, messager: Messager, options: String): Boolean {
        val group = bean.group
        val path = bean.path
        // 校验
        if (path.isNullOrEmptyKt() || !regex.matches(path)) {
            messager.printMessage(
                Diagnostic.Kind.ERROR,
                "@MRouter注解中的path值，必须为/{group}/{path}"
            )
            return false
        }
        val finalGroup = path.substring(1, path.indexOf("/", 1))
        // 如果 group有赋值 但 和模块名不一致
        if (group.isNotNullOrEmptyKt() && !group.equals(options)) {
            messager.printMessage(
                Diagnostic.Kind.ERROR,
                "@MRouter注解中的group值，必须和子模块名一致"
            )
            return false
        } else {
            bean.group = finalGroup
        }
        return true
    }


}