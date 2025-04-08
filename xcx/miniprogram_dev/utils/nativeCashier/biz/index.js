import CashierDataManager from "../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../store/constants";
import { convertToObject, extractQueryParameters, extractQueryString, isEmptyStr, isNonEmptyString, isValidString, parseToJson } from "../data/index";
import getTags from "./getTags";
import { isBaiduAndIqiyiMiniProgram, isByteDanceMiniProgram, isWxMiniProgram, envType, context, isKuaishouMiniProgram } from "../../context/contextHelper";
import { APP_INFO_CONFIG, PAY_SUCCESS_PAGE_INFO } from "../../config/cashierConfig";
import { createSignForWxOpenId } from "./sign";
import { getCachedSystemInfo } from "../common/index";

const app = getApp();

/**
 * 收银台版本
 * 规则:以年月日时表示，小时按24小时制表示，示例2022011224
 */
const VERSION = '2024103017';

/**
 * 判断是否为半屏
 * 最新逻辑：
 *  仅依托端上进行判断是否是半屏收银台
 * 更新逻辑：
 * 1. 如果未配置sceneType 则认为是半屏，
 * 2. 如果sceneType配置为了不标准，则认为是半屏
 *
 * @returns {boolean} 是否为半屏
 */
function isSimpleScreenCashier() {
    if (CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.IS_HALF_SCREEN_CASHIER)) {
        return true;
    } else {
        return false;
    }
    // // 获取收银场景类型
    // const sceneType = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.SCENE_TYPE);
    // if (!isValidString(sceneType)) {
    //     return true;
    // }
    // // 定义半屏模式相关的场景类型数组
    // const simpleScreenTypes = [
    //     'simpleScreen',
    //     'simpleScreenCashier',
    // ];
    // // 兼容配置的场景类型，不符合标准的收银台都归为半屏
    // const fullScreenTypes = [
    //     'fullScreenCashier'
    // ];
    // return (simpleScreenTypes.includes(sceneType) || sceneType.includes('simple') || !fullScreenTypes.includes(sceneType));
}

/**
 *  初始化收银台组件全生命周期的全局参数，在组件的created生命周期中调用
 *  1. 创建订单channel参数
 *  2. osType 区分系统类型：用于下单取数
 */
function initUnChangedGlobalParams() {
    const channel = APP_INFO_CONFIG.REQUEST_CHANNEL_INFO[envType];
    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.CREATE_ORDER_PARAM_CHANNEL, channel);
    const systemInfo = getCachedSystemInfo();
    let osType = systemInfo.platform;
    if(isNonEmptyString(osType)){
        osType = osType.toLowerCase();
        if(osType === 'ios') {
            osType = 'iOS';
        }
        CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.HOST_SYSTEM_TYPE, osType);
    }
}

// region 业务默认值获取

/**
 * 将对象转换为查询字符串格式。
 * @param {Object} params - 要转换的对象。
 * @return {string} 返回查询字符串。
 */
function objectToQueryString(params) {
    // 创建一个数组来存储键值对
    let keyValuePairs = [];

    // 遍历对象的所有属性
    for (const key in params) {
        if (params.hasOwnProperty(key)) {
            // 对键和值进行编码，并拼接成 "key=value" 的形式
            let encodedKey = encodeURIComponent(key);
            let encodedValue = encodeURIComponent(params[key]);
            keyValuePairs.push(encodedKey + "=" + encodedValue);
        }
    }

    // 将所有键值对用 "&" 符号连接起来形成完整的查询字符串
    return keyValuePairs.join("&");
}

/**
 * 合并url和queryString
 * @param queryString {string} 收银台链接中的queryString
 * @returns {string} 合并后的url
 */
const mergeUrlWithQueryString = (queryString) => {
    let btnUrl = "https://t.youku.com/app/visp/cashier/index?"
    if (queryString) {
        return btnUrl + queryString;
    } else {
        const h5params = JSON.stringify({
            pageKey: "MINIAPPSTANDARD_YOUKU",
            tags: getTags(),
            channel: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CREATE_ORDER_PARAM_CHANNEL) || '',
            attributes: {orderSequence: undefined},
        });
        let spmcd = 'cashdesk.opendefault';
        let spm = `${app?.track?.spmA}.${app?.track?.spmB}.${spmcd}`
        return `${btnUrl}h5params=${h5params}&en_scm=&en_spm=${spm}`;
    }

}

