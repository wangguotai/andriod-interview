/**************************************************
 * @description:
 *  - created time: 2024/7/9
 *  -
 **************************************************/
import { convertToObject, isNonEmptyObject, isNotEmptyArray, isTypeString, parseToJson } from "../../data/index";
import { alarmEvent, alarmEventWithPreDesc, sendAlarm, sendAlarm2 } from "../../track/TrackHelper";
import CashierDataManager from "../../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../../store/constants";
import { createOrder } from "../../request/requestHelper";
import { BYTE_DANCE_MINI_PAY_CONFIG, PAY_RESULT_QUERY_INFO } from "../../../config/cashierConfig";
import { startPoolOrder } from "../../pollOrder/index";
import { sendBroadcast } from "../../BroadcastReceiver";
import { getPlatformContext, getPlatformEnvTypeForUpperCase, getPlatformName, isBaiduAndIqiyiMiniProgram, isByteDanceMiniProgram, isIqiyiMiniProgram, isKuaishouMiniProgram, isWxMiniProgram } from "../../../context/contextHelper";
import { doAction } from "../../eventHandler/index";
import { getPaySuccessUrl } from "../../biz/index";
import { getCachedSystemInfo, isAvailable } from "../../common/index";
import { getPayChannelId } from "./PayManager";
import { goDouyinMiniPay } from "./douyinPayment";

const ctx = getPlatformContext();

/**
 * 还有未支付的订单，获取订单ID
 * @param data
 * @returns {*}
 */
const getOrderId = (data) => {
    let orderId = null;
    let res;

    if (typeof data === 'string') {
        res = parseToJson(data);
    } else {
        res = data;
    }

    if (res) {
        orderId = res.orderId;
    }

    return orderId;
};


/**
 * 创建订单失败，有为送出的礼包，返回未送出礼包的ID
 * @param data
 * @returns {*}
 */
const getOutId = (data) => {
    let outId = null;
    let res;

    if (typeof data === 'string') {
        res = parseToJson(data);
    } else {
        res = data;
    }

    if (res) {
        outId = res.outId;
    }

    return outId;
};

const getResultForMsgCode = (data, msgCode, msgInfo) => {
    const result = {};
    result.type = msgCode;
    /**
     * 主副卡业务resultCode 取值
     * FAIL_BIZ_UNRECEIVED_CHECK_ERROR::还有未赠送的礼包
     * FAIL_BIZ_RELATION_LIMIT_ERROR::购买已经超出上限
     * FAIL_BIZ_RELATION_LIMIT_VALID_ERROR::最多可购买4个轻享VIP会员
     * FAIL_BIZ_UNPAY_ORDER::有待支付的主副卡订单
     */
    if (msgCode === 'FAIL_BIZ_UNRECEIVED_CHECK_ERROR') {
        // 还有未赠送的礼包，不允许创建主副卡订单
        // 没有result.type = 'toast'，会导致一直loading不消失
        result.type = 'toast';
        result.outId = getOutId(data);
        result.message = '还有未赠送的礼包';
    } else if (msgCode === 'FAIL_BIZ_RELATION_LIMIT_ERROR') {
        // 购买已经超出了上限, 不允许创建主副卡订单
        result.type = 'toast';
        result.message = '可继续帮您的亲友续费哦~';
    } else if (msgCode === 'FAIL_BIZ_RELATION_LIMIT_VALID_ERROR') {
        // 最多可购买4个轻享VIP会员, 不允许创建主副卡订单
        result.type = 'toast';
        result.message = '在会员中心可管理亲友关系哦~';
    } else if (msgCode === 'FAIL_BIZ_UNPAY_ORDER' || msgCode === 'FAIL_BIZ_UNPAY_ORDER_CROSS') {
        // 有未支付主副卡订单, 不允许再次创建主副卡订单
        result.type = 'toast';
        result.orderId = getOrderId(data);
        result.message = '有未支付主副卡订单';
    } else if (msgCode) {
        result.type = 'toast';
        result.message = msgInfo || '支付失败';
    } else {
        result.type = 'fail';
        result.message = '支付失败';
    }
    result.res = data;

    return result;
};


/**
 * 创建订单失败，获取失败错误码
 * @param data
 * @returns {*}
 */
