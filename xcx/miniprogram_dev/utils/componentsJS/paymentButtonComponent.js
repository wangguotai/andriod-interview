/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/

// components/cashier/paymentButton/paymentButton.js
import CashierDataManager from "../nativeCashier/store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../nativeCashier/store/constants";
import { containsSubstring, isNonEmptyObject, isNotEmptyArray, isTypeString, parseToBool, parseToJson } from "../nativeCashier/data/index";
import { CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_SELECTED, CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED, CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED_UNBLOCK, getPayAgreementInfo, getPrePayAgreementSelectState, getRenderParams, isPayChannelTip, needBlockPayAgreementSelect, needShowPrePayAgreementSelect } from "../nativeCashier/helper/paymentButton/PayButtonHelper";
import { registerListener, sendBroadcast, unregisterListener } from "../nativeCashier/BroadcastReceiver";
import { getExtend, getReportABTrackInfo } from "../nativeCashier/request/dataHelper";
import { alarmEvent, alarmEventWithPreDesc, sendClick, sendExp } from "../nativeCashier/track/TrackHelper";
import { getOpenId } from "../nativeCashier/biz/index";
import { observeIntersector } from "../nativeCashier/observer/IntersectionObserver";
import { getNavBarHeight, queryNodeHeight } from "../nativeCashier/common/index";
import { getPlatformContext, isBaiduAndIqiyiMiniProgram, isKuaishouMiniProgram } from "../context/contextHelper";
import { doAction } from "../nativeCashier/eventHandler/index";
import { COMMON_CONFIG } from "../config/cashierConfig";

const app = getApp();

let paymentTime = 0;

/** 初始化支付按钮
 */
function initPayButton() {
    // 前置支付协议处理
    let payAgreementInfo = null;
    const orderLine = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.ORDER_LINE);
    const payChannel = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CURRENT_PAY_CHANNEL);
    if (orderLine && payChannel) {
        // 是否为连续包商品
        const cycle_buy_supported = parseToBool(orderLine.attributes && orderLine.attributes.cycle_buy_supported);
        // 支付协议前置处理
        payAgreementInfo = getPayAgreementInfo(payChannel, cycle_buy_supported);
        let {
            payButtonText,
            payPrice,
            priceUnit,
            payBtnBonus,
            payChannelId,
            orderLinePromotion,
            btnPayAvailable,
            payBtnBonusNumber
        } = getRenderParams(
            orderLine,
            payChannel,
            this.data.isSelectedCycleBuy,
            this.data.selectCoupon,
        );


        // 通过支付渠道的优惠信息，更新按钮价格、优惠文案和优惠弹窗信息
        const newOrderLinePromotion = JSON.parse(JSON.stringify(orderLinePromotion || {}));
        // 补充来自支付渠道的优惠信息
        const tipsInfo = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.PAY_CHANNEL_TIPS_INFO) || {};
        if (isPayChannelTip(orderLine, payChannel, tipsInfo)) {
            if (tipsInfo && tipsInfo.promotion && isTypeString(tipsInfo.promotion.payChannelPrice)) {
                const payChannelPrice = parseFloat(tipsInfo.promotion.payChannelPrice) / 100;
                if ((payChannelPrice > 0) && (payPrice > payChannelPrice)) {
                    payPrice -= payChannelPrice;

                    let btnBonusNumber = payChannelPrice;
                    if (payBtnBonusNumber > 0) {
                        btnBonusNumber = parseFloat(payBtnBonusNumber) + btnBonusNumber;
                    }
                    payBtnBonus = `已减¥${btnBonusNumber.toFixed(2)}`;

                    if (newOrderLinePromotion.promUnitPrice) {
                        newOrderLinePromotion.promUnitPrice -= parseFloat(tipsInfo.promotion.payChannelPrice);
                    } else {
                        newOrderLinePromotion.promUnitPrice = payPrice * 100;
                    }
                    if (!newOrderLinePromotion.activities) {
                        newOrderLinePromotion.activities = [];
                    }
                    if (newOrderLinePromotion.activities && Array.isArray(newOrderLinePromotion.activities)) {
                        const activitie = {};
                        activitie.name = '支付方式优惠';
                        activitie.bonusUnitPrice = parseFloat(tipsInfo.promotion.payChannelPrice);
                        newOrderLinePromotion.activities.push(activitie);
                    }
                }
            }
        }
        let newOfferPrice = 0;
        let checkRenderPriceConflict = false;
        {
            let productId = orderLine && orderLine.product && orderLine.product.attributes && orderLine.product.attributes.ios_product_id || "";
            // IPhone weex中的逻辑 处理支付价格不一致的逻辑，微信小程序中代码无用，后续删除
            let info = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.RENDER_PRICE_CONFLICT_INFO) || {};
            let type = (info && info.product && isTypeString(info.product.type) && info.product.type) || 'def';
            let action = (info && info.config && info.config.action && isTypeString(info.config.action.switch) && info.config.action.switch) || '0';
            if (type === 'ori' && action === '1' && isNonEmptyObject(info) && isNonEmptyObject(info.product) && isTypeString(info.product.pid) && isTypeString(productId) && productId === info.product.pid && isTypeString(info.product.newOfferPrice)) {
                newOfferPrice = parseInt(info.product.newOfferPrice) / 100;
                checkRenderPriceConflict = true;
            }
        }
        // 合规支付按钮协议处理(仅支付宝支付渠道，目前为冗余代码，暂且保留，不影响功能)
        const needShowPrePayAgreementSelectIcon = needShowPrePayAgreementSelect(orderLine);
        const agreementToPayBtnData = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.AGREEMENT_PAY_BTN_DATA);
        const hasAgreementData = agreementToPayBtnData && agreementToPayBtnData.data && isNotEmptyArray(agreementToPayBtnData.nodes);
        if (hasAgreementData) {
            payButtonText = '确认协议并支付';
        }
        this.setData({
            hasPrepareFirstFrameData: true,
            payAgreementInfo,
            payButtonText,
            payPrice: payPrice.toFixed(2),
            priceUnit,
            payBtnBonus,
            payChannelId,
            orderLinePromotion,
            btnPayAvailable,
            payBtnBonusNumber,
            newOfferPrice,
            checkRenderPriceConflict,
            needShowPrePayAgreementSelectIcon,
            agreementToPayBtnData,
            hasAgreementData,
            newOrderLinePromotion, // 支付优惠明细数据
            orderLine,
        },
            monitorPayButtonPosition.bind(this),
        );
    }
}

