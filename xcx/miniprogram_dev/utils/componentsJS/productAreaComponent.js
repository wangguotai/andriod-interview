/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/

import { getElementAtIndex, } from "../nativeCashier/data/index";
import { equalsPayChannel, getOrderLines, getProductViewRenderData } from "../nativeCashier/helper/productArea/ProductHelper";
import { registerListener, sendBroadcast, unregisterListener } from "../nativeCashier/BroadcastReceiver";
import CashierDataManager from "../nativeCashier/store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../nativeCashier/store/constants";
import { sendClick, sendExp } from "../nativeCashier/track/TrackHelper";

// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const productAreaComponent = {

    externalClasses: ['external-price-font-class'],

    /**
     * 组件的属性列表
     */
    properties: {
        componentInfo: {
            type: Object,
            value: {},
            observer: function (newVal) {
                if (newVal) {
                    // 1. 处理商品数据
                    const { orderLines, defSelectedProductIndex } = getOrderLines(newVal.dataSource);
                    //  设置选中商品
                    const orderLine = getElementAtIndex(orderLines, defSelectedProductIndex);
                    // 当前选中的商品
                    // const product = getProduct(orderLine);
                    if (orderLine) {
                        this.data.selectedIndex = defSelectedProductIndex;
                        orderLine.actionSourceType = 'product_render';
                        // 触发商品变化
                        this.onProductChanged(orderLine);
                        sendExp({}, orderLine.action);
                    }
                    const formatProductList = getProductViewRenderData(orderLines, this.data.selectedIndex, this.data.payChannel, newVal.type, newVal.data?.hintBottomTips === 1);
                    this.setData({
                        isHalfCashier: newVal.isHalfCashier,
                        isLogined: newVal.isLogined,
                        itemCount: formatProductList.length,
                        productList: formatProductList,
                        orderLines,
                        classRoot: formatProductList.length > 1
                            ? 'multi-products-container'
                            : 'single-product-container',
                        action: newVal.action,
                        isSvipCashier: newVal.isSvipCashier,
                    });

                }

            }
        },
    },


    /**
     * 组件的初始数据
     */
    data: {
        classRoot: 'single-product-container',
        selectedIndex: 0,
        payChannel: null,
        selectMonthlySubs: false,
        isSvipCashier: false,
    },
    lifetimes: {
        create() {
            // 监听支付渠道变化并更新
            registerListener('onChannelChanged', 'payProduct', (channel) => {
                if (!equalsPayChannel(channel, this.data.payChannel)) {
                    this.data.payChannel = channel;
                }
            });
        },
        attached() {
            unregisterListener('onChannelChanged', 'payProduct');
        },
    },

    /**
     * 组件的方法列表
     */
    methods: {
        onProductChanged(orderLine) {
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.ORDER_LINE, orderLine);
            sendBroadcast('onProductChanged', orderLine)
        },

        handleProductSelect(e) {
            const dataset = e.currentTarget.dataset
            if (this.data.selectedIndex !== dataset.index) {
                this.setData({
                    productList: this.data.productList.map((item, index) => {
                        item.isSelected = index === dataset.index;
                        return item;
                    }),
                    selectMonthlySubs: false, // 将连续包月选项置为初始值
                    // 更新选中商品下标
                    selectedIndex: dataset.index,
                });
                const orderLine = getElementAtIndex(this.data.orderLines, this.data.selectedIndex);
                this.onProductChanged(orderLine);
                sendClick({}, orderLine?.action);
            }
        },
        handleProductDesc(e) {
            const { title, popInfo } = e.currentTarget.dataset;
            sendBroadcast('showDialog', {
                justifyContent: 'center',
                type: 'custom',
                dialogParams: {
                    title: title,
                    content: popInfo,
                    confirmText: '我知道了',
                },
                isCancelable: true,
            });
        },

        // 7.30 对比主客逻辑 连续包月在商品区一直未启用，暂不考虑
        // handleCycleBugClick(e) {
        //     const {selected} = e.currentTarget.dataset;
        //     this.setData({
        //         selectMonthlySubs: !this.data.selectMonthlySubs,
        //     });
        //     sendBroadcast('onCycleBuyChange', selected);
        //     const clickParams  = {
        //         spm: '20140719.manual.product.cycleBuy',
        //         track_info: {
        //             product_id: product && product.productId,
        //             sku_id: product && product.skuId,
        //         },
        //     };
        //     sendClick(clickParams, null);
        // }
    }
};
