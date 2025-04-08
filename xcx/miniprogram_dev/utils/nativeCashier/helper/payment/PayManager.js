/**************************************************
 * @description:
 *  - created time: 2024/7/9
 *  -
 **************************************************/
import { isEmptyStr, isNonEmptyObject, isNotEmptyArray, isTypeString, parseToBool } from "../../data/index";
import { alarmEvent, alarmEventWithPreDesc, sendAlarm } from "../../track/TrackHelper";
import { doAction, jumpToUrl } from "../../eventHandler/index";
import { getBindPhoneNumberData, getPayChannelVmpRenderData, getRenderCRMParams } from "../../request/dataHelper";
import { isCoinChannel } from "../../biz/index";
import { createOrderForMiniPay, getReturnUrl, noSecretPayChannelFromChannels, noSecretPayChannels, openNoSecretPayChannels } from "./orderHelper";
import CashierDataManager from "../../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../../store/constants";
import { envType, isBaiduAndIqiyiMiniProgram, isByteDanceMiniProgram, isKuaishouMiniProgram, isWxMiniProgram } from "../../../context/contextHelper";
import { ALARM_CONFIG, APP_INFO_CONFIG, BAIDU_MINI_PAY_CONFIG, BYTE_DANCE_MINI_PAY_CONFIG, KS_MINI_PAY_CONFIG, WX_MINI_CONFIG } from "../../../config/cashierConfig";

/**
 * 获取支付渠道
 * @param {Object} payChannel 支付渠道
 */
export const getPayChannelId = (payChannel) => {
    let payChannelId = '';
    if (payChannel && payChannel.payChannelId) {
        payChannelId = payChannel.payChannelId.toString();
    }
    if (isEmptyStr(payChannelId)) {
        alarmEventWithPreDesc('6003', '支付渠道错误', payChannel);
    }

    return payChannelId;
};


/**
 *
 * @param {*} product
 * @param {*} payChannel
 * @param {*} extend {receivings:'额外添加到优惠', orderLineCouponPromotion: '优惠券优惠', cycleBuyOption: '是否勾选了连续包月' }
 * @param noSecretPay
 */
