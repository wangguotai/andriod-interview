import CashierDataManager from "../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../store/constants";
import { context } from "../../context/contextHelper";

/**
 * 判断所使用的的功能在当前版本是否可用
 *
 * @param {string} currentVersion 当前小程序native框架版本号
 * @param {string} uncheckedVersion 所使用的功能起始支持的native框架版本号
 * @return {boolean} 所使用的的功能在当前native框架版本是否可用
 */
export function isAvailable(currentVersion, uncheckedVersion) {
    let v1 = currentVersion.split('.').map(v => parseInt(v));
    let v2 = uncheckedVersion.split('.').map(v => parseInt(v));

    const len = Math.max(v1.length, v2.length);

    for (let i = 0; i < len; i++) {
        i >= v1.length && v1.push(0);
        i >= v2.length && v2.push(0);
        if (v1[i] > v2[i]) {
            return true;
        }
        else if (v1[i] < v2[i]) {
            return false;
        }
    }
    return true;
}

/**************************************************
 * @description:
 *  - created time: 2024/7/18
 *  -
 **************************************************/
export function compareVersion(v1, v2) {
    v1 = v1.split('.')
    v2 = v2.split('.')
    const len = Math.max(v1.length, v2.length)

    while (v1.length < len) {
        v1.push('0')
    }
    while (v2.length < len) {
        v2.push('0')
    }

    for (let i = 0; i < len; i++) {
        const num1 = parseInt(v1[i])
        const num2 = parseInt(v2[i])

        if (num1 > num2) {
            return 1
        } else if (num1 < num2) {
            return -1
        }
    }

    return 0
}

/**
 * 将rpx单位转换为px单位。
 * @param {number} rpx - 要转换的rpx值。
 * @returns {number} 转换后的px值。
 */
export function rpxToPx(rpx) {
    // 获取系统信息
    const systemInfo = getCachedSystemInfo();

    // 如果获取到了有效的系统信息，则进行转换；否则返回原值
    if (systemInfo) {
        return rpx * systemInfo.screenWidth / 750;
    } else {
        return rpx;
    }
}

/**
 * 获取缓存中的系统信息或重新获取并缓存。
 * @returns {Object} 系统信息对象。
 */
export function getCachedSystemInfo() {
    let systemInfo = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.SYSTEM_INFO);

    if (!systemInfo) {
        systemInfo = context.getSystemInfoSync();
        CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.SYSTEM_INFO, systemInfo);
    }

    return systemInfo || {};
}

/**
 * 获取缓存中的自定义导航条高度。
 * @returns {Object} 自定义导航条高度。
 */
export function getNavBarHeight() {
    return CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.NAV_BAR_HEIGHT) || 100;
}

/**
 * 获取节点高度
 * @param {string} selector - 节点选择器
 * @param _this - 组件实例
 * @returns {number} 节点高度
 */
export async function queryNodeHeight(selector, _this){
    const query = _this.createSelectorQuery();
    return new Promise((resolve, reject) => {
        try{
            query.select(selector).boundingClientRect().exec((res) => {
                resolve(res[0].height);
            });
        } catch (e) {
            reject(null);
        }
    });
}

/**
 * 深度拷贝
 * @param {Object} obj - 要拷贝的对象
 * @returns {Object} 拷贝后的对象
 */
export function deepClone(obj) {
    if (obj === null || typeof obj !== 'object') {
        return obj; // 基本数据类型或 null
    }

    if (Array.isArray(obj)) {
        // 是数组，使用 map 遍历
        return obj.map(item => deepClone(item));
    }

    const newObj = {}; // 创建一个新对象
    for (let key in obj) {
        if (obj.hasOwnProperty(key)) {
            newObj[key] = deepClone(obj[key]); // 递归拷贝每个属性
        }
    }
    return newObj;
}