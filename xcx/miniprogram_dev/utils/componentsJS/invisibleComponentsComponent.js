/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/

import { sendBroadcast } from "../nativeCashier/BroadcastReceiver";
import { isNonEmptyObject, isNotEmptyArray } from "../nativeCashier/data/index";
import CashierDataManager from "../nativeCashier/store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../nativeCashier/store/constants";

// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const invisibleComponent = {

    /**
     * 组件的属性列表
     */
    properties: {
        componentInfo: {
            type: Object,
            value: {},
            observer: function (newVal) {
                if (newVal) {
                    const { dataSource } = newVal;
                    const enablePay = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.ENABLE_CHECK_CONTINUE_PAY_ORDER);
                    if (enablePay && isNotEmptyArray(dataSource) && isNonEmptyObject(dataSource[0].data) && isNonEmptyObject(dataSource[0].data.order)) {
                        sendBroadcast('showDialog', {
                            type: 'unfinishedOrderModule',
                            dialogParams: {
                                unfinishedOrderModuleData: dataSource[0].data,
                            },
                        });
                        // 继续支付弹框，只展示一次
                        CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.ENABLE_CHECK_CONTINUE_PAY_ORDER, false);
                    }

                }
            }
        },
    },

    /**
     * 组件的初始数据
     */
    data: {},

    /**
     * 组件的方法列表
     */
    methods: {}
};