function sendGoPaymentBroadcast(extend, orderLine, payChannel, isPasswordFreeOpen) {
    extend.goPayFrom = COMMON_CONFIG.GO_PAY_FROM.PAY_BUTTON;
    sendBroadcast('goPayment', {
        orderLine,
        payChannel,
        promotion: this.data.orderLinePromotion,
        extend,
        isPasswordFreeOpen,
        coinBalance: null,
        payPrice: this.data.payPrice,
        cycleBuyOption: this.data.isSelectedCycleBuy,
    });
}

/**
 * 开始发起支付
 *
 * 发起来源：
 * 1.用户直接点击支付按钮支付；
 * 2.未登录下用户点击支付按钮，触发登录后自动支付；
 * 3.收银台链接带了自动支付参数，渲染后发起的自动支付；（扫码支付场景）(小程序不支持)
 * 4.无界面1.0自动支付（指定支付渠道自动支付、不指定支付渠道点击支付渠道自动支付）(小程序不支持)
 * 5.无界面2.0点击支付按钮； (小程序不支持)
 *
 */
function goPayment() {
    const orderLine = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.ORDER_LINE);
    const payChannel = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CURRENT_PAY_CHANNEL);
    const extend = getExtend();
    const isPasswordFreeOpen = false;
    const currentTime = new Date().getTime();

    if (currentTime - paymentTime > 500 && orderLine) {
        // 确认支付按钮的点击事件
        let crm_goods_exposure_key = '';
        if (orderLine && orderLine.attributes && orderLine.attributes.crm_goods_exposure_key) {
            crm_goods_exposure_key = orderLine.attributes.crm_goods_exposure_key;
        }

        let crm_channel_exposure_key = '';
        if (payChannel && payChannel.attributes) {
            try {
                const payChannelAttributes = parseToJson(payChannel.attributes);
                if (payChannelAttributes && payChannelAttributes.chl_scm) {
                    crm_channel_exposure_key = payChannelAttributes.chl_scm;
                }
            } catch (e) {
                alarmEvent('6111', '支付渠道attributes解析异常', null);
            }
        }

        const login = app?.cashier?.isLogined ? 'login' : 'unlogin';
        const clickParams = {
            spm: extend && `${extend.pageSPM}.payButton.goPay`,
            scm: extend && `${extend.pageSPM}.payButton.goPay`,
            crm_scm: crm_goods_exposure_key,
            chl_scm: crm_channel_exposure_key,
            trace_id: extend && extend.traceId,
            track_info: {
                login_state: login,
                abTestInfo: getReportABTrackInfo(),
            },
        };
        sendClick(clickParams, {});
        let finalPayChannel;
        if (isPasswordFreeOpen) {
            finalPayChannel = Object.assign({}, payChannel, { is_open_password_free: true });
        } else {
            finalPayChannel = Object.assign({}, payChannel);
        }
        paymentTime = currentTime;
        // 支付协议前置支付前处理
        const cycle_buy_supported = parseToBool(orderLine && orderLine.attributes && orderLine.attributes.cycle_buy_supported);
        if (cycle_buy_supported && finalPayChannel) {
            const {
                selected_by_default,
                protocol_text
            } = getPayAgreementInfo(finalPayChannel, cycle_buy_supported) || {};
            // 是否选中了同意支付协议前置
            const isSelectedPayAgreement = this.data.isPayAgreement || parseToBool(selected_by_default);
            // 是否为连续包商品
            if (protocol_text && isSelectedPayAgreement) {
                finalPayChannel = Object.assign({}, finalPayChannel, { isPayAgreement: true });
            }
        } else if (this.data.isSelectedCycleBuy && finalPayChannel) {
            const {
                selected_by_default,
                protocol_text
            } = getPayAgreementInfo(finalPayChannel, this.data.isSelectedCycleBuy) || {};
            // 是否选中了同意支付协议前置
            const isSelectedPayAgreement = this.data.isPayAgreement || parseToBool(selected_by_default);
            // 是否为连续包商品
            if (protocol_text && isSelectedPayAgreement) {
                finalPayChannel = Object.assign({}, finalPayChannel, { isPayAgreement: true });
            }
        }
        // 连包商品协议购买前需勾选
        if (needBlockPayAgreementSelect(orderLine) && this.data.hasAgreementData && !this.data.isPrePayAgreementSelected) {
            const _this = this;
            const onClick = (action) => {
                CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.PRE_AGREEMENT_DIALOG_ONCLICK);
                if (action.type === 'ok') {
                    _this.setData({
                        isPrePayAgreementSelected: true,
                        hasPrePayAgreementGuide: false,
                    });
                    // 发送支付广播
                    sendGoPaymentBroadcast.call(_this, extend, orderLine, finalPayChannel, isPasswordFreeOpen);
                    alarmEvent('6121', '购前协议弹窗点击同意', null);

                } else if (action.type === 'cancel') {
                    _this.setData({
                        hasPrePayAgreementGuide: true,
                    });
                    alarmEvent('6123', '购前协议弹窗点击不同意', null);
                }
            };
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.PRE_AGREEMENT_DIALOG_ONCLICK, onClick);
            sendBroadcast('showDialog', {
                type: 'prePayAgreement',
                dialogParams: {
                    callbackFuncKey: CASHIER_GLOBAL_STORE_KEYS.PRE_AGREEMENT_DIALOG_ONCLICK,
                },
                isCancelable: true,
            });
            alarmEvent('6120', '触发购前协议弹窗', null);
        } else {
            // 发送支付广播
            sendGoPaymentBroadcast.call(this, extend, orderLine, finalPayChannel, isPasswordFreeOpen);
            alarmEvent('6130', '未触发购前协议弹窗', null);
        }
    }
}

