/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/

// components/cashier/paymentMethod/paymentMethod.js
import { registerListener, unregisterListener } from "../nativeCashier/BroadcastReceiver";
import { parseToBool, parseToJson } from "../nativeCashier/data/index";
import { handlePayChannels } from "../nativeCashier/helper/paymentChannel/channelHelper";
import CashierDataManager from "../nativeCashier/store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../nativeCashier/store/constants";
import { isBaiduAndIqiyiMiniProgram } from "../context/contextHelper";
import { doAction } from "../nativeCashier/eventHandler/index";
import { sendExp } from "../nativeCashier/track/TrackHelper";

function initChannelInfo(orderLine) {
    const { allPayChannels, visiblePayChannels, channelIndex, isShowFold } = handlePayChannels(
        this.data.dataSource,
        orderLine,
    );
    // 当前商品是否为连续包月商品
    const recurringMonthlyProduct = parseToBool(orderLine.attributes?.cycle_buy_supported);
    allPayChannels?.forEach((channel, index) => {
        channel.isSelected = channelIndex === index;
        // 如果当前选中的商品上连续包商品，并且支付渠道携带前置签约声明，就展示前置签约声明
        const attributes = parseToJson(channel.attributes || '{}');
        if (attributes && recurringMonthlyProduct && attributes.show_protocol_on_pay_channel) {
            const protocol_on_pay_channel = parseToJson(attributes.show_protocol_on_pay_channel);
            if (protocol_on_pay_channel) {
                channel.protocol_text = protocol_on_pay_channel.protocol_text;
                channel.protocol_url = protocol_on_pay_channel.protocol_url;
                channel.selected_by_default = parseToBool(protocol_on_pay_channel.selected_by_default);
            }
        }
        sendExp({}, channel.action);
    });
    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.CURRENT_PAY_CHANNEL, allPayChannels?.[channelIndex]);

    // *DIFF*
    const data = {
        product: orderLine,
        selectIndex: channelIndex,
        allPayChannels,
        visiblePayChannels,
        // 当前商品是否为连续包月商品
        recurringMonthlyProduct,
        isShowFold,
    }
    if (isBaiduAndIqiyiMiniProgram()) {
        data.channels = isShowFold ? visiblePayChannels : allPayChannels;
    }
    this.setData(data);
}
// 避免重复渲染
let dataInitialized = false;
// let blockTaskQueue = [];

// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const paymentChannelComponent = {

    /**
     * 组件的属性列表
     */
    properties: {
        componentInfo: {
            type: Object,
            value: {},
            observer: function (newVal) {
                if (newVal) {
                    this.setData({
                        dataSource: newVal.dataSource,
                        isSvipCashier: newVal.isSvipCashier,
                    });
                    dataInitialized = true;
                    const orderLine = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.ORDER_LINE);
                    if (orderLine) {
                        initChannelInfo.call(this, orderLine);
                    }
                }
            }
        },

    },

    lifetimes: {
        attached() {
            // 与组件被卸载时一一对应，避免占据内存
            registerListener('onProductChanged', 'payChannel', orderLine => {
                if (dataInitialized) {
                    initChannelInfo.call(this, orderLine);
                }
            });
        },
        detached() {
            unregisterListener('onProductChanged', 'payChannel');
        }
    },
    /**
     * 组件的初始数据
     */
    data: {
        classRoot: 'payment-channel',
        dataSource: [],
        orderLine: null,
        allPayChannels: [],
        visiblePayChannels: [],
        isSvipCashier: false,
    },

    /**
     * 组件的方法列表
     */
    methods: {

        /**
         * 支付渠道选择
         * 1. 如果点击了自身，则不处理
         * 2. 如果选中了其他支付渠道，则切换选中
         */
        handleChannelSelect(e) {
            const { channel } = e.currentTarget.dataset;
            const mCurrentPayChannel = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CURRENT_PAY_CHANNEL);
            if (mCurrentPayChannel?.payChannelId?.toString() === channel?.payChannelId?.toString()) {
                return;
            }

            const { visiblePayChannels, allPayChannels, isShowFold } = this.data;
            // 更新支付渠道选择状态
            // 调整为直接对 所有支付渠道进行调整， 避免因引用问题导致的异常
            let newSelectedChannel = channel;
            allPayChannels?.forEach(item => {
                if (item?.payChannelId?.toString() === channel?.payChannelId?.toString()) {
                    item.isSelected = true;
                    newSelectedChannel = item;
                } else {
                    item.isSelected = false;
                }
            });
            const updateData = {
                allPayChannels,
            }
            // 如果是折叠状态，则需要更新可见支付渠道
            if (isShowFold) {
                visiblePayChannels?.forEach(item => {
                    if (item?.payChannelId?.toString() === channel?.payChannelId?.toString()) {
                        item.isSelected = true;
                        newSelectedChannel = item;
                    } else {
                        item.isSelected = false;
                    }
                });
                updateData.visiblePayChannels = visiblePayChannels;
            }

            // *DIFF* === 百度小程序不支持动态s-for语法，需要作为静态模板编译
            if (isBaiduAndIqiyiMiniProgram()) {
                updateData.channels = isShowFold ? visiblePayChannels : allPayChannels;
            }

            this.setData(updateData);
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.CURRENT_PAY_CHANNEL, newSelectedChannel);
        },

        handleProtocol(e) {
            const { protocolUrl } = e.currentTarget.dataset;
            if (protocolUrl) {
                doAction({
                    type: 'JUMP_TO_URL',
                    value: protocolUrl,
                    from: 'payChannel-jumpToUrl',
                });
            }
        },
        handleChannelExpand(e) {
            const newState = !this.data.isShowFold;
            const data = {
                isShowFold: newState,
            }
            // *DIFF* == reason: 百度小程序不支持动态s-for语法，需要作为静态模板编译 ==
            if (isBaiduAndIqiyiMiniProgram()) {
                data.channels = newState ? this.data.visiblePayChannels : this.data.allPayChannels
            }
            this.setData(data);
        },
    }
};