const getMsgCode = (data) => {
    const result = {};
    let res;

    if (typeof data === 'string') {
        res = parseToJson(data);
    } else {
        res = data;
    }

    if (res) {
        if (res.attr) {
            result.msgCode = res.attr.msgCode;
            result.msgInfo = res.attr.msgInfo;
        }
        result.resultCode = res.resultCode;
    }

    return result;
};

/**
 * 快手小程序调起支付逻辑
 * 执行支付逻辑：
 *  快手侧流程：
 *       1. 客户端调用开发者服务器，开始预支付流程，服务端生成 out_order_no -> 请求快手小程序服务端，创建预支付，计算费率 -> 返回预支付信息（orderInfo）
 *       2. 客户端使用预支付信息（orderInfo）拉起支付页面： 调用小程序SDK ks.pay(orderInfo)拉起支付页面
 *           - 快手小程序sdk 请求支付信息
 *           - 快手小程序服务端 验证支付信息，返回支付页面
 *       3. 用户发起支付
 *           - 快手小程序sdk，调用快手小程序服务端： 查询配置信息
 *           - 快手小程序sdk，接收返回的配置信息，拉起第三方支付页面
 *       4. 确认支付
 *           - 支付成功
 * @param {Object} orderInfo 快手小程序支付需要的支付参数
 * @param {String} payChannelId 支付渠道id
 */
const goKuaishouMiniPay = (orderInfo, payChannelId) => {
    try {
        const paymentChannel = payChannelId === '132' ? {
            provider: "ALIPAY",
            provider_channel_type: "NORMAL",
        } : {
            provider: "WECHAT",
            provider_channel_type: "NORMAL",
        }
        ks.pay({
            serviceId: 1,
            orderInfo,
            paymentChannel,
            success: (res) => {
                sendBroadcast('paySuccess', res);
            },
            fail: (res) => {
                sendBroadcast('payFail', res);
            }
        });
    } catch (e) {
        console.error(`调起${getPlatformName()}支付失败`, e);
        alarmEvent('6555', `调起${getPlatformName()}支付失败`);
    }

}

/**
 * 小程序调起支付逻辑
 * @param {Object} orderInfo 微信小程序支付需要的支付参数字符串
 */
const goWeChatMiniPay = (orderInfo) => {
    try {
        wx.requestPayment({
            // 必要的支付参数
            timeStamp: orderInfo.timeStamp,
            nonceStr: orderInfo.nonceStr,
            package: orderInfo.package,
            signType: orderInfo.signType,
            paySign: orderInfo.paySign,

            success: (res) => {
                sendBroadcast('paySuccess', res);
            },
            fail: (res) => {
                sendBroadcast('payFail', res);
            }
        });
    } catch (e) {
        console.error(`调起${getPlatformName()}支付失败`, e);
        alarmEvent('6555', `调起${getPlatformName()}支付失败`);
    }
};


// =============================================== START 百度小程序 ==================================================

/**
 * 更新百度小程序订单
 * @param orderInfo
 */
function updateBaiduTradeOrder(orderInfo) {
    const { orderId, baiduOrderId } = orderInfo;
    const params = {
        scenario: 'baiduApp',
        orderId: orderId,
        orderAttr: {
            baidu_order_id: baiduOrderId
        }
    }
    const app = getApp();
    const { mtop } = app.cashier;
    mtop.requestXmiApi('mtop.alidme.xtop.trade.order.update', params, (res) => {
        console.log('WGT === 001 updateBaiduTradeOrder success', res);
    }, (ret) => {
        console.error('WGT === 002 updateBaiduTradeOrder fail', ret);
    });
}

/**
 * 百度小程序调起支付逻辑
 * @param baiduOrderInfoParam
 */