function monitorPayButtonPosition() {
    try {
        if (!this.data.simpleCashier && !this.data.isInitPositionObserver) {
            this.data.isInitPositionObserver = true;
            new Promise(async (resolve) => {
                const paymentHeight = await queryNodeHeight('#payment-wrapper', this) || -122;
                const marginBotton = -paymentHeight;
                const marginTop = -(getNavBarHeight() + paymentHeight / 2);

                observeIntersector(this, '.payment-wrapper--observed', {
                    top: marginTop, // 监听 selector 节点距离视口顶部下方距离
                    bottom: marginBotton, // 监听 selector 节点距离视口底部的距离
                }, (res) => {
                    const { disappear } = res;
                    this.setData({
                        isShowFixedPaymentButton: disappear,
                    });
                    this.triggerEvent('onChangeFixedPaymentButtonStatus', {
                        isShowFixedPaymentButton: disappear,
                    });
                });
            });
        }
    } catch (e) {
        console.error('paymentButton ==== paymentButton::ready::全屏收银台滑动吸底监听失败 err', e);
        alarmEventWithPreDesc('7001', 'paymentButton ==== paymentButton::ready::全屏收银台滑动吸底监听失败 err', e);
    }
}


// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const paymentButtonComponent = {

    externalClasses: ['external-price-font-class'],
    /**
     * 组件的属性列表
     */
    properties: {
        simpleCashier: {
            type: Boolean,
            value: true,
        },

        isLogined: {
            type: Boolean,
            value: app?.cashier?.isLogined,
        },

        componentInfo: {
            type: Object,
            value: {},
            observer: function (newVal) {
                if (newVal) {
                    const { dataSource } = newVal;
                    this.setData({
                        dataSource,
                        isSvipCashier: newVal.isSvipCashier,
                    });
                    initPayButton.call(this);
                    // 曝光
                    const extend = getExtend();
                    const login = app?.cashier?.isLogined ? 'login' : 'unlogin';
                    const expParams = {
                        spm: extend && `${extend.pageSPM}.payButton.goPay`,
                        scm: extend && `${extend.pageSPM}.payButton.goPay`,
                        trace_id: extend && extend.traceId,
                        track_info: {
                            login_state: login,
                            abTestInfo: (getReportABTrackInfo() || ''),
                        },
                    };
                    sendExp(expParams, {});
                    // sendExp(dataSource?.[0]?.nodes?.[0]?.data?.action);
                }

            }
        },
    },

    /**
     * 组件的初始数据
     */
    data: {
        // 支付协议前置选中态
        isPayAgreement: false,
        // 当前选中连续包月
        isSelectedCycleBuy: false,
        selectCoupon: null,
        couponIndex: null,
        isPrePayAgreementSelected: false,
        hasPrePayAgreementGuide: false,
        // 底部支付协议选中
        hasAgreementData: false,
        hasPrepareFirstFrameData: false,
        isInitPositionObserver: false,
        // 是否展示固定吸底支付按钮
        isShowFixedPaymentButton: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.IS_HALF_SCREEN_CASHIER) || true,
        isSvipCashier: false,
    },
    lifetimes: {
        attached() {
            // 连续包月勾选框改变监听
            registerListener('onCycleBuyChange', 'payButton', event => {
                this.setData({
                    isSelectedCycleBuy: parseToBool(event),
                });
            });

            // 优惠券改变监听
            registerListener('selectCounpon', 'payButton', (event) => {
                if (event) {
                    this.setData({
                        selectCoupon: event,
                        couponIndex: event.selectIndex
                    });
                }
            });

            // 商品改变监听
            registerListener('onProductChanged', 'payButton', (orderLine) => {
                this.setData({
                    // 商品改变后，取消连续包月勾选
                    isSelectedCycleBuy: false,
                    // 商品改变，取消优惠券选中
                    selectCoupon: null,
                });

                if (needShowPrePayAgreementSelect(orderLine)) {
                    const state = getPrePayAgreementSelectState(orderLine);
                    if (state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_SELECTED) {
                        this.setData({
                            isPrePayAgreementSelected: true,
                            hasPrePayAgreementGuide: false,
                        })
                    } else if (state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED || state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED_UNBLOCK) {
                        if (!this.data.isPrePayAgreementSelected) {
                            this.setData({
                                isPrePayAgreementSelected: false,
                            })
                        }
                    }
                } else {
                    this.setData({
                        hasPrePayAgreementGuide: false,
                    })
                }
                initPayButton.call(this);
            });
        },
        detached() {
            unregisterListener('onProductChanged', 'payButton');
            unregisterListener('onCycleBuyChange', 'payButton');
        },

    },

    /**
     * 组件的方法列表
     */
    methods: {
        getPhoneNumberHandler(data) {
            let login = app.cashier.loginSDK;
            if (isBaiduAndIqiyiMiniProgram()) {
                login = app.login;
            } else if (isKuaishouMiniProgram()) {
                login = app._pspSdk;
            }
            login.needLogin(data).then((data) => {
                // 迁移自微信小程序主程 中登录的逻辑
                sendBroadcast('refreshPageAfterLogin', {
                    isLogined: true,
                });
                this.setData({
                    isLogined: true,
                });
                app.cashier.isLogined = true;
            }).catch((e) => {
                console.error('paymentButton ==== getPhoneNumberHandler needLogin 失败 err:', e)
            });
            alarmEvent('6091', '开始支付-未登录用户，确认支付');
        },
        onPayClick() {
            // 首先openId的获取会前移至页面渲染，如果获取失败，这里再做一次尝试
            const openId = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.OPEN_ID);
            // 百度&爱奇艺小程序不需要openId
            if (!openId && !isBaiduAndIqiyiMiniProgram()) {
                getOpenId().then(_openId => {
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.OPEN_ID, _openId);
                    goPayment.call(this);
                }).catch(err => {
                    alarmEventWithPreDesc('7021', '支付触发获取openId失败', err);
                    console.error('paymentButton ==== paymentButton getSessionId 失败 err', err);
                    const ctx = getPlatformContext();
                    ctx.showToast({ title: "数据异常请重试" });
                });
            } else {
                if (this.data.btnPayAvailable) {
                    goPayment.call(this);
                }
            }
            alarmEventWithPreDesc('6510', '开始支付', {openId});
        },

        /**
         * 支付优惠明细点击展示
         */
        onPayBtnBonusClick() {
            sendClick({
                // 芷瑶增加的埋点
                spm: 'a2h07.13758154.discountdetails.show',
            }, {});
            const newOrderLinePromotion = this.data.newOrderLinePromotion;
            const orderLine = this.data.orderLine;

            sendBroadcast('showDialog', {
                justifyContent: 'flex-end',
                type: 'discount_detail',
                dialogParams: { promotion: newOrderLinePromotion, orderLine },
                isCancelable: true,
            });

        },
        /**
         * 顶部前置支付协议点击逻辑处理，触发协议选中和协议查看跳转
         */
        onTopPayAgreementClick(e) {
            const { type, url } = e.currentTarget.dataset;
            if (type === 'JUMP_TO_URL') {
                doAction({
                    type,
                    value: url,
                    from: 'payButton-onTopPayAgreementClick',
                });
            } else if (type === 'SELECT') {
                this.setData({
                    isPayAgreement: !this.data.isPayAgreement,
                });
            }
        },
        onPayAgreementClick(e) {
            const { type } = e.currentTarget.dataset;
            if (type === 'prePayAgreement' || type === 'prePayAgreementGuide') {
                this.setData({
                    isPrePayAgreementSelected: !this.data.isPrePayAgreementSelected,
                });
                if (this.data.isPrePayAgreementSelected) {
                    if (this.data.hasPrePayAgreementGuide) {
                        this.setData({
                            hasPrePayAgreementGuide: false
                        });
                    }

                    alarmEvent('6125', '购前协议勾选');
                }
            } else {
                alarmEvent('6099', '支付按钮协议下发参数错误');
            }
        },
        jumpToUrl(e) {
            let { url } = e.currentTarget.dataset;
            doAction({
                type: 'JUMP_TO_URL',
                value: url,
                from: 'payButton-jumpToUrl',
            });
        }

    }
};