export const getOrderParams = (product, payChannel, extend, noSecretPay = false) => {
    const payChannelId = getPayChannelId(payChannel);
    const result = {};
    const mExtend = extend || {};
    if (typeof product !== 'object' || isEmptyStr(payChannelId)) {
        result.type = 'fail';
        result.message = payChannel ? '购买商品不能为空' : '支付渠道不能为空';
        result.res = product;

        alarmEvent('6103', result.message);
    } else if (product.attributes && parseToBool(product.attributes.limit_buy)) {
        result.type = 'toast';
        result.message = '您今天已达到购买上限啦！';
        result.res = product;

        alarmEvent('6102', result.message);
    } else if (product.attributes && product.attributes.can_pay === 'false' && product.attributes.can_pay_tip) {
        result.type = 'toast';
        result.message = product.attributes.can_pay_tip ? product.attributes.can_pay_tip : '所选商品无法进行购买';
        result.res = product;

        alarmEvent('6529', result.message);
    } else if (product.attributes && product.attributes.sellable && !parseToBool(product.attributes.sellable)) {
        if (product.attributes && product.attributes.buy_url) {
            // 本商品无法购买,跳转到指定地址购买
            result.type = 'toast';
            result.message = '';
            result.res = product.attributes.buy_url;

            doAction({
                type: 'JUMP_TO_URL',
                value: product.attributes.buy_url,
                from: 'PAY_MANAGER'
            });
            sendAlarm('6531', '本商品无法购买,跳转到指定地址购买', null, payChannel, product);
        } else {
            result.type = 'toast';
            result.message = '本商品无法购买';
            result.res = product;

            alarmEvent('6100', '拼接下单参数异常，本商品无法购买');
        }
    } else {
        // 优惠信息
        const receivings = [];
        const activityIds = [];
        if (isNotEmptyArray(mExtend.unfinishedReceivings) && mExtend.goPayFrom === 'UnfinishedOrderDialog') {
            // 处理未支付优惠订单，继续支付优惠信息
            mExtend.unfinishedReceivings.forEach((receiving) => {
                if (receiving.activityId && activityIds.indexOf(receiving.activityId) < 0) {
                    receivings.push({ activityId: receiving.activityId });
                    activityIds.push(receiving.activityId);
                }
            });
        } else if (isNotEmptyArray(mExtend.detainmentReceivings) && mExtend.goPayFrom === 'DetainmentPromotionDialog') {
            // 处理优惠订单挽留再支付的优惠信息
            mExtend.detainmentReceivings.forEach((receiving) => {
                if (receiving.activityId && activityIds.indexOf(receiving.activityId) < 0) {
                    receivings.push({ activityId: receiving.activityId });
                    activityIds.push(receiving.activityId);
                }
            });
        } else {
            // 拉取用户选择的Coupon的activityId
            // 确定用户选中或系统默认选中第一个Coupon组合
            // 首现判断是否勾选自续费，如果勾选自续费则不使用任何优惠券，只使用自续费优惠，已经与真龙确认
            if (mExtend.cycleBuyOption && product && product.product && product.product.cycleBuyOptionPromotions && isNotEmptyArray(product.product.cycleBuyOptionPromotions) && product.product.cycleBuyOptionPromotions[0]) {
                const receivingsTemp = product.product.cycleBuyOptionPromotions[0].receivings;
                if (isNotEmptyArray(receivingsTemp)) {
                    receivingsTemp.forEach((receiving) => {
                        if (receiving) {
                            const activityId = receiving.activityId;
                            if (activityId && activityIds.indexOf(activityId) < 0) {
                                receivings.push({ activityId });
                                activityIds.push(activityId);
                            }
                        }
                    });
                }
            } else if (mExtend.orderLineCouponPromotion) {
                const { activities } = mExtend.orderLineCouponPromotion;
                if (isNotEmptyArray(activities)) {
                    activities.forEach((activity) => {
                        if (activity) {
                            const activityId = activity.id || activity.activityId;
                            if (activityId && activityIds.indexOf(activityId) < 0) {
                                receivings.push({ activityId });
                                activityIds.push(activityId);
                            }
                        }
                    });
                }

                // 无价格优惠到优惠信息(赠送)
                const noPriceActivities = mExtend.orderLineCouponPromotion.noPriceRelatedPromotion;
                if (isNotEmptyArray(noPriceActivities)) {
                    noPriceActivities.forEach((activity) => {
                        if (activity) {
                            const activityId = activity.id || activity.activityId;
                            if (activityId && activityIds.indexOf(activityId) < 0) {
                                receivings.push({ activityId });
                                activityIds.push(activityId);
                            }
                        }
                    });
                }
            }

            // 商品自带到优惠信息
            const mProduct = product.product;
            if (mProduct && isNotEmptyArray(mProduct.promotions) && isNotEmptyArray(mProduct.promotions[0].receivings)) {
                const productReceivings = mProduct.promotions[0].receivings;
                productReceivings.forEach((activity) => {
                    if (activity) {
                        const activityId = activity.id || activity.activityId;
                        if (activityId && activityIds.indexOf(activityId) < 0) {
                            receivings.push({ activityId });
                            activityIds.push(activityId);
                        }
                    }
                });
            }

            // 支付渠道优惠
            if (mProduct && isNotEmptyArray(mProduct.payChannelPromotions) && isNotEmptyArray(mProduct.payChannelPromotions[0].receivings) && mProduct.payChannelPromotions[0].attributes) {
                for (let i = 0; i < mProduct.payChannelPromotions.length; i++) {
                    const element = mProduct.payChannelPromotions[i];
                    if (element && isNotEmptyArray(element.receivings) && element.attributes && element.receivings[0].activityId && payChannelId === element.attributes.pay_channel) {
                        const { activityId } = element.receivings[0];
                        if (activityId && activityIds.indexOf(activityId) < 0) {
                            receivings.push({ activityId });
                            activityIds.push(activityId);
                        }
                    }
                }
            }
        }

        const tempAttributes = (mExtend.params && mExtend.params.attributes) || {};
        const attributes = Object.assign({}, tempAttributes);

        // crm及订单复用 开始
        if (product.attributes && product.attributes.crm_goods_id) {
            attributes.crm_goods_id = product.attributes.crm_goods_id;
        }

        //下单的crm标识
        let renderCRMParams = getRenderCRMParams();
        if (isNonEmptyObject(renderCRMParams) && isTypeString(renderCRMParams.crm_touch_point_code)) {
            attributes.crm_touch_point_code = renderCRMParams.crm_touch_point_code;
        }

        const productAttributes = {};
        if (product && product.attributes) {
            if (product.attributes.paramtransfer) {
                productAttributes.paramtransfer = product.attributes.paramtransfer;
            }
            // 如果是U钻支付渠道，设置currency
            if (isCoinChannel(payChannelId) && product.attributes.product_pay_currency) {
                productAttributes.product_pay_currency = product.attributes.product_pay_currency;
            }
        }

        // 区分加购商品，王欢定义的协议
        productAttributes.order_type = 'main';

        // 周期购
        let cycleBuyType;
        // 老参数周期购
        let orderType;
        if (
            (product.attributes && parseToBool(product.attributes.cycle_buy_supported)) ||
            parseToBool(mExtend.cycleBuyOption)
        ) {
            cycleBuyType = '1';
            orderType = '2';
        } else {
            cycleBuyType = '0';
            orderType = '1';
        }

        // 支付宝协议前置逻辑，连续包商品，并且命中了协议前置逻辑
        if (cycleBuyType === '1' && payChannel && payChannel.isPayAgreement) {
            attributes.preposition_cyclebuy_protocol = true;
        }

        // 购买数量，批量购买处理
        let quantity = '1';
        if (product && product.quantity) {
            quantity = product.quantity;
        }
        // 如果是超前点播批量购买，配置数量和prior_video_segments
        if (product && product.isBatchVodPay && isNotEmptyArray(product.selectVodVideos)) {
            attributes.prior_video_segments = product.selectVodVideos;
            quantity = product.selectVodVideos.length;
        }
        const biz = (mExtend.params && mExtend.params.biz) || 'default';
        const subBiz = (mExtend.params && mExtend.params.subBiz) || '';

        // 过滤重复的活动ID
        const promotions = [];
        if (isNotEmptyArray(receivings)) {
            const activityIds = [];
            receivings.forEach((receiving) => {
                if (receiving && receiving.activityId && activityIds.indexOf(receiving.activityId) < 0) {
                    promotions.push({ activityId: receiving.activityId });
                    activityIds.push(receiving.activityId);
                }
            });
        }

        // 免密
        if (product && product.attributes && product.attributes.certain_pay_channels) {
            // 支付
            if (noSecretPay) {
                const notSecretPayChannels = noSecretPayChannels(product);
                if (notSecretPayChannels.length) {
                    const noSecretPayChannel = noSecretPayChannelFromChannels(notSecretPayChannels, payChannel);
                    if (noSecretPayChannel && noSecretPayChannel.attributes) {
                        if (noSecretPayChannel.attributes.is_can_password_free) {
                            attributes.is_can_password_free = noSecretPayChannel.attributes.is_can_password_free;
                        }
                        if (noSecretPayChannel.attributes.has_can_password_free) {
                            attributes.has_can_password_free = noSecretPayChannel.attributes.has_can_password_free;
                        }
                        if (noSecretPayChannel.attributes.pay_with_password_free) {
                            attributes.pay_with_password_free = noSecretPayChannel.attributes.pay_with_password_free;
                        }
                    }
                }
            } else {
                // 开通
                const openNotSecretPayChannels = openNoSecretPayChannels(product);
                if (openNotSecretPayChannels.length) {
                    const openNoSecretPayChannel = noSecretPayChannelFromChannels(openNotSecretPayChannels, payChannel);
                    if (openNoSecretPayChannel && openNoSecretPayChannel.attributes) {
                        if (openNoSecretPayChannel.attributes.is_open_password_free) {
                            attributes.is_open_password_free = openNoSecretPayChannel.attributes.is_open_password_free;
                        }
                        if (openNoSecretPayChannel.attributes.has_can_password_free) {
                            attributes.has_can_password_free = openNoSecretPayChannel.attributes.has_can_password_free;
                        }
                        if (openNoSecretPayChannel.attributes.is_can_password_free) {
                            attributes.is_can_password_free = openNoSecretPayChannel.attributes.is_can_password_free;
                        }
                    }
                }
            }
        }
        const payChannelVmpRenderData = getPayChannelVmpRenderData();
        // 拼接支付渠道精准营销优惠文案,要确保商品上有is_show_merchant_promo并为true才去透传下发的字段
        if (payChannelVmpRenderData && product && product.attributes && product.attributes.is_show_merchant_promo === 'true') {
            if (payChannelVmpRenderData && payChannelVmpRenderData.promotionList && isNotEmptyArray(payChannelVmpRenderData.promotionList)) {
                payChannelVmpRenderData.promotionList.forEach((payChannelItem) => {
                    if (payChannelItem && payChannelItem.payChannel === payChannelId) {
                        if (payChannelItem.promotionPayExt && payChannelItem.promotionPayExt !== '') { // 两个参数必须同时存在才给服务端传，与狼牙确定协议
                            attributes.promotion_pay_ext = payChannelItem.promotionPayExt;
                        }
                    }
                });
            }
        }

        let deviceId = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.UT_DID) || ''; // 后端取该字段转化为utdid，用于CRM圈人，运营策略下发

        // 如果联合会员需要绑定的手机号
        if (getBindPhoneNumberData() && getBindPhoneNumberData().mobile) {
            attributes.user_mobile = getBindPhoneNumberData().mobile;
            // 默认地区为cn，和狼牙确认
            attributes.region = 'CN';
            attributes.out_user = {
                nick: '',
                type: 3,
            };
        }

        const params = {
            activityCode: (mExtend.params && mExtend.params.activityCode) || '',
            channel: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CREATE_ORDER_PARAM_CHANNEL) || '',
            payChannel: payChannelId, // 微信支付102
            products: [
                {
                    productId: product.productId,
                    promotions,
                    quantity,
                    skuId: product.skuId,
                    cycleBuyType,
                    attributes: JSON.stringify(productAttributes),
                },
            ],
            spm: mExtend.pageSPM,
            traceId: mExtend.traceId,
            attributes: JSON.stringify(attributes),
            appVersion: ALARM_CONFIG.VERSION[envType],
            orderType,
            biz,
            subBiz,
            deviceId,
            returnUrl: getReturnUrl(),
            tags: mExtend.tags,
            osType: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.HOST_SYSTEM_TYPE) || '', // 小程序支持IOS售卖后，需要区分IOS、Android
        }

        if (mExtend.goPayFrom === 'UnfinishedOrderDialog') {
            params.orderId = mExtend.unfinishedOrderId;
        }
        if(isWxMiniProgram()){
            params.appId = APP_INFO_CONFIG.APP_ID_LIST['wx'];
        }
        // *DIFF* 百度小程序不需要该参数
        params.payInfo = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.OPEN_ID) || '';
        result.type = 'success';
        result.message = cycleBuyType;
        result.res = params;
    }
    return result;
}

