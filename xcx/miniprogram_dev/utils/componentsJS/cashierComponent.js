/**************************************************
 * @Description: 半屏收银台组件，业务逻辑参考主客
 * 1. requestRenderData 迁移主客请求渲染数据
 **************************************************/
import { createComponents, getExtend, getModules, initCashier, parseData } from '../nativeCashier/request/dataHelper';
import CashierDataManager from '../nativeCashier/store/index';
import { CASHIER_GLOBAL_STORE_KEYS } from "../nativeCashier/store/constants";
import { areObjectsEqual, isNonEmptyObject, isNonEmptyString } from "../nativeCashier/data/index";
import { closeOrderById, getHomeRenderData } from "../nativeCashier/request/requestHelper";
import { registerListener, sendBroadcast } from "../nativeCashier/BroadcastReceiver";
import { alarmEvent, alarmEventWithPreDesc, sendAlarm, sendPageView } from "../nativeCashier/track/TrackHelper";
import { payProduct } from "../nativeCashier/helper/payment/PayManager";
import { buildCashierUrl, getPaySuccessUrl, getOpenId, getCNA, initUnChangedGlobalParams, isSimpleScreenCashier } from "../nativeCashier/biz/index";
import { doAction } from "../nativeCashier/eventHandler/index";
import { getCachedSystemInfo } from '../nativeCashier/common/index';
import { buildAlarmInfo, checkOrderStatus } from '../nativeCashier/helper/payment/orderHelper';
import { context, getPlatformName, isBaiduAndIqiyiMiniProgram, isBaiduMiniProgram, isByteDanceMiniProgram, isKuaishouMiniProgram, isWxMiniProgram } from "../context/contextHelper";

const app = getApp();
const componentName = 'cashier';
const ctx = context;

/**
 * 支付
 * 执行支付逻辑：
 * @param orderLine
 * @param payChannel
 * @param extend
 */
function payment(orderLine, payChannel, extend) {

    payProduct(orderLine, payChannel, extend, (result) => {
        const goPayFrom = extend && extend.goPayFrom;
        if (result.type === 'wait') {
            if (result.orderId) {
                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.ORDER_ID, result.orderId);
                // 设置支付信息，稍后用于支出成功/失败的上报
                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.PAY_INFO, {
                    orderId: result.orderId,
                    orderLine,
                    payChannel,
                    goPayFrom,
                });
            }
            alarmEvent('6561', `处理支付结果wait from-${result.from} goPayFrom-${goPayFrom}`);

        } else if (result.type === 'fail') {
            const data = result?.res?.data;
            let orderId = (data && (data.order_id || data.trade_id || data.orderId)) || null;

            // 支付失败，如果创建了订单，则关闭订单
            if (orderId) {
                closeOrderById(orderId, payChannel, orderLine, (res) => {
                    if (res.type !== 'success') {
                        ctx.showToast({title: '关闭订单失败', icon: 'none'});
                    } else {
                        ctx.showToast({title: '支付失败，关闭订单成功', icon: 'none'});
                    }
                    sendBroadcast('requestRenderData', res);
                })
                console.error('PAY_ERROR', 'payProduct===callback  支付结果fail，有订单号 处理支付失败', orderId);
            } else {
                alarmEvent('6570', '处理支付结果fail，没有订单号');
            }
            alarmEventWithPreDesc('6563', `处理支付结果fail from-${result.from} goPayFrom-${goPayFrom}`, result);
        } else if (result.type === 'toast') {

            if (result.message) {
                let flag = true;
                const res = result.res;
                if (res && res.detailCode && res.detailCode.toString() === '70001') {
                    flag = false;
                }
                if (flag) {
                    ctx.showToast({
                        title: result.message,
                        icon: 'none',
                    });
                }
            }
            alarmEventWithPreDesc('6564', `处理支付结果toast from-${result.from} goPayFrom-${goPayFrom}`, result.message, null);
        }
    });
}

