export const CASHIER_ENVIRONMENT = {
    DEFAULT_ENV: 'release', // 重要！！在这里控制收银台环境切换，预发不要上传到仓库
}

// 1. 支付结果轮询对应的配置
export const PAY_RESULT_QUERY_INFO = {
    MAX_POLL_TIMES: 2,  // 支付结果，额外允许的最大的轮询次数
    POLL_INTERVAL: 1000,  // 支付结果，轮询间隔，单位：毫秒
}

// 2. 支付成功页
export const PAY_SUCCESS_PAGE_INFO = {
    PAGE_KEY: 'MINIAPPPAYSUCCESS_VIP',
    SCENE_TYPE: 'fullScreenCashier',
    DEFAULT_ENV: 'release',
    HOST_URL: {
        release: 'https://t.youku.com',
        pre: 'https://pre.t.youku.com',
    }
}

// 3. 针对预警的通用配置
export const ALARM_CONFIG = {
    // 记录当前的预警点位使用位置
    CURRENT_CODE: '7020',
    // 预警参数量较大，调整为POST请求 11.15 废弃，所有的ALARM点位都改为POST
    BIG_ALARM_CLIENT_CODES: ['6403', '6030', '6572', '6563', '6004', '6033', '6334'],
    ALARM_API: 'mtop.youku.aio.collector.alarm',
    BIZ_TYPE_LIST: {
        wx: 'vip-pay-cashier-wx-mini',
        ks: 'vip-pay-cashier-ks-mini',
        swan: 'vip-pay-cashier-swan-mini',
        iqiyi: 'vip-pay-cashier-iqiyi-mini',
        tt: 'vip-pay-cashier-tt-mini',
    },
    // 收银台版本号，每次发布正式版本后，在此版本号+1
    VERSION: {
        wx: '0.0.12', // 播放页半屏场景化
        ks: '0.0.4', // 播放页半屏场景化
        swan: '0.0.5',
        iqiyi: '0.0.5',
        tt: '0.0.4',
    }
}

// 4. 微信小程支付配置
export const WX_MINI_CONFIG = {
    PAY_INFO: {
        // 微信小程序支持的所有支付渠道，需要将支付渠道转换为102
        ALL_PAY_CHANNEL_ID: ['105', '103', '102'],
        FINAL_PAY_CHANNEL_ID: '102',
        WX_PAY_CHANNEL_ICON: 'https://gw.alicdn.com/tfs/TB1gJYBeAL0gK0jSZFtXXXQCXXa-58-58.png',
        WX_PAY_CHANNEL_TITLE: '微信支付',
        WX_PAY_CHANNEL_TERMINAL: 'WeChat',

    }
}

// 5. 快手小程序支付配置
export const KS_MINI_PAY_CONFIG = {
    // 快手小程序担保支付支持的所有支付渠道
    ALL_PAY_CHANNEL_ID: ['132', '119'],
    // 继续支付 & 默认兜底支付使用支付宝
    DEFAULT_PAY_INFO : {
        ALI_PAY_CHANNEL_ID: '132',
        ALI_PAY_TITLE: '支付宝',
        ALI_PAY_TERMINAL: 'kuaishou',
        ALI_PAY_ICON: 'https://gw.alicdn.com/tfs/TB1eWrmerY1gK0jSZTEXXXDQVXa-58-58.png'
    },
}

// 6. 字节小程支付配置
export const BYTE_DANCE_MINI_PAY_CONFIG = {
    // 字节小程序支持的所有支付渠道
    ALL_PAY_CHANNEL_ID: ['117', '137'],
     // 继续支付默认使用抖音支付
     DEFAULT_PAY_INFO : {
        DOUYIN_PAY_CHANNEL_ID: '117',
        DOUYIN_PAY_TITLE: '抖音支付',
        DOUYIN_PAY_TERMINAL: 'toutiao',
        DOUYIN_PAY_ICON: 'https://gw.alicdn.com/imgextra/i4/O1CN01FFN0Vy1cs2Y8Z7Xb2_!!6000000003655-2-tps-72-72.png'
    }
}

// 7. 百度小程序支付配置
export const BAIDU_MINI_PAY_CONFIG = {
    // 继续支付默认使用支付宝
    DEFAULT_PAY_INFO : {
        ALI_PAY_CHANNEL_ID: '100',
        ALI_PAY_TITLE: '支付宝',
        ALI_PAY_TERMINAL: 'baidu',
        ALI_PAY_ICON: 'https://gw.alicdn.com/tfs/TB1WZo_cCslXu8jSZFuXXXg7FXa-72-72.png'
    },
    CONTINUE_PAY_URL: 'https://t.youku.com/app/ykvip_rax/yk_vip_repay/pages/index?hideNavigatorBar=true&type=continuePay',
    USE_H5_PAY_HOST: ['tomas', 'iqiyi'],
}

