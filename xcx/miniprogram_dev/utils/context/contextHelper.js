import { BAIDU_MINI_PAY_CONFIG, CASHIER_ENVIRONMENT } from "../config/cashierConfig";
import { getCachedSystemInfo } from "../nativeCashier/common/index";
// 获取不同小程序的平台上下文 避免循环引用先初始化 context 然后是envType，最后是platformName
export const context = getPlatformContext();
// 获取不同小程序的环境类型
export const envType = getPlatformEnvType();
// 获取不同小程序的名称
export const platformName = getPlatformName();

// console.log(`WGT ==== 初始化全局环境变量 envType: ${envType}, platformName: ${platformName}, context: ${context}`)

export const isPre = CASHIER_ENVIRONMENT.DEFAULT_ENV === 'pre';

/**
 * 获取不同小程序的平台上下文
 * @returns {Object} 平台上下文
 */
export function getPlatformContext() {
    if (typeof tt !== 'undefined' && typeof tt.login === 'function') {
        return tt;
    } else if (typeof swan !== 'undefined' && typeof swan.request === 'function') {
        return swan;
    } else if (typeof ks !== 'undefined' && typeof ks.login === 'function') {
        return ks;
    } else if (typeof wx !== 'undefined' && typeof wx.login === 'function') {
        return wx;
    }
    return null;
}

/**
 * 获取不同小程序的环境类型:
 * 抖音小程序中有全局变量wx，保证tt的判断在wx之前
 * @returns {String} 环境类型
 */
export function getPlatformEnvType() {
    if (typeof tt !== 'undefined' && typeof tt.login === 'function') {
        return 'tt';
    } else if (typeof swan !== 'undefined' && typeof swan.request === 'function') {
        if (isBaiduH5Pay()) { // 百度关怀版同样划归到iqiyi
            return 'iqiyi';
        } else {
            return 'swan';
        }
    } else if (typeof ks !== 'undefined' && typeof ks.login === 'function') {
        return 'ks';
    } else if (typeof wx !== 'undefined' && typeof wx.login === 'function') {
        return 'wx';
    }
    return '';
}


/**
 * 获取不同小程序的环境类型
 * @returns {String} 环境类型-大写
 */
export function getPlatformEnvTypeForUpperCase() {
    return getPlatformEnvType().toUpperCase();
}

/**
 * 获取不同小程序的名称
 * @returns {String} 名称
 */
export function getPlatformName() {
    // 避免全局变量的初始化时机不同，导致渠道的数据为空
    let myEnvType = envType;
    if (!myEnvType) {
        myEnvType = getPlatformEnvType();
    }
    if (myEnvType === 'wx') {
        return '微信小程序';
    } else if (myEnvType === 'swan') {
        return '百度小程序';
    } else if (myEnvType === 'iqiyi') {
        return '爱奇艺小程序';
    }
    else if (myEnvType === 'tt') {
        return '抖音小程序';
    } else if (myEnvType === 'ks') {
        return '快手小程序';
    }
    return '';
}

/**
 * 是否是微信小程序
 * @returns {Boolean} 是否微信小程序
 */
export function isWxMiniProgram() {
    return envType === 'wx';
}

/**
 * 是否是百度小程序或爱奇艺小程序
 * @returns {Boolean} 是否百度小程序或爱奇艺小程序
 */
export function isBaiduAndIqiyiMiniProgram() {
    return envType === 'swan' || envType === 'iqiyi';
}

/**
 * 是否是爱奇艺小程序
 * @returns {Boolean} 是否爱奇艺小程序
 */
export function isIqiyiMiniProgram() {
    return envType === 'iqiyi';
}

/**
 * 是否是百度小程序
 * @returns {Boolean} 是否百度小程序
 */
export function isBaiduMiniProgram() {
    return envType === 'swan';
}

/**
 * 是否是抖音小程序
 * @returns {Boolean} 是否抖音小程序
 */
export function isByteDanceMiniProgram() {
    return envType === 'tt';
}

/**
 * 是否是快手小程序
 * @returns {Boolean} 是否快手小程序
 */
export function isKuaishouMiniProgram() {
    return envType === 'ks';
}

/**
 * 是否使用百度系H5支付
 * @returns {Boolean} 是否百度系H5支付
 */
function isBaiduH5Pay() {
    const systemInfo = getCachedSystemInfo();
    return BAIDU_MINI_PAY_CONFIG.USE_H5_PAY_HOST.includes(systemInfo.host);
}
