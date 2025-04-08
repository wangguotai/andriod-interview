const app = getApp();

let pool_timer = null; // 轮询timer
let pool_max_count = 5; // 最大轮询次数
let pool_current_time = 0; // 已轮询次数
let pool_interval = 2000; // 轮询间隔ms
// 确保结果的order与当前请求一致
let currentOrder = '';

/**
 * 轮询订单
 * @param orderId
 * @param maxPollTimes
 * @param pollInterval
 * @param callback
 */
export function startPoolOrder(orderId, maxPollTimes, pollInterval, callback) {

    startPool([orderId], '0', maxPollTimes, pollInterval, callback);
}

/**
 * 订单轮询
 * @param orderIds                   订单id数组
 * @param detailType String          0/ 1/ 2
 * @param maxCount Number            最大轮询次数
 * @param interval Long:毫秒数        每次轮询间隔时间
 * @param callback Function          结果回调
 */
function startPool(orderIds = [], detailType, maxCount, interval, callback) {
    if (pool_timer) {
        clearTimeout(pool_timer);
    }
    pool_current_time = 0;
    currentOrder = orderIds;
    pool_max_count = maxCount;
    pool_interval = interval;
    checkOrderState(orderIds, detailType, callback);
}

/**
 * 订单支付状态轮询
 * 轮询规则：每2s轮询一次订单详情接口，如果订单状态为已支付，自动跳转到支付成功页
 * @param {Array<String>} orderIds
 * @param {String} detailType
 * @param {Function} callback
 */
function checkOrderState(orderIds, detailType = '0', callback) {
    const params = {
        orderIds: orderIds,
        showPage: 'ORDER_DETAIL',
        detailType: detailType,
    }
    // 订单轮询成功回调
    const successCallback = (res) => {
        if (orderIds === currentOrder) {
            if (res && res.data) {
                if (detailType === '0') {
                    const result = (res.data.result && res.data.result[0]) || {};
                    // 是否为关联订单
                    if (result && result.attributes && (result.attributes.subsequent_order_param || result.attributes.subsequent_order_id)) {
                        //该值不一定有，看服务端创建关联订单的速度，如果没有，也开始调用关联订单接口，detailType的值改为'2'
                        const subsequent_order_id = result.attributes.subsequent_order_id;
                        let currentIds = Object.assign(orderIds) || [];
                        if (subsequent_order_id) {
                            let index = currentIds.indexOf(subsequent_order_id, 0);
                            //不包含此订单的时候再添加到订单数组中
                            if (index < 0) {
                                currentIds.push(subsequent_order_id);
                            }
                        }
                        // 重新查询关联订单
                        startPool(currentIds, '2', pool_max_count, pool_interval, callback);
                    } else {
                        let paySuccessCount = 0;
                        let orderCount = res.data.result && res.data.result.length;
                        for (let i = 0; i < orderCount; i++) {
                            let orderDetail = res.data.result[i];
                            let mPayOrder = orderDetail.payOrder || {};
                            if (mPayOrder.payState === 3 || mPayOrder.payState === '3' || mPayOrder.payState === 6 || mPayOrder.payState === '6') {
                                paySuccessCount = paySuccessCount + 1;
                            }
                        }
                        if (paySuccessCount > 0 && paySuccessCount === orderCount) {
                            let callbackParams = {
                                type: 'success',
                                message: (res && res.data && res.data.orderStateDesc) || '支付成功',
                                res: res,
                                detailType: detailType
                            };
                            callback && callback(callbackParams);
                        } else {
                            startPoolOrderState(orderIds, detailType, res, callback);
                        }
                    }
                } else {
                    // 关联订单
                    if (res.data.combinedOrder === true || res.data.combinedOrder === 'true') {
                        if (res.data.orderState === '3' || res.data.orderState === 3) {
                            let callbackParams = {
                                type: 'success',
                                message: (res && res.data && res.data.orderStateDesc) || '支付成功',
                                res: res,
                                detailType: detailType
                            };
                            callback && callback(callbackParams);
                        } else {
                            const currentIds = Object.assign(orderIds) || [];
                            const result = (res.data.result && res.data.result[0]) || {};
                            let subsequent_order_id = null;
                            if (result && result.attributes && result.attributes.subsequent_order_id) {
                                subsequent_order_id = result.attributes.subsequent_order_id;
                            }
                            if (subsequent_order_id) {
                                let index = currentIds.indexOf(subsequent_order_id, 0);
                                if (index < 0) {
                                    //目前请求的订单数组中不包含关联订单的订单ID；
                                    currentIds.push(subsequent_order_id);
                                }
                            }
                            startPoolOrderState(currentIds, detailType, res, callback);
                        }
                    } else {
                        // 非关联订单
                        let paySuccessCount = 0;
                        let orderCount = res.data.result.length;
                        for (let i = 0; i < orderCount; i++) {
                            let orderDetail = res.data.result[i];
                            let mPayOrder = orderDetail.payOrder || {};
                            if (mPayOrder.payState === 3 || mPayOrder.payState === '3' || mPayOrder.payState === 6 || mPayOrder.payState === '6') {
                                paySuccessCount = paySuccessCount + 1;
                            }
                        }
                        if (paySuccessCount > 0 && paySuccessCount === orderCount) {
                            let callbackParams = {
                                type: 'success',
                                message: (res && res.data && res.data.orderStateDesc) || '支付成功',
                                res: res,
                                detailType: detailType
                            };
                            callback && callback(callbackParams);
                        } else {
                            startPoolOrderState(orderIds, detailType, res, callback);
                        }
                    }
                }
            }
        }

    };

    const failCallback = (res) => {
        if(orderIds === currentOrder) {
            startPoolOrderState(orderIds, detailType, res, callback);
        } else {
            let callbackParams = {
                type: 'fail',
                message: '查询失败',
                res: res,
                detailType: detailType
              };
              callback && callback(callbackParams);
        }
    };
    const { mtop } = app.cashier;
    mtop.requestXmiApi('mtop.alidme.xtop.trade.order.combine.detail', params, successCallback, failCallback);

}

/**
 * 如果首次查询订单状态未成功，则执行轮询逻辑
 * @param orderIds
 * @param detailType
 * @param res
 * @param callback
 */
function startPoolOrderState(orderIds, detailType, res, callback) {
    if (pool_current_time < pool_max_count) {
        clearTimeout(pool_timer);
        pool_timer = setTimeout(function () {
            pool_current_time++;
            checkOrderState(orderIds, detailType, callback);
        }, pool_interval);
    } else {
        clearTimeout(pool_timer);
        const result = (res.data.result && res.data.result[0]) || {};
        const isResign = result.attributes && ('is_resign' in result.attributes);
        let callbackParams;
        if (isResign && result.paySuccessInfo) {
            callbackParams = {
                type: 'alert',
                message: '重复签约订单',
                alert: {
                    title: '提示',
                    contentText: result.paySuccessInfo.paySuccessTip || '未知结果，请到订单详情页查看订单状态',
                    confirmText: result.paySuccessInfo.paySuccessBtnTitle || '我知道了',
                    url: result.paySuccessInfo.paySuccessBtnUrl || '',
                },
                res: result
            };
        } else {
            let message = (res && res.data && res.data.orderStateDesc) || '支付查询失败';
            callbackParams = {
                type: 'fail',
                message: message,
                res: result,
                detailType: detailType
            };
        }
        callback && callback(callbackParams);
    }
}