/**
 * 获取openId
 * */
function getOpenId() {

    return new Promise((resolve, reject) => {
        const ctx = context;
        // 快手获取的是openId
        if (isKuaishouMiniProgram()) {
            const openId = ctx.getStorageSync("youku-ksid");
            if (openId) {
                resolve(openId);
            } else {
                ctx.login({
                    success: res => {
                        if (res.code) {
                            ctx.request({
                                url: "https://service.dandelion.youku.com/ks/login",
                                data: {
                                    app_id: APP_INFO_CONFIG.APP_ID_LIST['ks'],
                                    js_code: res.code,
                                },
                                header: {
                                    "content-type": "application/json",
                                },
                                success: res => {
                                    const {data = {}} = res.data || {};
                                    const {open_id} = data;
                                    // 快手utdid优先取自youku-ksid
                                    ctx.setStorage({key: 'youku-ksid', data: open_id});
                                    resolve(open_id);
                                },
                            });
                        } else {
                            console.error("cashier组件 获取快手登录code失败！res", res);
                            reject(res?.errMsg);
                        }
                    },
                    fail(err) {
                        console.error("ks login err", err);
                        reject(err?.errMsg);
                    }
                });
            }

        } else if (isByteDanceMiniProgram()) {
            const openId = ctx.getStorageSync("youku-ttid");
            if (openId) {
                resolve(openId);
            } else {
                ctx.login({
                    force: false, //未登录时, 是否强制调起登录框
                    success: (res) => {
                        ctx.request({
                            url: 'https://service.dandelion.youku.com/toutiao/login',
                            header: {
                                'content-type': 'application/json',
                            },
                            data: {
                                app_id: APP_INFO_CONFIG.APP_ID_LIST['tt'],
                                js_code: res.code,
                            },
                            method: 'GET',
                            success: (res) => {

                                if (res?.data?.success && res?.data?.data?.data?.openid) {
                                    const openId = res.data.data.data.openid;
                                    ctx.setStorage({key: 'youku-ttid', data: openId});
                                    resolve(openId);
                                } else {
                                    console.error('WGT ==== cashier组件 getOpenId 失败 res====>', res);
                                }
                            },
                            fail: (err) => {
                                console.log('WGT ==== cashier组件 getOpenId 失败  err====>', err);
                                reject(err?.errMsg);
                            },
                        });
                    }
                })
            }
        } else if (isWxMiniProgram()) {
            const openId = ctx.getStorageSync("youku-weixinid");
            if (openId) {
                resolve(openId);
            } else {
                ctx.login({
                    success: res => {
                        if (res.code) {
                            const timeStamp = new Date().getTime();
                            const args = {
                                code: res.code,
                            }
                            const options = {};
                            options.url = "https://service.dandelion.youku.com/weixin/v1/login";
                            options.data = {
                                _timestamp: timeStamp,
                                sign: createSignForWxOpenId(args, timeStamp),
                                ...args,
                            };
                            options.callback = (res) => {
                                if (res?.result?.data?.data) {
                                    let weixinInfo = res.result.data.data;
                                    if (weixinInfo) {
                                        // 兼容安卓ios设备返回weixinInfo格式不一致
                                        let openId = '';
                                        if (isNonEmptyString(weixinInfo)) {
                                            openId = convertToObject(weixinInfo)?.openid;
                                        } else if (typeof weixinInfo === "object") {
                                            openId = weixinInfo?.openid;
                                        }
                                        wx.setStorage({
                                            key: "youku-weixinid",
                                            data: openId
                                        });
                                        resolve(openId);
                                    } else {
                                        reject("cashier组件 getOpenId 失败 res====>", res);
                                    }
                                }
                            };
                            ctx.request(options);
                        } else {
                            console.error("cashier组件 获取微信openId失败！res", res);
                            reject(res?.errMsg);
                        }
                    },
                    fail(err) {
                        console.error("获取微信openId失败！err", err);
                        reject(err?.errMsg);
                    }
                });
            }

        } else if (isBaiduAndIqiyiMiniProgram()) {
            // 百度小程序创建订单时不需要该参数
            let openId = ctx.getStorageSync("youku-swanid");
            if (!openId) {
                ctx.getSwanId({
                    success: res => {
                        openId = res.data.swanid;
                        ctx.setStorage({key: 'youku-swanid', data: openId});
                        resolve(openId);
                    },
                    fail: err => {
                        console.error("收银台组件 获取百度openId失败！err", err);
                        reject(err?.errMsg);
                    }
                });
            } else {
                resolve(openId);
            }
        }
    });
}


