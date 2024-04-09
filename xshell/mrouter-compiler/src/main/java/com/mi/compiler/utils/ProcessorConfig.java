package com.mi.compiler.utils;

public interface ProcessorConfig {
    // @MRouter注解 的 包名 + 类名
    String MROUTER_PACKAGE = "com.mi.router_annotation.MRouter";

    // 接收参数的TAG标记
    String OPTIONS = "moduleName"; // 同学们：目的是接收 每个module名称
    String APT_PACKAGE = "packageNameForAPT"; // 同学们：目的是接收 包名（APT 存放的包名）

    // String全类名
    public static final String STRING_PACKAGE = "java.lang.String";

    // Activity全类名
    public static final String ACTIVITY_PACKAGE = "android.app.Activity";

    // MRouter api 包名
    String MROUTER_API_PACKAGE = "com.mi.mrouter_api";

    // MRouter api 的 MRouterGroup 高层标准
    String MROUTER_API_GROUP = MROUTER_API_PACKAGE + ".MRouterGroup";

    // MRouter api 的 MRouterPath 高层标准
    String MROUTER_API_PATH = MROUTER_API_PACKAGE + ".MRouterPath";

    // 路由组，中的 Path 里面的 方法名
    String PATH_METHOD_NAME = "getPathMap";

    // 路由组，中的 Group 里面的 方法名
    String GROUP_METHOD_NAME = "getGroupMap";

    // 路由组，中的 Path 里面 的 变量名 1
    String PATH_VAR1 = "pathMap";

    // 路由组，中的 Group 里面 的 变量名 1
    String GROUP_VAR1 = "groupMap";

    // 路由组，PATH 最终要生成的 文件名
    String PATH_FILE_NAME = "MRouter$$Path$$";

    // 路由组，GROUP 最终要生成的 文件名
    String GROUP_FILE_NAME = "MRouter$$Group$$";
}