const goBaiduMiniPay = (orderId, baiduOrderInfoParam) => {
    if (swan.canIUse('requestThirdPayment')) {
        const {
            appKey,
            tpOrderId,
            dealId,
            totalAmount,
            payInfo,
            tradeNo,
            mchIdMd5,
            rsaSign,
            dealTitle,
            chosenChannel,
            payCheckUrl,
            returnData
        } = baiduOrderInfoParam || '';

        // 支持 支付宝 + 微信 支付
        swan.requestThirdPayment({
            orderInfo: {
                tradeNo: tradeNo,
                mchIdMd5: mchIdMd5,
                dealId: dealId,
                dealTitle: dealTitle,
                totalAmount: totalAmount,
                appKey: appKey,
                chosenChannel: chosenChannel,
                tpOrderId: tpOrderId,
                rsaSign: rsaSign,
                payInfo: payInfo,
                payCheckUrl: payCheckUrl,
                returnData: returnData,
            },
            success: (res) => {
                updateBaiduTradeOrder({
                    orderId,
                    baiduOrderId: res.orderId
                });
                // 微信支付只返回拉起SDK的状态，并不是支付结果
                if (chosenChannel === 'WeChat') {
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.BAIDU_NEED_CHECK_ORDER_WHEN_BACK, res);
                } else { // 支付宝直接通知支付成功
                    sendBroadcast('paySuccess', res);
                }
            },
            fail: (res) => {
                sendBroadcast('payFail', res);
            }
        });
    } else {
        ctx.showToast({
            title: '当前APP版本过低，不支持支付',
            icon: 'none',
        });
        const systemInfo = getCachedSystemInfo();
        const sdkVersion = systemInfo.SDKVersion || '';
        alarmEvent('7004', `${getPlatformName()}版本过低:${sdkVersion},不支持requestThirdPayment`);
    }

}


// =============================================== END 百度小程序 ==================================================
/**
 * 微信小程序端创建订单并支付
 * @param res
 * @param payChannel 支付渠道对象（包含支付渠道id等完整的数据）
 * @param orderLine 商品
 * @param cycleBuyType 连包类型
 * @param payChannelId 支付渠道id
 * @param callback 回调
 */