/**
 * 获取utdid
 * 1. 微信小程序使用 openId作为utdid
 * 2. 百度小程序使用 swanId作为utdid
 * 3. 其他小程序使用 cna作为utdid
 * @return {Promise<unknown>}
 */
function getCNA() {
    return new Promise((resolve, reject) => {
        const ctx = context;
        const utdid = ctx.getStorageSync("__ETAG__CNA__ID__")?.cna;
        if (utdid) {
            resolve(utdid);
        } else {
            ctx.request({
                url: "https://log.mmstat.com/eg.js",
                header: {
                    "content-type": "application/json",
                },
                success: res => {
                    const str = res?.data || '';
                    const etagRegex = /goldlog\.Etag="([^"]+)"/;
                    const match = str.match(etagRegex);
                    if (match && match[1]) {
                        const etagValue = match[1];
                        resolve(etagValue);
                    } else {
                        reject("cna接口返回，正则表达式匹配失败");
                    }
                },
                fail: res => {
                    console.error("cashier组件 获取cna失败！res", res);
                    reject('cna接口请求失败');
                }
            });
        }
    });
}


/**
 * 从事件对象中构建跳转收银台的参数
 * @param cashierInfo {Object} 事件传递来的数据对象
 * @param isForceFullScreen 是否强制使用全屏收银台
 * @returns {string} 跳转收银台的参数字符串 demo:  https://t.youku.com/app/visp/cashier/index?h5params=%7B%22activityCode%22%3A%2237lxby25%22%2C%22pageKey%22%3A%22MINIAPPSTANDARD_YOUKU%22%2C%22attributes%22%3A%22%7B%5C%22r2%5C%22%3A%5C%221%5C%22%2C%5C%22g_vid%5C%22%3A%5C%221600013433%5C%22%2C%5C%22ctid%5C%22%3A%5C%222108a75417211310565681025e78d9%5C%22%2C%5C%22crm_params%5C%22%3A%5C%22%7B%5C%5C%5C%22touch_point_code%5C%5C%5C%22%3A%5C%5C%5C%22trial_buy_vip_s%5C%5C%5C%22%2C%5C%5C%5C%22tab%5C%5C%5C%22%3A%5C%5C%5C%221%5C%5C%5C%22%2C%5C%5C%5C%22csdId%5C%5C%5C%22%3A%5C%5C%5C%2215%5C%5C%5C%22%7D%5C%22%2C%5C%22no_surprise%5C%22%3A%5C%22true%5C%22%2C%5C%22g_sid%5C%22%3A%5C%22593756%5C%22%2C%5C%22g_tid%5C%22%3A%5C%222108a75417211310565681025e78d9%5C%22%7D%22%2C%22products%22%3A%5B%7B%22promotions%22%3A%5B%7B%22activityId%22%3A%2253473%22%7D%5D%2C%22productId%22%3A%22128%22%2C%22skuId%22%3A%2214679022%22%7D%5D%2C%22tags%22%3A%22ykminiapp%2Cwechat%40ykminiapp%2CYOUKU_WECHAT_MINI_APP%2CplayPayment%22%2C%22channel%22%3A%22WeChat%40yk%22%7D&sceneType=simpleScreenCashier&gray=1&envs=pre&en_sid=593756&en_vid=1600013433&tabStyle=1&hideNavigatorBar=true&crmCode=trial_buy_vip_s&en_scm=20140732.0.0.crm_20140732-manual-999_1_0_0-108218_100101_0_1_17037_1932_1721131056660_dc0172ae94b44f32b6280aa308f628f310_0_0-syt_HALFSTANDARDRENDER&en_spm=a2h08.8165823.player.vipbuy";
 */