// 8. MTOP APP_KEY的配置
export const MTOP_CONFIG = {
    APP_KEY_LIST: {
        tt: '34698806', // 抖音
        wx: '34678439', // 微信
        ks: '34660643', // 快手
        swan: '34686434', // 百度
        iqiyi: '34883816', // 爱奇艺
    }
}

// 9. 黄金令箭埋点配置
export const APLUS_CONFIG = {
    APLUS_DEFAULT_KEY: 'default',
    // 上报至的logKey / 接口路径
    APLUS_LOG_KEY: {
        tt: '/youku.h5web.control',
        wx: '/youku.h5web.control',
        ks: '/youku.h5web.control',
        swan: '/youku.h5web.control',
        iqiyi: '/youku.h5web.control',
        default: '/youku.h5web.control', // 兜底
    },
    // 上报的host
    APLUS_HOST: {
        tt: 'gm.mmstat.com/yt',
        wx: 'gm.mmstat.com/yt',
        ks: 'gm.mmstat.com/yt',
        swan: 'gm.mmstat.com/yt',
        iqiyi: 'gm.mmstat.com/yt',
        default: 'gm.mmstat.com/yt', // 兜底
    },
    APP_KEY_LIST: {
        // 默认APPKEY
        DEFAULT_APP_KEY: 'YOUKU_DEFAULT_MINI_APP',
        // 字节系appkey
        tt: {
            Douyin: 'YOUKU_DOUYIN_MINI_APP', // 抖音(国内版)
            Toutiao: 'YOUKU_TOUTIAO_MINI_APP', // 今日头条
            douyin_lite: 'YOUKU_DOUYIN_LITE_MINI_APP', // 抖音极速版
            aweme_hotsoon: 'YOUKU_LIVE_STREAM_MINI_APP', // 抖音火山版
            default: 'YOUKU_BYTE_DANCE_OTHER_MINI_APP', // 默认其他字节系
        },
        // 微信系appkey
        wx: {
            default: 'YOUKU_WECHAT_MINI_APP', // 默认其他微信系
        },
        // 快手系appkey
        ks: {
            KUAISHOU: 'YOUKU_KUAISHOU_MINI_APP', // 快手
            NEBULA: 'YOUKU_KUAISHOU_NEBULA_MINI_APP', // 快手极速版
            default: 'YOUKU_KUAISHOU_OTHER_MINI_APP', // 默认其他快手系
        },
        // 百度系appkey
        swan: {
            baiduboxapp: 'YOUKU_BAIDU_MINI_APP', // 百度APP
            bdlite: 'YOUKU_BDLITE_MINI_APP', // 百度极速版
            tomas: 'YOUKU_BD_TOMAS_MINI_APP', // 百度关怀版
            default: 'YOUKU_BAIDU_OTHER_MINI_APP', // 默认其他百度系host
        },
        // 爱奇艺appkey  [爱奇艺作为主要营收端，已从百度小程序中做了单独的拆分]
        iqiyi: {
            iqiyi: 'YOUKU_IQIYI_MINI_APP', // 爱奇艺
            default: 'YOUKU_IQIYI_OTHER_MINI_APP', // 不会走到的默认配置
        },
    },

}

// 10. passport 配置
export const PASSPORT_CONFIG = {
    APP_NAME: 'youku',
    APP_ENTRANCE_LIST: {
        wx: 'wechat', // 微信
        ks: 'kuaishou', // 快手
        swan: 'baidu', // 百度
        iqiyi: 'iqiyi', // 爱奇艺
        tt: 'toutiao', // 抖音
        default: 'cashier', // 收银台兜底配置
    },
    GATEWAY: "https://cnpassport.youku.com",
    PAGE_PATH: '/pages/havanalogin/index',
}

// 11. 小程序的APP_ID
export const APP_INFO_CONFIG = {
    // 小程序的APP_ID
    APP_ID_LIST: {
        wx: 'wx5de0c309a1472da6', // 微信
        ks: 'ks682013871589519471', // 快手
        swan: '16326274', // 百度
        iqiyi: '16326274', // 爱奇艺 与 百度相同
        tt: 'tt9771e1cc085b1597', // 抖音
    },
    // 渲染 & 支付 渠道标识
    REQUEST_CHANNEL_INFO: {
        wx: 'WeChat@yk', // 微信
        ks: 'kuaishou@yk', // 快手
        swan: 'baidu@yk', // 百度
        iqiyi: 'iqiyi@yk', // 爱奇艺
        tt: 'toutiao@yk', // 抖音
    }
}

// 12. 通用配置
export const COMMON_CONFIG = {
    // 支付来源
    GO_PAY_FROM: {
        PAY_BUTTON: 'payButton',
        UNFINISHED_ORDER_DIALOG: 'UnfinishedOrderDialog',
        REUSED_ORDER: 'reusedOrder',
    },
    // SVIP收银台标识
    SVIP_CONFIG: {
        SVIP_BIZ: 'cibn',
        SVIP_CASHIER_SKIN: 'customSvip'
    }

}
