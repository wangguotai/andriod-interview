import { getPlatformName } from "../../../context/contextHelper";
import { sendBroadcast } from "../../BroadcastReceiver";
import { alarmEventWithPreDesc } from "../../track/TrackHelper";

/**
 * 抖音小程序支付
 * @param cycleBuyType 1 - 周期购
 * @param payChannelResponse
 * @param byteAuthorization
 */
export function goDouyinMiniPay(cycleBuyType, payChannelResponse, byteAuthorization) {
    if (cycleBuyType === '1') {
        ttCreateSignOrder(payChannelResponse.data, byteAuthorization);
    } else {
        ttRequestOrder(payChannelResponse.data, byteAuthorization);
    }
}

/**
 * 抖音小程序支付 - 创建普通订单
 * @param data
 * @param byteAuthorization
 */
function ttRequestOrder(data, byteAuthorization) {
    // 请求抖音小程序预下单，返回订单号和订单信息
    tt.requestOrder({
        data, // 请勿在前端对data做任何处理
        byteAuthorization, // 请勿在前端对byteAuthorization做任何处理
        success: res => {
            console.log('WGT ==== 7008 tt.requestOrder 成功', res);
            ttGetOrderPayment(res.orderId);
            alarmEventWithPreDesc('7008', `${getPlatformName()}-预下单调用成功`, res);
        },
        fail: res => {
            console.log('WGT ==== 7009 tt.requestOrder 失败', res);
            sendBroadcast('payFail', res);
            alarmEventWithPreDesc('7009', `${getPlatformName()}-预下单调用失败`, res);
        }
    })
}

/** * 抖音小程序普通支付 - 拉起支付收银台
 * @param orderId
 */
function ttGetOrderPayment(orderId) {
    tt.getOrderPayment({
        orderId,
        success: (res) => {
            sendBroadcast('paySuccess', res);
            alarmEventWithPreDesc('7010', `${getPlatformName()}-拉起收银台成功`, res);
            console.log('WGT ==== 7010 tt.getOrderPayment 成功', res);
        },
        fail: (res) => {
            sendBroadcast('payFail', res);
            console.log('WGT ==== 7011 tt.getOrderPayment 失败', res);
            alarmEventWithPreDesc('7011', `${getPlatformName()}-拉起收银台失败`, res);
        }
    })
}

/**
 * 抖音小程序支付 - 周期代扣签约 - 生成签约订单
 * @param data
 * @param byteAuthorization
 */
function ttCreateSignOrder(data, byteAuthorization) {
    tt.createSignOrder({
        businessType: 2,
        data, // 请勿在前端对data做任何处理
        byteAuthorization, // 请勿在前端对byteAuthorization做任何处理
        success: (res) => {
            console.log('WGT ==== 7012 tt.createSignOrder 成功', res);
            ttSign(res.authOrderId, res.payOrderId);
            alarmEventWithPreDesc('7012', `${getPlatformName()}-签约调用成功`, res);
        },
        fail: (res) => {
            console.log('tt.createSignOrder 失败', res);
            sendBroadcast('payFail', res);
            alarmEventWithPreDesc('7013', `${getPlatformName()}-签约调用失败`, res);
        }
    });
}


/**
 * 抖音小程序支付 - 周期代扣签约 - 唤起签约页面
 * @param authOrderId
 * @param payOrderId
 */
function ttSign(orderId, payOrderId){
    tt.sign({
        businessType: 2,
        orderId,
        success: (res) => {
            console.log('tt.sign 成功', res);
            sendBroadcast('paySuccess', res);
            alarmEventWithPreDesc('7014', `${getPlatformName()}-签约成功`, res);
        },
        fail: (res) => {
            console.log('tt.sign 失败', res);
            sendBroadcast('payFail', res);
            alarmEventWithPreDesc('7015', `${getPlatformName()}-签约失败`, res);
        },
    })
}
