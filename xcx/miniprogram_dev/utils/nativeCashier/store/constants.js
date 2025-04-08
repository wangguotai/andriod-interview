/**************************************************
 * @description: 保存全局存储的key
 *  - created time: 2024/6/20
 **************************************************/

export const CASHIER_GLOBAL_STORE_KEYS = {
    IS_SVIP_CASHIER: 'isSvipCashier',
    // 场景化 是否是自动拉齐收银台
    IS_AUTO_INVOKE: 'autoInvoke',
    // 场景化顶部top图
    TOP_BANNER_DATA: 'topBannerData',
    // 场景类型: 半屏or全屏收银台
    SCENE_TYPE: 'sceneType',
    // 是否是半屏收银台, 由端内控制
    IS_HALF_SCREEN_CASHIER: 'isHalfScreenCashier',
    PAGE_KEY: 'pageKey',
    TAG: 'tag',

    ORDER_LINE: 'orderLine',
    ORDER_ID: 'orderId',
    UT_DID: 'utdid',
    CURRENT_PAY_CHANNEL: 'currentPayChannel',
    ALL_PAY_CHANNELS: 'allPayChannels',

    // 处理支付成功与失败的点位上报数据
    PAY_INFO: 'payInfo',

    PAY_CHANNEL_TIPS_INFO: 'payChannelTipsInfo',
    RENDER_PRICE_CONFLICT_INFO: 'renderPriceConflictInfo',
    AGREEMENT_PAY_BTN_DATA: 'agreementPayBtnData',
    AB_TEST_INFO: 'abTestInfo',
    PAGE_TITLE: 'pageTitle',
    OPEN_ID: 'openId',
    CASHIER_ENVIRONMENT: 'envs',
    /**======================== START(宿主设备信息) ==========================**/
    HOST_SYSTEM_TYPE: 'hostSystemType',
    /**======================== END(宿主设备信息) ==========================**/
    /**======================== START(状态控制) ==========================**/
    // 支付成功后是否需要刷新
    NEED_REFRESH: 'needRefresh',
    IS_LOGIN_STATUS_CHANGED: 'isLoginStatusChanged',
    ENABLE_CHECK_CONTINUE_PAY_ORDER: 'enableCheckContinuePayOrder',
    /**======================== START(埋点使用) ==========================**/
    SYSTEM_INFO: 'systemInfo',
    PRE_PAGE_SPM_AB: 'prePageSpmAB',
    TRACE_ID: 'traceId',
    CNT_PAGE_SPM: 'cntPageSpm',
    CASHIER_LINK_URL: 'cashierLinkUrl',
    /**======================== END(埋点使用) ==========================**/


    /**======================== START(设备信息) ==========================**/
    // 自定义导航栏高度
    NAV_BAR_HEIGHT: 'navBarHeight',
    /**======================== END(设备信息) ==========================**/

    /**====================== START(保存全局函数) ==========================**/
    // 预支付协议弹窗点击，采用用后即焚的模式，避免内存泄漏
    PRE_AGREEMENT_DIALOG_ONCLICK: 'preAgreementOnClick',
    /**======================== END(保存全局函数) ==========================**/

    /**======================== START(创建订单时使用) ==========================**/
    CREATE_ORDER_PARAM_CHANNEL: 'createOrderParamChannel',
    BAIDU_NEED_CHECK_ORDER_WHEN_BACK: 'baiduNeedCheckOrderWhenBack',
    IQIYI_WX_PAY_NEED_GO_TO_PAY_SUCCESS_BY_MANUALLY: 'iqiyiWeixinPayNeedGoToPaySuccessByManually',
    /**======================== END(创建订单时使用) ==========================**/
}
export const CASHIER_GLOBAL_STORE_DEFAULT_VALUES = {
    SCENE_TYPE_VALUE: 'simpleScreen',

    // PAGE_KEY_VALUE: '',
    // TAG_VALUE: '',
    // ORDER_LINE_VALUE: {},
    // CURRENT_PAY_CHANNEL_VALUE: {},
    // PAY_CHANNEL_TIPS_INFO_VALUE: {},
    // RENDER_PRICE_CONFLICT_INFO_VALUE: {},
}
