/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/
// 封装多种dialog弹窗 1. 自定义弹窗(居中展示提示内容) 2. 优惠明细弹框（底部展示）

import { isNotEmptyArray } from "../nativeCashier/data/index";
import { getExtend } from "../nativeCashier/request/dataHelper";
import { alarmEvent, alarmEventWithPreDesc, sendClick, sendExp } from "../nativeCashier/track/TrackHelper";
import CashierDataManager from "../nativeCashier/store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../nativeCashier/store/constants";
import { doAction } from "../nativeCashier/eventHandler/index";
import { sendBroadcast } from "../nativeCashier/BroadcastReceiver";
import { closeOrder } from "../nativeCashier/request/requestHelper";
import { getPayChannelBylId } from "../nativeCashier/helper/payment/PayManager";
import { getPlatformContext, getPlatformName } from "../context/contextHelper";
import { COMMON_CONFIG } from "../config/cashierConfig";
import { deepClone } from "../nativeCashier/common/index";

const ctx = getPlatformContext();

const sendDialogExp = (spmC, spmD) => {
    if (getExtend()) {
        const pageSpm = getExtend().pageSPM;
        const expParams = {};
        expParams.spm = `${pageSpm}.${spmC}.${spmD}`;
        sendExp(expParams, {});
    }
};

const sendDialogClick = (spmC, spmD) => {
    if (getExtend()) {
        const pageSpm = getExtend().pageSPM;
        const clickParams = {};
        clickParams.spm = `${pageSpm}.${spmC}.${spmD}`;
        sendClick(clickParams, {});
    }
};

// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const dialogComponent = {

    /**
     * 组件的属性列表
     */
    properties: {
        dialogInfo: {
            type: Object,
            value: {},
            observer(dialogInfo) {
                if (dialogInfo) {
                    try {
                        const {dialogParams, type} = dialogInfo;
                        // 重置状态避免异常渲染, 使用对象合并，避免多次设置setData
                        const initialData = {
                            isCustom: false,
                            isDiscount: false,
                            isPrePayAgreement: false,
                        }
                        if (type === 'custom') {
                            const {
                                title,
                                content,
                                contentText,
                                cancelText,
                                confirmText,
                                action,
                            } = dialogParams;
                            this.setData({
                                ...initialData,
                                isCustom: true,
                                title,  // 标题
                                content, // 内容列表
                                contentText, // 内容文本
                                cancelText, // 取消
                                confirmText, // 确认
                                onClick: action && action.onClick, // 回调事件
                            });
                        } else if (type === 'discount_detail') {
                            const {promotion = {}, orderLine = {}} = dialogParams;
                            // 优惠明细弹窗内容列表
                            const renderList = [];

                            // 商品原价
                            renderList.push({
                                title: '商品',
                                itemName: orderLine.product?.title || '',
                                itemPrice: {
                                    text: `￥${(orderLine.tagPrice) / 100}`,
                                }
                            });

                            // 商品优惠
                            if (promotion && isNotEmptyArray(promotion.activities)) {
                                promotion.activities.forEach(item => {
                                    renderList.push({
                                        title: '优惠',
                                        itemName: item.name || '',
                                        itemPrice: {
                                            text: `-￥${(item.bonusUnitPrice) / 100}`,
                                            style: 'font-size: 30rpx; color: #FF6F3B; text-align: right;'
                                        }
                                    });
                                });
                            }

                            // 合计支付
                            let totalPayment = null;
                            if (promotion && promotion.activities && Array.isArray(promotion.activities) && promotion.activities.length > 0) {
                                let totalPayPrice = promotion.promUnitPrice / 100;
                                totalPayment = {
                                    title: '合计支付',
                                    price: `￥${totalPayPrice}`
                                }
                            }
                            this.setData({
                                ...initialData,
                                isDiscount: true,
                                renderList,
                                totalPayment,
                            });
                        } else if (type === 'prePayAgreement') {
                            const {callbackFuncKey} = dialogParams;
                            const agreementPayBtnData = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.AGREEMENT_PAY_BTN_DATA)?.nodes
                            this.setData({
                                ...initialData,
                                isPrePayAgreement: true,
                                prePayAgreementDialogInfo: {
                                    title: '确认开通',
                                    subtitle: '我已阅读并同意',
                                    okButtonText: '继续开通',
                                    agreementPayBtnData,
                                    callbackFuncKey,
                                },
                            })
                        } else if (type === 'unfinishedOrderModule') {
                            const {unfinishedOrderModuleData} = dialogParams;
                            const order = unfinishedOrderModuleData?.order;
                            if (order) {

                                let unfinishedOrderId = '';
                                let unfinishedOrderIds = '';

                                let unfinishedOrderPayUrl = '';
                                let tradeId = '';

                                let unfinishedOrderProduct;
                                let unfinishedOrderProductTitle = '';
                                let unfinishedOrderReceivings = [];
                                let unfinishedOrderProductPayPrice = '';
                                let unfinishedOrderPayChannelDesc = '';
                                let unfinishedOrderProductPayChannelId = '';

                                // realPromotion提供继续支付的参数
                                const realPromotion = [];
                                const unfinishedOrder = order.orderDetail;
                                unfinishedOrderIds = order.orderIds;
                                if (unfinishedOrder) {
                                    unfinishedOrderId = unfinishedOrder.orderId;
                                    if (unfinishedOrder.attributes) {
                                        tradeId = unfinishedOrder.attributes.tid;
                                    }

                                    // 刷新包含优惠的未支付订单数据
                                    const {productMap} = unfinishedOrder;
                                    for (const key in productMap) {
                                        if (productMap.hasOwnProperty(key)) {
                                            unfinishedOrderProduct = productMap[key];
                                        }
                                    }
                                    if (unfinishedOrderProduct) {
                                        unfinishedOrderProductTitle = unfinishedOrderProduct.title;
                                    }
                                    const {promotionMap} = unfinishedOrder;
                                    let promotion;
                                    for (const key in promotionMap) {
                                        if (promotionMap.hasOwnProperty(key)) {
                                            promotion = promotionMap[key];
                                        }
                                    }
                                    if (promotion) {
                                        unfinishedOrderReceivings = promotion.receivings;
                                        if (unfinishedOrderReceivings) {
                                            unfinishedOrderReceivings.forEach((receiving) => {
                                                if (receiving) {
                                                    if (receiving.activityId) {
                                                        realPromotion.push({
                                                            activityId: receiving.activityId,
                                                            receivingId: receiving.receivingId
                                                        });
                                                    }
                                                }
                                            });
                                        }
                                    }
                                }

                                if (unfinishedOrder.payOrder) {
                                    unfinishedOrderPayChannelDesc = unfinishedOrder.payOrder.payChannelDesc;
                                    unfinishedOrderProductPayPrice = unfinishedOrder.payOrder.payPrice;
                                    unfinishedOrderPayUrl = unfinishedOrder.payOrder.payUrl;
                                    unfinishedOrderProductPayChannelId = unfinishedOrder.payOrder.payChannel;
                                }
                                this.setData({
                                    isUnfinishedOrderModule: true,
                                    orderInfo: {
                                        unfinishedOrderProductTitle,
                                        unfinishedOrderReceivings,
                                        unfinishedOrderProductPayPrice: `${parseInt(unfinishedOrderProductPayPrice) / 100}元`,
                                        unfinishedOrderPayChannelDesc,
                                        unfinishedOrderIds,
                                        unfinishedOrderProduct,
                                        unfinishedOrderId,
                                        realPromotion,
                                        tradeId,
                                        unfinishedOrderProductPayChannelId,
                                        unfinishedOrderPayUrl,
                                    }
                                });
                                alarmEventWithPreDesc('7017', '下发继续支付弹框组件', {
                                    unfinishedOrderProductPayChannelId,
                                    unfinishedOrderId,
                                    tradeId,
                                });
                            } else {
                                alarmEventWithPreDesc('7019', '继续支付异常，下发数据无单号', unfinishedOrderModuleData);
                                console.error('[dialog][unfinishedOrderModule] order is null');
                                this.triggerEvent('closeDialog');
                            }


                        }

                    } catch (e) {
                        console.error(e);
                    }

                }
            }
        }
    },

    /**
     * 组件的初始数据
     */
    data: {
        isCustom: false,
        isDiscount: false,
    },
    /**
     * 组件的方法列表
     */
    methods: {
        // custom弹框，如果没有type标识单按钮，提示信息，有type标识多按钮，并回调点击事件
        onConfirm(e) {
            const {type} = e.currentTarget.dataset;
            if (type) {
                this.data.onClick && this.data.onClick(type);
            }
            this.triggerEvent('closeDialog');

        },
        // 回调支付协议传入取消事件
        onClosePrePayAgreement() {
            this.triggerEvent('closeDialog');
            const onClick = CashierDataManager.get(this.data?.prePayAgreementDialogInfo?.callbackFuncKey);
            onClick && onClick({type: 'cancel'});
            sendDialogClick('popAgreement', 'refuse');
        },
        onClickAgreement(e) {
            const {url} = e.currentTarget.dataset;
            doAction({
                type: 'JUMP_TO_URL',
                value: url,
                from: 'dialog-payAgreement',
            });
        },
        // 回调支付协议继续支付
        onPreAgreementPayContinue() {
            this.triggerEvent('closeDialog');
            const onClick = CashierDataManager.get(this.data?.prePayAgreementDialogInfo?.callbackFuncKey);
            onClick && onClick({type: 'ok'});
            sendDialogClick('popAgreement', 'accept');
        },

        // 未支付订单重新下单前需要关闭订单
        onCloseOrder() {
            try {
                const {unfinishedOrderIds} = this.data.orderInfo;
                closeOrder(unfinishedOrderIds, null, null, (res) => {
                    if (res.type !== 'success') {
                        ctx.showToast({title: '关闭订单失败', icon: 'none'});
                    }
                    sendBroadcast('requestRenderData', res);
                    this.triggerEvent('closeDialog');
                });
                alarmEvent('7018', '继续支付-用户关闭继续支付弹框');
            } catch (e) {
                console.error(e);
                this.triggerEvent('closeDialog');
            }
        },
        // 未支付订单继续支付
        onContinuePay() {
            try {
                const {
                    unfinishedOrderProduct,
                    unfinishedOrderId,
                    realPromotion,
                    tradeId,
                    unfinishedOrderProductPayChannelId,
                } = this.data.orderInfo;
                if (unfinishedOrderProduct && unfinishedOrderId) {
                    const extend = deepClone(getExtend() || {});
                    extend.goPayFrom = COMMON_CONFIG.GO_PAY_FROM.UNFINISHED_ORDER_DIALOG;
                    extend.unfinishedReceivings = realPromotion;
                    extend.params.attributes.order_id = unfinishedOrderId;
                    extend.unfinishedOrderId = unfinishedOrderId;
                    extend.pageSPM = 'a2h07.13575842';
                    extend.traceId = tradeId;
                    extend.sceneType = '';
                    const orderLine = {
                        productId: unfinishedOrderProduct.id,
                        skuId: unfinishedOrderProduct.skuId,
                        attributes: unfinishedOrderProduct.attributes,
                        product: unfinishedOrderProduct
                    };
                    const payChannel = getPayChannelBylId(unfinishedOrderProductPayChannelId);
                    sendBroadcast('goPayment', {orderLine, payChannel: payChannel, extend});
                    alarmEventWithPreDesc('6600', `${getPlatformName()}继续支付`, {unfinishedOrderId});
                } else {
                    ctx.showToast({title: '继续支付异常，请重新购买', icon: 'none'});
                    alarmEventWithPreDesc('6597', '继续支付异常-无单号或者无商品', this.data.orderInfo);
                }
                this.triggerEvent('closeDialog');
            } catch (e) {
                console.error('WGT ==== 6597 继续支付异常', e);
                ctx.showToast({title: '继续支付异常，请重新购买', icon: 'none'});
                this.triggerEvent('closeDialog');
                alarmEvent('6597', `继续支付异常 catch到了下单错误 ${e.message}`);
            }
        }

    },
};