function processPaymentForDiffMini(res = {}, payChannel, orderLine, cycleBuyType, payChannelId, callback) {
    const mData = res.data;
    const { orderId } = mData; // 获取订单号
    const result = { orderId };
    result.type = 'wait';
    result.res = res;
    result.from = `${getPlatformEnvTypeForUpperCase()}_MINI`;
    // 适配百度小程序，requestThirdPayment 非异步的，会导致回调延后导致异常
    callback && callback(result);
    // 打开支付中的加载动画
    sendBroadcast('paying', null);
    let createOrderFail = false;
    let externalMsg = '';
    if (isKuaishouMiniProgram()) {
        // 此处要强约束 payChannelResponse 为符合预期的待转字符串
        const orderInfo = parseToJson(mData.payChannelResponse);
        if (isNonEmptyObject(orderInfo)) {
            goKuaishouMiniPay(orderInfo, payChannelId);
        } else {
            createOrderFail = true;
        }

    } else if (isWxMiniProgram()) {
        const wxPayParamsStr = mData.payChannelResponse || mData.payUrl;
        const orderInfo = parseToJson(wxPayParamsStr);

        if (isNonEmptyObject(orderInfo)) {
            goWeChatMiniPay(orderInfo);
        } else {
            createOrderFail = true;
        }
    } else if (isBaiduAndIqiyiMiniProgram()) {
        let payUrl = mData.payChannelResponse || mData.payUrl;
        // 需要先判断是否是爱奇艺小程序
        // 爱奇艺小程序中使用H5方式支付
        if(isIqiyiMiniProgram()){
            if(payChannelId === '105' || payChannelId === '102'){
                // rax环境下，微信支付使用302跳转解决referer丢失问题
                // 微信支付时，走location跳转解决referrer为空问题
                payUrl = `https://t.youku.com/app/ykvip_rax/yk_vip_transfer_rax/pages/index?callback=${encodeURIComponent(payUrl)}`;
                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.IQIYI_WX_PAY_NEED_GO_TO_PAY_SUCCESS_BY_MANUALLY, true);
            }
            // 当小程序从H5中返回后，需要检测订单状态
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.BAIDU_NEED_CHECK_ORDER_WHEN_BACK, true);
            // 爱奇艺小程序中使用H5方式支付，框架会自动decode一次，因此需要encode两次，保证传参的正确性
            doAction({
                type: 'JUMP_TO_URL',
                value: {
                    innerUrl: payUrl,
                    needEncodeTwice: true,
                },
                from: '爱奇艺支付'
            });
        } else {
            // 百度小程序中使用直连支付API
            const { attributes, orderId } = mData;
            const { baiduOrderInfo } = attributes;
            if (isNonEmptyObject(baiduOrderInfo)) {
                const baiduMiniParamObj = parseToJson(baiduOrderInfo);
                goBaiduMiniPay(orderId, baiduMiniParamObj);
            } else {
                createOrderFail = true;
            }
        }
    } else if (isByteDanceMiniProgram()) {
        // 根据主客逻辑支付渠道 117走抖音支付，其余会走H5跳转
        const channelId = getPayChannelId(payChannel);
        let payUrl = mData.payChannelResponse || mData.payUrl;
        if (BYTE_DANCE_MINI_PAY_CONFIG.ALL_PAY_CHANNEL_ID.includes(channelId)) {
            // 抖音小程序中，并且下发了抖音的支付渠道，需要走抖音支付；
            // 打开新的小程序页面，传入下单参数，在小程序页面完成下单和拉抖音支付面板，完成支付后，在页面resume的时候查询订单；
            // 抖音提供的下单等接口只支持小程序调用，所以需要打开新的小程序页面；
            // 连包（tt.createSignOrder/tt.sign）和非连包（tt.requestOrder/tt.getOrderPayment）调用不同的接口；
            // https://developer.open-douyin.com/docs/resource/zh-CN/mini-app/develop/api/industry/credit-products/createSignOrder
            if (mData?.payChannelResponse) {

                const payChannelResponse = parseToJson(mData.payChannelResponse);
                const payChannelResponseData = payChannelResponse && parseToJson(payChannelResponse.data);
                const byteAuthorization = payChannelResponse.byteAuthorization;

                // 连包商品可能降级，需要告诉小程序走非连包链路
                if (cycleBuyType && payChannelResponse && payChannelResponse.cycleBuyType && payChannelResponse.cycleBuyType == 'REDIRECT_PAY') {
                    cycleBuyType = '0';
                }
                // 如果连包且没有openid，提示用户重启小程序后支付
                if (cycleBuyType === '1' && payChannelResponseData && (!payChannelResponseData.openId || payChannelResponseData.openId == 'undefined')) {
                    result.type = 'toast';
                    result.message = '支付失败，请尝试重启优酷小程序。';
                    callback && callback(result);
                    sendAlarm('6596', `${getPlatformName()}中跳转参数异常，没有openId，payUrl=`, payUrl, payChannel, orderLine, orderId);
                } else if (!payChannelResponseData || !byteAuthorization) {
                    result.type = 'toast';
                    result.message = '支付失败，请尝试重启优酷小程序。';
                    callback && callback(result);
                    sendAlarm('6596', `${getPlatformName()}中跳转参数异常，payChannelResponse=`, payChannelResponse, payChannel, orderLine, orderId);
                } else {
                    // 唤起抖音支付
                    goDouyinMiniPay(cycleBuyType, payChannelResponse, byteAuthorization);
                }
            } else {
                createOrderFail = true;
            }
        } else {
            createOrderFail = true;
            externalMsg = ',没有下发抖音的支付渠道';
        }
    }

    if (createOrderFail) {
        alarmEventWithPreDesc('6006', `${getPlatformName()}-订单状态异常`, res, null);
        result.type = 'fail';
        result.message = '订单创建失败' + externalMsg;
        result.res = res;
        callback && callback(result);
    }

    sendAlarm2('6552', `${getPlatformName()}中支付`, null, payChannel, orderLine, orderId);

}


/**
 * 小程序创建订单
 * @param params
 * @param payChannel
 * @param orderLine
 * @param cycleBuyType
 * @param callback
 */