const buildCashierUrl = (cashierInfo, isForceFullScreen = false) => {
    // 如果捕获到服务端的收银台链接, 进行处理，避免关键参数丢失
    if (cashierInfo?.cashierUrl) {
        const obj = extractQueryParameters(cashierInfo.cashierUrl);
        if (obj.sceneType && !isForceFullScreen) {
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.SCENE_TYPE, obj.sceneType);
        } else {
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.SCENE_TYPE, 'fullScreenCashier');
        }
        const h5params = convertToObject(obj.h5params) || {};
        if (!h5params.pageKey) {
            h5params.pageKey = "MINIAPPSTANDARD_YOUKU";
        }
        if (!h5params.activityCode) {
            h5params.activityCode = '';
        }
        if (!h5params.products) {
            h5params.products = [];
        }
        h5params.tags = cashierInfo.tags || getTags(); // 用于收银台前端来源统计
        if (!h5params.channel) {
            h5params.channel = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CREATE_ORDER_PARAM_CHANNEL) || '';
        }
        if (!h5params.attributes) {
            h5params.attributes = {};
        }

        // 补充scm、spm信息
        if (!obj.en_scm) {
            obj.en_scm = '';
        }
        if (!obj.en_spm) {
            let spmcd = cashierInfo?.spmcd || 'cashdesk.opendefault';
            obj.en_spm = `${app?.track?.spmA}.${app?.track?.spmB}.${spmcd}`;
        }
        obj.h5params = JSON.stringify(h5params);
        const queryString = objectToQueryString(obj);
        return mergeUrlWithQueryString(queryString);
    } else {
        // 当cashierUrl失效时，启用兜底
        // 更新收银台sceneType 为全屏收银台
        CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.SCENE_TYPE, 'fullScreenCashier');
        let h5params;
        let cashierUrl = "https://t.youku.com/app/visp/cashier/index?"; //收银台兜底地址"
        let scm = cashierInfo?.dataScm || '';
        let spmcd = cashierInfo?.spmcd || 'cashdesk.opendefault';
        let spm = cashierInfo?.dataSpm || `${app?.track?.spmA}.${app?.track?.spmB}.${spmcd}`;
        h5params = JSON.stringify({
            pageKey: "MINIAPPSTANDARD_YOUKU",
            tags: decodeURIComponent(cashierInfo.tags) || '',
            channel: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CREATE_ORDER_PARAM_CHANNEL) || '',
            attributes: {orderSequence: undefined},
        });
        cashierUrl = `${cashierUrl}h5params=${h5params}&en_scm=${scm}&en_spm=${spm}`;
        return cashierUrl;
    }
}


/**
 * 获取收银台页面本地路径
 * @param isNative {boolean} 是否是native
 * @param urlString {String} 参数
 * @returns {string} 本地收银台路径带有参数
 */
function getCashierPage(isNative, urlString) {
    const params = extractQueryString(urlString);
    // 根据是否为使用原生收银台选择不同的页面路径 包含参数
    if (isNative) {
        return '/pages/nativeCashier/index?' + params;
    } else {
        return '/pages/cashdesk/index?' + params;
    }
}

/**
 * 生成标签
 * @param params {Object} 参数
 * @returns {string} 标签
 */
function generateTags(params) {
    const baseTags = ["ykminiapp"];
    const additionalTags = [];

    // 添加半屏或全屏标签
    if (isSimpleScreenCashier()) {
        additionalTags.push('half-screen');
    } else {
        additionalTags.push('full-screen');
    }

    // 合并用户自定义标签
    if (params && isValidString(params.tags)) {
        additionalTags.push(...params.tags.split(','));
    }

    // 去重并拼接所有标签
    const uniqueTags = [...new Set(baseTags.concat(additionalTags))];
    return uniqueTags.join(',');
}

