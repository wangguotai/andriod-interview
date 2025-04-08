/**************************************************
 * @description: 埋点、预警 通用方法
 *  - created time: 2024/7/6
 *  - 为避免异常影响业务，所有埋点、预警都try catch
 **************************************************/
import { getExtend } from "../request/dataHelper";
import { getActivityCode, isSimpleScreenCashier } from "../biz/index";
import { isEmptyStr, isNotEmptyArray } from "../data/index";
import CashierDataManager from "../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../store/constants";

const app = getApp();

export const sendPageView = () => {
    try {
        const extend = getExtend();
        const {
            sceneType,
            pageSPM: spm,
            pageKey: nodeKey,
            trace_id,
            en_spm,
            en_scm,
            en_id,
            actv_spm,
            actv_scm,
            en_component_id,
            en_sid,
            en_vid,
            en_info,
            refer,
            from,
            abTestInfo
        } = extend;
        const isFullCashier = isSimpleScreenCashier() ? 0 : 1;
        const activity_code = extend?.params?.activityCode || getActivityCode();
        const isFromActivity = extend?.params?.tags?.search('isFromActivity') !== -1 ? 'Y' : 'N';

        const pvParams = {
            spm,
            sceneType,
            isFullCashier,
            nodeKey,
            trace_id,
            en_spm,
            en_scm,
            en_id,
            actv_spm,
            actv_scm,
            en_component_id,
            en_sid,
            en_vid,
            en_info,
            refer,
            from,
            isAutoShow: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.IS_AUTO_INVOKE) ? '1' : '0', // 是否是场景化自动拉起
            isFromActivity,
            activity_code,
            abTestInfo,
        };
        // 清除场景化自动拉起标记
        CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.IS_AUTO_INVOKE);
        const spmAB = spm.split('.');

        const {trackSDK, alarmSDK} = app?.cashier;
        trackSDK?.sendPV(spmAB[0], spmAB[1], pvParams);

        // 添加一个虚拟曝光，处理页面en来源
        pvParams.trace_info_tag = 'Y';
        const expParams = {
            spm: `${spm}.virtual.en_trace_info`,
            scm: '',
            track_info: pvParams,
            trace_id,
        };
        trackSDK?.sendExp(expParams, {});

        alarmSDK?.alarmEventWithPreDesc('6333', 'sendPV, params =', expParams, null);
    } catch (e) {
        console.error('TrackHelper sendPV', e);
    }

};

/**
 * 发送请求，点击
 * @param params{object}  //spm=a.b.c.d, scm=a1.b1.c1.d1, track_info={}
 * @param action{object}
 */
export function sendClick(params, action) {
    try {
        const {trackSDK} = app.cashier;
        trackSDK.sendClick(params, action);
    } catch (e) {
        console.error('TrackHelper sendClick', e);
    }
}

/**
 * 发送请求，曝光
 * @param params{object}  //spm=a.b.c.d, scm=a1.b1.c1.d1, track_info={}
 * @param action{object}
 */
export function sendExp(params, action) {
    try {
        const {trackSDK} = app.cashier;
        trackSDK.sendExp(params, action);
    } catch (e) {
        console.error('TrackHelper sendExp', e);
    }

}

// ******************* 非对外使用的预警数据拼接方法 *******************
/**
 * 从支付渠道、商品信息中获取重要的支付信息
 * @param msg
 * @param payChannel
 * @param orderLine
 * @param orderId
 */
function getBaseMsg(msg, payChannel, orderLine, orderId) {
    let payChannelId = '';
    let productIdSkuId = '';
    let productPrice = '';
    let orderIdStr = '';
    if (payChannel) {
        payChannelId = ` payChannelId-${payChannel.payChannelId}`;
    }
    if (orderLine) {
        productIdSkuId = ` productId_skuId-${orderLine.productId}_${orderLine.skuId}`;
        productPrice = ` productPrice-${orderLine.payPrice}_${orderLine.tagPrice}`;
    }
    if (!isEmptyStr(orderId) || isNotEmptyArray(orderId)) {
        orderIdStr = ` orderId-${orderId}`;
    }

    return `${msg}${payChannelId}${productIdSkuId}${productPrice}${orderIdStr}`;
}

/**
 * 从扩展信息中获取埋点点位信息
 */
function getExtendMsg() {
    let enSpmStr = '';
    let fromStr = '';
    let referStr = '';
    if (getExtend()) {
        enSpmStr = ` en_spm-${getExtend().en_spm}`;
        fromStr = ` from-${getExtend().from}`;
        referStr = ` refer-${getExtend().refer}`;
    }

    return `${enSpmStr}${fromStr}${referStr}`;
}

// ******************* END *******************
/**
 * 发送alarm信息
 *
 * @param {string} client_code
 * @param {string} client_msg
 * @param {function} callback
 */
export function alarmEvent(client_code, client_msg, callback=null) {
    try{
        const { alarmSDK} = app?.cashier;
        alarmSDK?.alarm(client_code, client_msg).then((result) => {
            callback && callback(result);
        }).catch((result) => {
            callback && callback(result);
        });
    }catch (e){
        console.error('TrackHelper alarmEvent', e);
    }
}
/**
 * 发送alarm信息
 *
 * @param {*} code alarmCode
 * @param {*} msg alarm提示
 * @param {*} msgObj alarmJson
 * @param {*} payChannel 支付渠道
 * @param {*} orderLine 商品
 * @param {*} orderId 订单号
 */
export function sendAlarm(code, msg, msgObj, payChannel, orderLine, orderId = undefined) {
    try {
        const baseMsg = getBaseMsg(msg, payChannel, orderLine, orderId);
        alarmEventWithPreDesc(code, `${baseMsg}`, msgObj, null);
    } catch (e) {
        console.error('TrackHelper sendAlarm', e);
        alarmEvent('6804', `sendAlarm Error ${e?.message}`);
    }
}

/**
 * 发送alarm信息
 *
 * 在sendAlarm的基础上额外增加收银台扩展信息（主要用到来源信息en_spm\from\refer)
 *
 * @param {*} code alarmCode
 * @param {*} msg alarm提示
 * @param {*} msgObj alarmJson
 * @param {*} payChannel 支付渠道
 * @param {*} orderLine 商品
 * @param {*} orderId 订单号
 * @param {*} extend 收银台扩展信息（主要用刀来源信息en_spm\from\refer)
 */
export function sendAlarm2(code, msg, msgObj, payChannel, orderLine, orderId) {
    try {
        const baseMsg = getBaseMsg(msg, payChannel, orderLine, orderId);
        const extendMsg = getExtendMsg();

        alarmEventWithPreDesc(code, `${baseMsg}${extendMsg}`, msgObj, null);
    } catch (e) {
        alarmEvent('6804', 'sendAlarm Error');
    }
}

/**
 * 预警埋点 使用try-catch避免预警异常导致页面挂掉
 * @param {*} client_code
 * @param {*} client_msg 具体描述
 * @param {*} client_msg_obj 补充的jsonObject描述
 * @param {*} callback
 */
export function alarmEventWithPreDesc(client_code, client_msg, client_msg_obj, callback = null) {
    try{
        const {alarmSDK} = app?.cashier;
        alarmSDK?.alarmEventWithPreDesc(client_code, client_msg, client_msg_obj, callback);
    } catch (e){
        console.error('TrackHelper alarmEventWithPreDesc', e);
    }

}

