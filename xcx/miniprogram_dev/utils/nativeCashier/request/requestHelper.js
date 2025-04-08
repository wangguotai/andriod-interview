/**************************************************
 * @description 收银台接口请求
 *  - created time: 2024/6/19
 *  -
 **************************************************/
import { getExtend, parseDetainmenCRMParams, setMainGoodsProduct, setRenderCRMParams } from "./dataHelper";
import { getSupportTypes, getSystemInfo, getVersion, isSimpleScreenCashier } from "../biz/index";
import { isNonEmptyObject, isNotEmptyArray } from "../data/index";
import { alarmEvent, alarmEventWithPreDesc, sendAlarm } from "../track/TrackHelper";
import CashierDataManager from "../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../store/constants";
import { isWxMiniProgram } from "../../context/contextHelper";

const app = getApp();

/**
 * 防抖函数
 * @param fn {function} 需要防抖的函数
 * @param delay {number} 延迟 单位ms
 */
function debounce(fn, delay) {
    let timer = null;
    return function (...args) {
        const context = this;
        if (timer !== null) {
            clearTimeout(timer);
        }
        timer = setTimeout(() => {
            fn.apply(context, args);
        }, delay);
    };
}

// ====================== 模块内部函数 ===========================
/**
 * 获取是否展示节点
 * @param products {array} 商品列表
 * @param tabStyle {string} tab样式
 */
function getShowNodeList(products, tabStyle) {
    if (isSimpleScreenCashier() || isNotEmptyArray(products) && tabStyle !== '2' || tabStyle === '1') {
        return 0;
    }
    return 1;
}

function getHomeRenderData(checkLoginPay = false) {
    setMainGoodsProduct(null);
    return new Promise((resolve, reject) => {
        const extend = getExtend();
        const bizParams = Object.assign({}, extend.params || {});
        const showNodeList = getShowNodeList(extend.products, extend.tabStyle);
        let attributes = {};
        if (isNonEmptyObject(bizParams.attributes)) {
            attributes = JSON.parse(JSON.stringify(bizParams.attributes));
        }
        // 上报渠道类别
        if (isWxMiniProgram()) {
            attributes.platform = 'wechat';
        }
        attributes.pageVersion = getVersion();

        let nodeKey = bizParams.pageKey;
        bizParams.attributes = JSON.stringify(attributes);
        bizParams.supportTypes = getSupportTypes();
        const params = {
            showNodeList,
            enableEntryDebug: 0,
            bizKey: 'VIP_RENDER',
            bizParams: JSON.stringify(bizParams),
            nodeKey,
            debug: 0,
            gray: extend.gray,
            pageNo: 1,
            utdid: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.UT_DID) || '',
        }
        const cmsParams = {
            ms_codes: '2019040300',
            params: JSON.stringify(params),
            system_info: getSystemInfo(),
        };

        const successCallback = (result) => {
            if (result && result.data && result.data[cmsParams.ms_codes]) {
                resolve(result.data[cmsParams.ms_codes].data);
            } else {
                reject(result);
            }

            if (result) {
                const params = parseDetainmenCRMParams(result, cmsParams.ms_codes);
                if (isNonEmptyObject(params)) {
                    setRenderCRMParams(params);
                }
            }

            alarmEvent('6000', '页面数据请求完成。');
        }
        const failureCallback = (result) => {
            reject();
            alarmEventWithPreDesc('6002', '页面数据请求失败。result = ', result, null);
        }
        // const result = require('./mock/render');
        // successCallback(result.agreementPopUp);  // 协议弹框
        // successCallback(result.singleProduct);  // 单商品
        // successCallback(result.multiProducts);  // 多商品
        // successCallback(result.unPayedOrder);   // 未支付订单
        // successCallback(result.twoProducts); // 两个商品
        // successCallback(result.multiProductsWithActivity);

        // 调起网络请求
        // mtop更新用户登录态
        const {mtop} = app.cashier;
        const userInfo = app.cashier.loginSDK.getSession(); // 通过登录SDK获取token值
        mtop.setUserSessionInfo(userInfo);
        mtop.requestMtopApi('mtop.alidme.xtop.columbus.render', cmsParams, successCallback, failureCallback);
        alarmEventWithPreDesc('6403', '开始渲染，params = ', cmsParams, null);

    });
}

/**
 * 创建订单
 * @param params
 * @param callback 登录结果回调 true, false
 */
export function createOrder(params = {}, callback) {
    const result = {};

    function successCallback(res) {
        if (res && res.data) {
            result.type = 'success';
            result.message = '创建订单成功';
            result.res = res;
            callback && callback(result);
        } else {
            failureCallback(res);
        }
    }

    function failureCallback(res) {
        result.type = 'fail';
        result.message = '创建订单失败';
        result.res = res;
        callback && callback(result);
    }

    const {mtop} = app.cashier;
    mtop.requestXmiApi('mtop.alidme.xtop.trade.order.create', params, successCallback, failureCallback);
}


/**
 * 关闭订单
 * @param {String} orderId 订单ID
 * @param {Object} payChannel 支付渠道
 * @param {Object} orderLine 商品信息
 * @param {Function} callback 关闭订单回调
 */
export const closeOrderById = (orderId, payChannel, orderLine, callback) => {
    if (orderId) {
        closeOrder([orderId], payChannel, orderLine, callback);
    } else {
        const result = {
            type: 'fail',
            message: '订单id不能为空',
        };
        callback && callback(result);
    }
};

/**
 * 关闭订单
 * @param {Array} orderIds 订单ID
 * @param {Object} payChannel 支付渠道
 * @param {Object} orderLine 商品信息
 * @param {Function} callback 关闭订单回调
 */
export function closeOrder(orderIds, payChannel, orderLine, callback) {
    const result = {};
    if (isNotEmptyArray(orderIds)) {
        function successCallback(res) {
            if (res !== null && res.data !== null) {
                result.type = 'success';
                result.message = '关闭订单成功';
                result.res = res;
                callback && callback(result);
                sendAlarm('6410', '关闭订单成功', null, payChannel, orderLine, orderIds);
            } else {
                result.type = 'fail';
                result.message = '关闭订单失败';
                result.res = res;
                callback && callback(result);
                sendAlarm('6411', '关闭订单失败没有数据 res = ', res, payChannel, orderLine, orderIds);
            }
        }

        function failureCallback(res) {
            result.type = 'fail';
            result.message = '关闭订单失败';
            result.res = res;
            callback && callback(result);
            sendAlarm('6409', '关闭订单失败 result = ', result, payChannel, orderLine, orderIds);
        }

        const params = {
            orderIds,
        };
        const {mtop} = app.cashier;
        mtop.requestXmiApi('mtop.alidme.xtop.trade.order.close', params, successCallback, failureCallback);
        sendAlarm('6407', '关闭订单,params=', params, payChannel, orderLine, orderIds);
    } else {
        result.type = 'fail';
        result.message = '关闭订单失败，订单号为空';
        callback && callback(result);
        alarmEvent('6408', '无法关闭订单，订单号为空');
    }
}


export {
    debounce,
    getHomeRenderData
}