/**
 * 获取活动码
 * @returns {string} 活动码
 */
function getActivityCode() {
    return 'buy_wechat';
}

/** * 获取系统信息
 * @returns {string} 系统信息
 */
function getSystemInfo() {
    const systemInfo = {
        ver: app?.globalData?.ver || '', // 标准收银台圈人：万象使用的优酷小程序的版本号
        appPackageKey: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.HOST_SYSTEM_TYPE) === 'iOS' ?  'com.youku.YouKu.miniapp' : 'com.youku.phone.miniapp', // 区分iOS和Android
        os: 'h5',
        young: 0,
        clientMode: 0,
        utdid: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.UT_DID) || '',
    };

    return JSON.stringify(systemInfo);
}

//  后期调整删除这些逻辑
const getStatusBarHeight = () => {
    return 0;
}

/**
 * 计算用户信息组件高度
 */
const getUserComponentHeight = (isShowHeader) => {
    if (isShowHeader) {
        if (isSimpleScreenCashier()) {
            return 88;
        } else {
            return 120 + 88 + getStatusBarHeight();
        }
    } else if (isSimpleScreenCashier()) {
        return 88;
    } else {
        return 120;
    }
};

/**
 * 获取用户信息展示样式
 * @param {String} type 组件类型
 */
const getUserInfoType = (type) => {
    if (type === '18240') {  // 其他用户信息
        if (isSimpleScreenCashier()) {
            return 'film';
        }
        return 'filmFull';
    } else if (isSimpleScreenCashier()) {
        return 'simple';
    } else if (type === '18400') { // 小升大样式用户信息
        return 'upgrade';
    } else {
        return null;
    }
};

/**
 * 获取商品样式
 * @param {String} type 组件类型
 */
function getProductStyle(type) {
    if (type === '18410') {
        // 横滑B
        return 'slideB';
    } else if (type === '18030') {
        // 九宫格
        return 'switch';
    }
    // 横滑
    return 'slide';
}

// endregion

// region 常规业务方法

/**
 * 获取当前用户U钻数量
 * @param {Object} userData 用户信息
 */
const getCoinBalance = (userData) => {
    if (userData?.attributes?.UCOIN) {
        const coin = convertToObject(userData.attributes.UCOIN);
        if (coin && coin.balance) {
            return coin.balance / 100;
        }
    }
    return 0;
};

/**
 * 判断是否为U钻支付渠道
 * @param {String} payChannelId
 */
const isCoinChannel = (payChannelId) => {
    return payChannelId === '804' || payChannelId === '805' || payChannelId === '806' || payChannelId === '807';
};

// region 版本和组件
/** * 获取版本
 * @returns {number} 版本
 */
const getVersion = () => {
    if (isPreEnv()) {
        return 0;
    }
    return Number(VERSION);
}

/**
 * 获取页面key，调用应在收银台初始化完成之后
 * @returns {string} 页面key
 */
const getPageKey = () => {
    return CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.PAGE_KEY) || '';
}

/**
 * 获取支付成功页地址
 * @param {String} orderId 订单id
 * @returns {String} 支付成功页地址
 */
const getPaySuccessUrl = (orderId) => {
    const env = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CASHIER_ENVIRONMENT) || PAY_SUCCESS_PAGE_INFO.DEFAULT_ENV;
    const nodeKey = PAY_SUCCESS_PAGE_INFO.PAGE_KEY;
    const sceneType = PAY_SUCCESS_PAGE_INFO.SCENE_TYPE;
    const hostUrl = PAY_SUCCESS_PAGE_INFO.HOST_URL[env];
    return `${hostUrl}/app/ykvip_rax/paysuccesspage/pages/index?nodeKey=${nodeKey}&sceneType=${sceneType}&orderId=${orderId}&envs=${env}`;
}

