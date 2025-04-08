/**************************************************
 * 迁移主客收银台全局广播模块
 *  1. 主客 initApp中的
 *                  商品改变、
 *                  支付渠道改变
 *                  虚拟组件数据（下单挽留）
 *                  支付渠道优惠文案拉取成功
 *      会使用到本模块进行监听回调
 **************************************************/
import { alarmEventWithPreDesc } from "./track/TrackHelper";


const mListeners = {}

/** * 注册监听
 *
 * @param {String} action 广播类型
 * @param {String} key  子分类
 * @param {function} callback 回调方法
 */
export const registerListener = (action, key, callback) => {
    if (action) {
        if (mListeners[action]) {
            mListeners[action][key] = callback
        } else {
            mListeners[action] = {}
            mListeners[action][key] = callback
        }
    }
}

/**
 * 从监听器列表中移除指定动作的监听器实例。
 * @param {string} actionName 监听的动作类型。
 * @param {string|number} listenerKey 监听器的唯一标识符。
 */
export const unregisterListener = (actionName, listenerKey) => {
    // 尝试从监听器列表中删除指定的动作监听器
    try {
        if (actionName && mListeners[actionName] && mListeners[actionName][listenerKey]) {
            delete mListeners[actionName][listenerKey];
        }
    } catch (error) {
        // 记录错误信息以便于调试
        console.error('Error while unregistering listener:', error);
    }
};

/**
 * 发送广播
 * @param {String} action 广播类型
 * @param {Object} event 广播内容
 */
export const sendBroadcast = (action, event) => {
    if (action) {
        const listener = mListeners[action];
        for (const key in listener) {
            const callback = listener[key];
            if (typeof callback === 'function') {
                try {
                    callback(event);
                } catch (error) {
                    console.error(`Error while sending broadcast: action=${action}, event=${JSON.stringify(event)}, key=${key}, error=${error}`);
                    alarmEventWithPreDesc('7003', `[Error] ==== BroadcastReceiver::sendBroadcast {action=${action}, event=${JSON.stringify(event)}}`, error);
                }
            }
        }
    }
};