/**
 * 8.2.0之前版本调用的支付方式
 * @param orderLine
 * @param payChannel
 * @param extend
 * @param callback
 */
export const payProduct = (orderLine, payChannel, extend, callback) => {
    const result = getOrderParams(orderLine, payChannel, extend);
    const { type, message, res } = result;
    if (type === 'success' && res) {
        createOrderForMiniPay(res, payChannel, orderLine, message, callback);

        sendAlarm('6030', '创建订单参数成功-res=', res, payChannel, orderLine);
    } else {
        callback && callback({ type, message, res, from: 'createOrderParams' });

        sendAlarm('6004', '创建订单参数失败-result=', result, payChannel, orderLine);
    }
};


/**
 * 为继续支付订单，获取支付渠道
 * @param payChannelId
 */
export const getPayChannelBylId = (payChannelId = '') => {
    const allPayChannels = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.ALL_PAY_CHANNELS) || [];
    let payChannel = null;
    try {
        // 百度小程序中有直连微信支付继续支付时下发的微信支付渠道是102，需要扩冲一下匹配
        if(isBaiduAndIqiyiMiniProgram() && payChannelId.toString() === WX_MINI_CONFIG.PAY_INFO.FINAL_PAY_CHANNEL_ID){
            payChannel = allPayChannels?.find(item => {
                return WX_MINI_CONFIG.PAY_INFO.ALL_PAY_CHANNEL_ID.includes(item.data.payChannelId.toString());
            })?.data;
        } else {
            payChannel = allPayChannels?.find(item => {
                return item.data.payChannelId.toString() === payChannelId.toString();
            })?.data;
        }
    } catch (e) {
        console.error(e);
    }
    // 如果支付渠道不存在
    if (!payChannel) {
        if (isKuaishouMiniProgram()) {
            // 如果发生了异常，则采用默认的支付宝支付
            payChannel = {
                payChannelId: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_CHANNEL_ID,
                payImg: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_ICON,
                payTitle: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TITLE,
                terminal: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TERMINAL,
            }
        } else if (isWxMiniProgram()) { // 微信小程序使用默认兜底
            // 如果发生了异常，则采用默认的微信支付
            payChannel = {
                payChannelId: WX_MINI_CONFIG.PAY_INFO.FINAL_PAY_CHANNEL_ID,
                payImg: WX_MINI_CONFIG.PAY_INFO.WX_PAY_CHANNEL_ICON,
                payTitle: WX_MINI_CONFIG.PAY_INFO.WX_PAY_CHANNEL_TITLE,
                terminal: WX_MINI_CONFIG.PAY_INFO.WX_PAY_CHANNEL_TERMINAL,
            };
        } else if (isBaiduAndIqiyiMiniProgram()) {
            // 如果发生了异常，则采用默认的支付宝支付
            payChannel = {
                payChannelId: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_CHANNEL_ID,
                payImg: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_ICON,
                payTitle: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TITLE,
                terminal: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TERMINAL,
            }
        } else if (isByteDanceMiniProgram()) {
            payChannel = {
                payChannelId: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_CHANNEL_ID,
                payImg: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_ICON,
                payTitle: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_TITLE,
                terminal: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_TERMINAL,
            }
        }
        else {
            // default
            payChannel = allPayChannels?.[0]?.data;
        }
    } else {
        // 微信需要调整支付渠道ID
        if (isWxMiniProgram()) {
            payChannel.payChannelId = WX_MINI_CONFIG.PAY_INFO.FINAL_PAY_CHANNEL_ID;
        }

    }
    return payChannel;

}