/**
 * 判断是否为预发环境 确保调用时机发生在收银台初始化完成之后
 * @returns {boolean} 是否为预发环境
 */
const isPreEnv = () => {
    const env = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CASHIER_ENVIRONMENT);
    return env === 'pre';
}

/**
 * 收银台版本支持的组件类型，每次更新版本的时候，要同步更新新增的组件类型
 */
const getSupportTypes = () => {
    return ['18000', '18400', '18240', '18010', '18020', '18410', '18030', '18970', '18040', '18150', '18050', '18070', '18090', '18110', '18120', '18130', '18220', '18221', '18270', '18140', '18280', '18130', '18720', '18199', '18101', '18188', '18711', '18013', '18272', '18015'];
}

/**
 * 判断是否为顶部banner组件类型
 * @param componentType
 * @return {boolean}
 */
const isTopBannerComponentType = (componentType) => {
    return componentType === '18015';
}

/**
 * 判断是否为支付按钮组件类型
 * @param componentType {string} 组件类型
 * @returns {boolean} 是否为支付按钮组件类型
 */
const isPayButtonComponentType = (componentType) => {
    return componentType === '18711';
};

/**
 * 判断是否为用户组件类型
 * 18000: 默认用户信息
 * 18400: 小升大用户信息
 * 18240: 其他用户信息
 * @param componentType {string} 组件类型
 * @returns {boolean} 是否为用户组件类型
 */
const isUserComponentType = (componentType) => {
    //
    return componentType === '18000' || componentType === '18400' || componentType === '18240';
};

/**
 * 判断是否为支付渠道组件类型
 * @param componentType {string} 组件类型
 * @returns {boolean} 是否为支付渠道组件类型
 */
const isPayChannelComponentType = (componentType) => {
    return componentType === '18040';
};

/**
 * 判断是否为主商品组件类型
 * 18020 : 主商品
 * 18410 : 横滑b商品
 * 18030 : 九宫格
 * @param componentType {string} 组件类型
 * @returns {boolean} 是否为主商品组件类型
 */
const isMainProductComponentType = (componentType) => {
    return componentType === '18020' || componentType === '18410' || componentType === '18030';
};

/**
 * 判断是否为会员权益组件类型
 * @param componentType {string} 组件类型
 * @returns {boolean} 是否为会员权益组件类型
 */
const isVipBenefitComponentType = (componentType) => {
    return componentType === '18092';
};
// /**
//  * 判断是否为常见问题隐私政策组件类型
//  * @param componentType {string} 组件类型
//  * @returns {boolean} 是否为常见问题隐私政策组件类型
//  */
// const isQAPPComponentType = (componentType) => {
//     return componentType === '18110';
// };

/**
 * 判断是否为banner组件
 * @param componentType {string} 组件类型
 * @returns {boolean} 是否为banner组件
 */
const isBannerComponent = (componentType) => {
    return componentType === '18270';
};

/**
 * 是否是支付协议组件
 * @param {*} componentType
 * @returns
 */
const isPayAgreementComponentType = (componentType) => {
    return componentType === '18101';
};
// 收银台未支付订单组件
const isUnPayedOrderComponentType = (componentType) => {
    return componentType === '18140';
}

// endregion

// endregion

export {
    getOpenId,
    getCNA,
    initUnChangedGlobalParams,
    objectToQueryString,
    mergeUrlWithQueryString,
    buildCashierUrl,
    getCashierPage,
    isSimpleScreenCashier,
    getUserComponentHeight,
    getUserInfoType,
    getProductStyle,
    generateTags,
    getActivityCode,
    getCoinBalance,
    getVersion,
    getPageKey,
    getPaySuccessUrl,
    isPreEnv,
    getSupportTypes,
    getSystemInfo,
    isPayButtonComponentType,
    isUserComponentType,
    isPayChannelComponentType,
    isMainProductComponentType,
    isBannerComponent,
    isVipBenefitComponentType,
    isPayAgreementComponentType,
    isUnPayedOrderComponentType,
    isCoinChannel,
    isTopBannerComponentType,
}