export const createOrderForMiniPay = (params = {}, payChannel, orderLine, cycleBuyType, callback) => {
    // 1. 请求接口，创建订单
    createOrder(params, (result) => {
        let mResult = {};
        mResult.from = `${getPlatformEnvTypeForUpperCase()}_MINI`;
        if (result && result.type === 'success') {
            if (result.res && result.res.data) {
                // 2. 创建订单成功，执行不同小程序的支付
                processPaymentForDiffMini(result.res, payChannel, orderLine, cycleBuyType, params.payChannel, callback);

                const orderId = result.res.data && (result.res.data.order_id || result.res.data.trade_id || result.res.data.orderId);
                sendAlarm('6033', `${getPlatformName()}端 创建订单成功`, result, payChannel, orderLine, orderId);
            } else {
                mResult.type = 'fail';
                mResult.message = '订单创建失败';
                mResult.res = result.res;
                callback && callback(mResult);
                sendAlarm2('6006', `${getPlatformName()}端 创建订单失败-接口成功但没数据，返回内容错误result-`, result, payChannel, orderLine, null);
            }
        } else {
            let data;
            if (result && result.res) {
                data = result.res.data;
            }
            const { msgCode, msgInfo } = getMsgCode(data);
            if (msgCode) {
                mResult = getResultForMsgCode(data, msgCode, msgInfo);
                mResult.from = `${getPlatformEnvTypeForUpperCase()}_MINI`;
                console.error(`${getPlatformName()}端 创建订单失败，msgCode-${msgCode}||${mResult.message}，result-`, result, payChannel, orderLine);
                sendAlarm2('6006', `${getPlatformName()}端 创建订单失败，msgCode-${msgCode}||${mResult.message}，result-`, result, payChannel, orderLine, null);
            } else {
                mResult.type = 'toast';
                mResult.message = msgInfo || '登录信息异常，请重新登录';
                console.error(`${getPlatformName()}端 创建订单失败，没有错误码，${mResult.message}，result-`, result, payChannel, orderLine);
                sendAlarm2('6006', `${getPlatformName()}端 创建订单失败，没有错误码，${mResult.message}，result-`, result, payChannel, orderLine, null);
            }
            // 创建订单失败
            callback && callback(mResult);
        }
    });
};

/** * 获取支付结果页的hostName
 * @returns {string}
 */
function getPayResultHostName() {
    const environment = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CASHIER_ENVIRONMENT);
    if (environment === 'pre') {
        return 'pre.t.youku.com';
    } else {
        return 'activity.youku.com';
    }
}


/**
 * 获取创建订单的回调地址
 * @returns {string}
 */
export function getReturnUrl() {
    const envs = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CASHIER_ENVIRONMENT) || 'release';
    return `https://${getPayResultHostName()}/app/ykvip_rax/paysuccesspage/pages/index?sceneType=fullScreenCashier&nodeKey=MINIAPPPAYSUCCESS_VIP&envs=${envs}`;
}


export function openNoSecretPayChannels(orderLine) {
    const payChannelAttrs = [];
    if (isNonEmptyObject(orderLine) && isNonEmptyObject(orderLine.attributes)) {
        if (isTypeString(orderLine.attributes.certain_pay_channels)) {
            const certain_pay_channels = JSON.parse(orderLine.attributes.certain_pay_channels);
            if (isNotEmptyArray(certain_pay_channels)) {
                for (let index = 0; index < certain_pay_channels.length; index++) {
                    const element = certain_pay_channels[index];
                    if (isNonEmptyObject(element) && isTypeString(element.attributes)) {
                        const attributes = JSON.parse(element.attributes);
                        if (isNonEmptyObject(attributes) && isTypeString(attributes.is_open_password_free) && attributes.is_open_password_free === 'true') {
                            const payChannelAttr = {};
                            payChannelAttr.attributes = attributes;
                            payChannelAttr.payChannelId = element.payChannelId;
                            payChannelAttrs.push(payChannelAttr);
                        }
                    }
                }
            }
        }
    }

    return payChannelAttrs;
}


/**
 * 过滤出有密码免密的支付渠道
 * @param orderLine
 * @returns
 */
export function noSecretPayChannels(orderLine) {
    const payChannelAttrs = [];
    if (isNonEmptyObject(orderLine) && isNonEmptyObject(orderLine.attributes)) {
        if (isTypeString(orderLine.attributes.certain_pay_channels)) {
            const certain_pay_channels = JSON.parse(orderLine.attributes.certain_pay_channels);
            if (isNotEmptyArray(certain_pay_channels)) {
                for (let index = 0; index < certain_pay_channels.length; index++) {
                    const element = certain_pay_channels[index];
                    if (isNonEmptyObject(element) && isTypeString(element.attributes)) {
                        const attributes = JSON.parse(element.attributes);
                        if (isNonEmptyObject(attributes) && isTypeString(attributes.pay_with_password_free) && attributes.pay_with_password_free === 'true') {
                            const payChannelAttr = {};
                            payChannelAttr.attributes = attributes;
                            payChannelAttr.payChannelId = element.payChannelId;
                            payChannelAttrs.push(payChannelAttr);
                        }
                    }
                }
            }
        }
    }
    return payChannelAttrs;
}