/**
 * 开始发起支付前进行支付拦截：用户重复签约购买前弹框提醒
 * 发起来源：
 * 1.用户直接点击支付按钮支付；
 *
 * @param orderLine
 * @param payChannel
 * @param myExtend
 */
function goPayment(orderLine, payChannel, myExtend) {
    // 支付拦截，老历史代码，通过 预警id=》traceId=》获取到了线上发生的支付拦截数据
    // eg："purchase_intercept": "{\"vip_url\":\"\",\"h5_url\":\"\",\"primary_title\":\"确定要重新签约吗？\",\"secondary_title\":\"您已经签约了连续包月，正在享受保价权益，如果重新签约将不再享受该优惠，是否确认重新签约？\",\"btn1\":\"暂不购买\",\"btn2\":\"继续支付\"}",
    // {
    //     "vip_url": "",
    //     "h5_url": "",
    //     "primary_title": "确定要重新签约吗？",
    //     "secondary_title": "您已经签约了连续包月，正在享受保价权益，如果重新签约将不再享受该优惠，是否确认重新签约？",
    //     "btn1": "暂不购买",
    //     "btn2": "继续支付"
    // }
    if (orderLine && orderLine.attributes && orderLine.attributes.purchase_intercept && orderLine.attributes.purchase_intercept !== '') {  // 购买拦截
        const purchaseIntercept = JSON.parse(orderLine.attributes.purchase_intercept) || {};
        const alertParams = {
            type: 'custom',
            dialogParams: {
                title: purchaseIntercept.primary_title,
                contentText: purchaseIntercept.secondary_title,
                cancelText: purchaseIntercept.btn1,
                confirmText: purchaseIntercept.btn2,
                action: {
                    onClick: (action) => {
                        if (action === 'confirm') {
                            payment(orderLine, payChannel, myExtend);
                            alarmEventWithPreDesc('6528', '弹窗支付打断-点击支付，开始下单', purchaseIntercept);
                        } else if (purchaseIntercept && purchaseIntercept.h5_url) {
                            doAction({
                                type: 'JUMP_TO_URL',
                                value: purchaseIntercept.h5_url,
                                from: '支付拦截弹窗',
                            });
                            alarmEventWithPreDesc('6523', '弹窗支付打断-跳转h5链接', purchaseIntercept);
                        } else {
                            alarmEventWithPreDesc('6524', '弹窗支付打断-支付异常', purchaseIntercept);
                        }
                    }
                },
            },
        };
        sendBroadcast('showDialog', alertParams);
        alarmEventWithPreDesc('6094', '确认支付，弹窗提示', purchaseIntercept);
    } else {
        payment(orderLine, payChannel, myExtend);
        alarmEvent('6095', '普通支付，开始下单');
    }
}

function initApp(cashierUrl) {
    // 初始化收银台参数
    initCashier(cashierUrl);
    // 初始化收银台高度
    this.handleCashierHeight();
    this.registerUIListener(this);
    // 请求收银台渲染接口
    this.requestRenderData(this);
}

/**
 * 收银台组件
 * 适配快手小程序，无法在组件JS外执行Component的初始化
 */
export const cashierComponent = {
    /**
     * 组件的属性列表
     */
    properties: {
        isShowCashier: {
            type: Boolean,
            value: false,
        },
        linkParams: {
            type: String,
            value: '',
        },

        cashierInfo: {
            type: Object,
            value: {},
            observer: async function (newVal) {

                let cashierInfo = newVal;
                // 微信 & 快手支持场景化
                if (isNonEmptyObject(newVal)) {
                    const {isAutoShow, ...otherProps} = newVal;
                    cashierInfo = otherProps;
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.IS_AUTO_INVOKE, isAutoShow);
                }
                // 是否需要强制刷新
                const needRefresh = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.NEED_REFRESH);
                if (isNonEmptyObject(cashierInfo) && (!areObjectsEqual(this.data.paramsObj, cashierInfo) || needRefresh)) {

                    const data = {
                        paramsObj: cashierInfo,
                        sceneType: cashierInfo.sceneType,
                        loadingType: 'loading',

                    };

                    this.setData(data);
                    // 精准控制是否是全屏收银台，由端内控制，而非下发链接
                    const isFullScreenCashier = cashierInfo?.sceneType === 'fullScreenCashier';
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.IS_HALF_SCREEN_CASHIER, !isFullScreenCashier);
                    const cashierLinkUrl = buildCashierUrl(cashierInfo, isFullScreenCashier);
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.CASHIER_LINK_URL, cashierLinkUrl);
                    // 解析传入参数，初始化收银台全局变量的起点
                    initApp.call(this, cashierLinkUrl);
                } else {
                    // 【loading】 2. 如果不需要重新刷新，则关闭loading
                    this.setData({
                        loadingType: 'complete',
                    });
                }
            }
        }

    },

    /**
     * 组件的初始数据
     */
    data: {
        paramsObj: {}, // 用于比对上次的传入参数对象，避免重复初始化，重新渲染
        modules: [],
        classRoot: 'cashier-modal-container',
        loadingType: 'loading',
        floatingVisible: {visible: false, data: {}},
        isLogined: app?.cashier?.isLogined || false,
        pendingType: 'view',
        isShowFixedPaymentButton: false,
        loadingWithTransparent: false, // 是否使用透明loading
    },


    pageLifetimes: {
        show: function () {
            // 百度小程序直连支付不支持微信支付结果的回传，因此需要在返回页面时，做支付结果的判断
            // 爱奇艺小程序使用H5链路同样不支持支付结果回传
            if (isBaiduAndIqiyiMiniProgram()) {
                const payResult = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.BAIDU_NEED_CHECK_ORDER_WHEN_BACK);
                if (payResult && this.data.pendingType === 'paying') {
                    CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.BAIDU_NEED_CHECK_ORDER_WHEN_BACK);
                    const orderInfo = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.PAY_INFO) || {};
                    const {orderId, orderLine, payChannel, goPayFrom} = orderInfo;
                    checkOrderStatus(orderId, orderLine, payChannel, goPayFrom)
                        .then(res => {
                            if (res.type === 'success') {
                                // 1. 设置页面需要刷新标记，避免用户打开仍有优惠的收银台页面
                                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.NEED_REFRESH, true);
                                this.setData({
                                    pendingType: 'success',
                                    loadingWithTransparent: false,
                                    loadingType: 'complete'
                                });
                                // 2. 是否跳转至支付成功页
                                // 2.1 百度直连支付API在微信渠道没有支付成功回调通知的配置，需要在此跳转支付成功页
                                if (isBaiduMiniProgram() || CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.IQIYI_WX_PAY_NEED_GO_TO_PAY_SUCCESS_BY_MANUALLY)) {
                                    // 爱奇艺微信支付需要手动判断跳转支付成功页
                                    CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.IQIYI_WX_PAY_NEED_GO_TO_PAY_SUCCESS_BY_MANUALLY);
                                    doAction({
                                        type: 'JUMP_TO_URL',
                                        value: getPaySuccessUrl(orderId),
                                        from: '轮询订单支付成功',
                                    });
                                } else {
                                    // 2.2 爱奇艺支付宝支付有支付成功后跳转支付成功页的配置，仅需要在此关闭收银台
                                    if (this.data.cashierInfo.sceneType === 'fullScreenCashier') {
                                        ctx.navigateBack({delta: 1});
                                    } else {
                                        this.setData({
                                            pendingType: 'view',
                                        });
                                    }
                                    this.closeCashierModal(true);
                                }

                                // 预警上报支付成功
                                const alarmInfo = buildAlarmInfo(orderInfo, res);

                                alarmEventWithPreDesc('6334', `${getPlatformName()}-支付成功`, alarmInfo);
                            }
                        })
                        .catch(() => {
                            // 支付失败
                            sendBroadcast('payFail', {
                                msg: "订单状态轮询结果失败"
                            });
                        });
                }

            }
            // // 页面显示时判断是否来自支付成功页返回，无法直接在事件监听处做路由back
            if (this.data.pendingType === 'success') {
                if (this.data.cashierInfo.sceneType === 'fullScreenCashier') {
                    ctx.navigateBack({delta: 1});
                } else {
                    this.setData({
                        pendingType: 'view',
                    });
                }
                this.closeCashierModal(true);
            }
        }
    },

    /**
     * 组件生命周期
     * created中不能使用setData()
     */
    lifetimes: {
        created() {
            const fontFaceOptions = {
                family: 'iconfont_cashier4',
                source: 'url("https://g.alicdn.com/visp/assets/0.0.2/VIPFONT-Black.ttf")',
                success: function (res) {
                    console.log('cashier组件 lifetimes - 加载业务字体成功', res);
                },
                fail: function (res) {
                    console.error('cashier组件 lifetimes - 加载业务字体失败', res);
                }
            };
            // 当前仅百度和微信小程序支持加载业务字体
            if (isBaiduAndIqiyiMiniProgram() || isWxMiniProgram()) {
                try {
                    ctx.loadFontFace && ctx.loadFontFace(fontFaceOptions);
                } catch (e) {
                    console.error('cashier组件 lifetimes - 加载业务字体失败', e);
                }
            }

            // 预先拉取一下sessionId，用于创建订单
            getOpenId().then(openId => {
                // 优先取端内的全局utdid，如果未取到，则使用openId
                if (isNonEmptyString(app?.globalData?.utdid)) {
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.UT_DID, app.globalData.utdid);
                } else {
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.UT_DID, openId);
                }
                // 保持当前线上逻辑, 百度小程序不需要payInfo
                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.OPEN_ID, isBaiduAndIqiyiMiniProgram() ? '' : openId);
            }).catch(err => {
                // 纯新用户用户未登录场景中无法获取openId，跟家鲁约定在当前非直接进入收银台的场景，取app.globalData.utdid
                if (isNonEmptyString(app?.globalData?.utdid)) {
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.UT_DID, app.globalData.utdid);
                } else {
                    // 如果openId获取失败，则使用CNA作为utdid
                    getCNA().then(utdid => {
                        CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.UT_DID, utdid);
                    });
                }
                console.error('cashier组件 lifetimes - 获取openId失败', err);
            });

            // 初始化无变动的收银台全局变量： 支付channel
            initUnChangedGlobalParams();
        },

        /**
         * 组件挂载完毕后触发，只有这个时候才可以使用setData()
         */
        attached() {
            const context = this;

            /**
             * 登录后需要刷新收银台页面
             * 20240819 登录成功后关闭页面
             * 20241009 登录成功后恢复页面刷新，验证换货策略
             */
            registerListener('refreshPageAfterLogin', 'home', (event) => {
                context.setData({
                    isLogined: event.isLogined,
                    loadingType: 'loading',
                });

                // 标记是否是收银台内部做出登录导致 登录态变化
                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.IS_LOGIN_STATUS_CHANGED, true);
                // 登录态发生了变化，需要刷新页面
                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.NEED_REFRESH, true);
                // 20241009 登录成功后刷新页面执行换货，取消关闭收银台
                context.requestRenderData(context, event.isLogined);

            });

            registerListener('requestRenderData', 'home', () => {
                context.requestRenderData(context);
            });
        },
    },

    /**
     * 组件的方法列表
     */
    methods: {
        /**
         * 关闭收银台
         * @param isPaySuccess 是否是从支付成功的触发的关闭，默认值是false
         * 2024.11.19 修复isPaySuccess会被小程序带入event对象导致播放页重复刷新
         */
        closeCashierModal(isPaySuccess = false) {
            // 判断是否在收银台内部发生了登录态变化 （未登录->已登录）
            const isLoginStatusChanged = !!CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.IS_LOGIN_STATUS_CHANGED);
            CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.IS_LOGIN_STATUS_CHANGED);
            // 向小程序主客发送关闭收银台的回调
            this.triggerEvent('onClose', {
                isLoginStatusChanged,
                isPaySuccess: isPaySuccess === true,
                extraParams: {},
            });
        },

        /**
         * 关闭弹框
         */
        closeDialog() {
            this.setData({
                isShowDialog: false,
            });
        },

        handleCashierHeight() {
            // 处理收银台高度，存储在全局变量中
            let height;
            const simpleCashier = isSimpleScreenCashier();
            try {
                const systemInfo = getCachedSystemInfo();
                const scaleFactor = simpleCashier ? 0.64 : 1;

                // *DIFF* 百度小程序获取的屏幕高度
                if (isBaiduAndIqiyiMiniProgram() || isByteDanceMiniProgram()) {
                    height = Math.round(Math.max(systemInfo.screenHeight, systemInfo.screenWidth) * scaleFactor);
                } else {
                    // 修复在全屏播放模式下打开收银台，导致高度变低的问题
                    height = Math.round(Math.max(systemInfo.windowHeight, systemInfo.windowWidth) * scaleFactor);
                }
            } catch (e) {
                // 兼容处理 极少数基础库过小的情况
                height = 521;
            }

            this.setData({
                scrollViewStyle: `height: ${height}px;`,
                simpleCashier,
            });
        },

        // 请求渲染数据
        requestRenderData: (context, isLoginPay = false) => {
            getHomeRenderData(isLoginPay).then(result => {
                if (result) {
                    // 数据解析
                    parseData(result);
                    // 渲染开始前，清除上次的渲染的全局数据
                    CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.TOP_BANNER_DATA);
                    // 创建本次渲染数据
                    const modules = createComponents(getModules());

                    // 更新埋点SDK的当前页面的 spm数据，通过广播传值
                    app?.cashier?.trackSDK?.updatePageSPM();

                    // 发送收银台PV事件 && 预警上报
                    sendPageView();
                    // 补充页面渲染完成的标志
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.ENABLE_CHECK_CONTINUE_PAY_ORDER, true);
                    context.setData({
                        // 设置完数据后会触发页面的曝光埋点
                        modules,
                        // 【loading】 3. 数据加载完毕，关闭loading
                        loadingType: 'complete',
                    });
                    // 页面已经刷新完毕了，重置刷新标记
                    CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.NEED_REFRESH);
                } else {
                    ctx.showToast({title: "网络异常请重试", duration: 2000});
                }

            }).catch((err) => {
                // failCallback 中已经有了支付失败预警上报
                ctx.showToast({title: "网络异常请重试", duration: 2000});
                console.error("cashier组件 requestRenderData error", err);
            });
        },
        registerUIListener(context) {
            // 从render接口中获取登录态
            registerListener('updateLoginStatus', componentName, (loginInfo) => {
                if (!app.cashier) {
                    app.cashier = {};
                }
                app.cashier.isLogined = loginInfo.isLogined;
                context.setData({isLogined: loginInfo.isLogined});
            });

            // 添加支付中的状态控制
            registerListener('paying', componentName, () => {
                context.setData({
                    loadingWithTransparent: true,
                    loadingType: 'loading',
                    pendingType: 'paying',
                });
            });

            // 支付结果查询异常alert情况
            registerListener('payAlert', componentName, () => {
                context.setData({
                    loadingWithTransparent: false,
                    loadingType: 'complete',
                });
            });

            // 小程序SDK返回支付成功通知后:
            //  1. 设置页面需要刷新标记
            //  2. 开启支付结果轮询
            //      - 轮询订单状态： alert、success、fail
            //  返回到了本页面，需要刷新
            registerListener('paySuccess', componentName, res => {
                // 1. 设置页面需要刷新标记，避免用户打开仍有优惠的收银台页面
                CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.NEED_REFRESH, true);
                const orderInfo = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.PAY_INFO) || {};
                const {orderId, orderLine, payChannel, goPayFrom} = orderInfo;
                // 2. 开启支付结果轮询
                checkOrderStatus(orderId, orderLine, payChannel, goPayFrom).then(r => {
                    // 支付成功后:
                    // 1. 更新支付成功标记，用于后续刷新 & 关闭加载动画
                    context.setData({
                        pendingType: 'success',
                        loadingWithTransparent: false,
                        loadingType: 'complete'
                    });
                    // 2. 跳转至支付成功页
                    doAction({
                        type: 'JUMP_TO_URL',
                        value: getPaySuccessUrl(orderId),
                        from: '轮询订单支付成功',
                    });
                }).catch(r => {
                    const {result, type} = r || {};
                    // 轮询结果失败，权益下发会是正常的，为保障用户优惠权益，关闭订单
                    sendBroadcast('payFail', {
                        notShowToast: true,
                        msg: "订单状态轮询结果失败"
                    });
                    ctx.showToast({
                        title: "订单状态更新异常", duration: 2000, icon: 'none',
                    });
                    if (type === 'fail') {  // fail是端内查询返回的标识
                        // 提取重要的信息进行预警上报
                        const resData = result?.res || {};
                        const msgObj = {
                            orderState: resData?.orderState || '',
                            orderStateDesc: resData?.orderStateDesc || '',
                            timeout: resData?.timeout || '',
                            payPrice: resData?.payPrice || '',
                        }
                        sendAlarm('6574', `${getPlatformName()}-支付返回收银台检查订单异常,curOrderGoPayFrom=${goPayFrom}`, msgObj, payChannel, orderLine, orderId);
                    }
                });
                // 预警上报支付成功
                const alarmInfo = buildAlarmInfo(orderInfo, res);

                alarmEventWithPreDesc(6334, `${getPlatformName()}-支付成功`, alarmInfo);
            });

            // 支付失败后，返回到了本页面，不需要刷新
            registerListener('payFail', componentName, res => {
                // notShowToast字段标记是否需要展示toast
                if (!res?.notShowToast) {
                    ctx.showToast({
                        title: '支付失败',
                        icon: 'error',
                        duration: 2000
                    });
                }
                context.setData({
                    loadingWithTransparent: false,
                    loadingType: 'complete',
                });
                // 执行关闭订单的操作
                // 1. 获取订单号、支付渠道、商品信息

                // 调整关闭订单的数据来源，避免未支付订单中，orderLine来源出错
                const orderInfo = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.PAY_INFO) || {};
                const {orderId, payChannel, orderLine, goPayFrom} = orderInfo;

                // 2. 调用关闭订单接口
                closeOrderById(orderId, payChannel, orderLine, (res) => {
                    // 3. 关闭订单成功后，如果是来自继续支付的订单，刷新收银台页面，保证用户看到真实的优惠信息
                    if (res?.type === 'success' && goPayFrom === 'UnfinishedOrderDialog' || res?.type === 'fail') {
                        context.requestRenderData(context);
                    }
                });

                const alarmInfo = buildAlarmInfo(orderInfo, res);
                alarmEventWithPreDesc(6335, `${getPlatformName()}-支付失败`, alarmInfo);
            });

            /**
             * 监听弹框展示，发送相应数据到弹框组件
             */
            registerListener('showDialog', componentName, (data) => {
                context.setData({
                    dialogInfo: data,
                    isShowDialog: true,
                });
            });
            registerListener('goPayment', 'home', (action) => {
                const {orderLine, payChannel, extend, promotion, cycleBuyOption} = action || {};
                if (orderLine) {
                    const myExtend = Object.assign({}, extend || getExtend(), {
                        orderLineCouponPromotion: promotion,
                        cycleBuyOption
                    });
                    goPayment(orderLine, payChannel, myExtend);

                }
            });
        },

        onChangeFixedPaymentButtonStatus(e) {
            const {isShowFixedPaymentButton} = e.detail;
            this.setData({
                isShowFixedPaymentButton
            });
        },
    },

};