/**
 * 是否是免密支付
 * @param payChannels
 * @param payChannel
 * @returns
 */
export function noSecretPayChannelFromChannels(payChannels, payChannel) {
    for (let index = 0; index < payChannels.length; index++) {
        const element = payChannels[index];
        if (element.payChannelId && payChannel && payChannel.payChannelId && isTypeString(payChannel.payChannelId)) {
            let noSecretPayChannelId = element.payChannelId;
            if (!isTypeString(noSecretPayChannelId)) {
                noSecretPayChannelId = noSecretPayChannelId.toString();
            }
            if (payChannel.payChannelId.toString() === noSecretPayChannelId) {
                return element;
            }
        }
    }

    return null;
}


// 获取订单结果
export const checkOrderPayResult = (orderId, callback) => {
    const { MAX_POLL_TIMES, POLL_INTERVAL } = PAY_RESULT_QUERY_INFO;
    startPoolOrder(orderId, MAX_POLL_TIMES, POLL_INTERVAL, callback);
}

// 构建支付成功、失败 预警上报数据
export function buildAlarmInfo(orderInfo, res) {
    const { payChannel, orderLine } = orderInfo;
    return {
        goPayFrom: orderInfo?.goPayFrom || '',
        orderId: orderInfo?.orderId || '',
        res: res || '',
        productInfo: {
            payPrice: orderLine?.payPrice || '',
            productId: orderLine?.productId || '',
            productKey: orderLine?.productKey || '',
            promotionKey: orderLine?.promotionKey || '',
            quantity: orderLine?.quantity || '',
            tagPrice: orderLine?.tagPrice || '',
        },
        payChannelInfo: {
            payTitle: payChannel?.payTitle || '',
            payChannelId: payChannel?.payChannelId || '',
            terminal: payChannel?.terminal || '',
            cycleBuySupported: payChannel?.cycleBuySupported || '',
        },
    };
}

/**
 * 执行轮询探测支付结果
 * @param {String} orderId
 * @param {*} orderLine
 * @param {*} payChannel
 * @param {*} goPayFrom
 * @param {*} context
 * @returns
 */
export function checkOrderStatus(orderId, orderLine, payChannel, goPayFrom) {
    return new Promise((resolve, reject) => {
        checkOrderPayResult(orderId, (result) => {
            if (result.type === 'alert') {
                const alertParams = { dialogParams: result.alert, type: 'custom', };
                if (result?.alert?.url) {
                    Object.assign(alertParams.dialogParams, {
                        cancelText: '我知道了',
                        action: {
                            onClick: (type) => {
                                if (type === 'confirm') {
                                    doAction({
                                        type: 'JUMP_TO_URL',
                                        value: result.alert.url,
                                        from: '轮询订单异常弹框',
                                    });
                                }
                            }
                        }
                    });
                }
                sendBroadcast('payAlert');
                sendBroadcast('showDialog', alertParams);
                // 轮询结果alert失败
                reject({
                    status: 'fail',
                    type: 'alert',
                    msg: '支付结果弹框alert'
                });
                sendAlarm('6573', `${getPlatformName()}-支付返回收银台检查订单alert,curOrderGoPayFrom=${goPayFrom}`, result, payChannel, orderLine, orderId);
            } else if (result.type === 'success') {
                ctx.showToast({
                    title: '付款成功',
                    icon: 'success',
                    duration: 2000
                });
                resolve({
                    status: 'success',
                    type: 'success',
                    msg: '支付结果success'
                });
                sendAlarm('6572', `${getPlatformName()}-支付返回收银台检查订单success,curOrderGoPayFrom=${goPayFrom}`, result, payChannel, orderLine, orderId);
            } else {
                reject({
                    status: 'fail',
                    type: 'fail',
                    result,
                });
            }
        });
    });
}

