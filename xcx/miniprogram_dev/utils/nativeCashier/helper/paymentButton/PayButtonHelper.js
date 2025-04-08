/**************************************************
 * @description:
 *  - created time: 2024/7/5
 *  -
 **************************************************/
import { isNotEmptyArray, isTypeString, parseToBool, parseToJson } from "../../data/index";
import { getProduct } from "../productArea/ProductHelper";
// import { isCoinChannel } from "../../../../utils/nativeCashier/biz/index";


// 购前协议勾选框配置状态：显示不选中
export const CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED = 'UNSELECTED';
// 购前协议勾选框配置状态：显示并选中
export const CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_SELECTED = 'SELECTED';
// 购前协议勾选框配置状态：显示不选中，且不阻断
export const CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED_UNBLOCK = 'UNSELECTED_UNBLOCK';


export const needBlockPayAgreementSelect = (orderLine) => {
    if (orderLine) {
        const state = getPrePayAgreementSelectState(orderLine);
        if (state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED
            || state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_SELECTED) {
            return true;
        }
    }
    return false;
};

/**
 * 购前协议选中状态
 * @param {*} orderLine
 * @returns UNSELECTED = 不勾选；SELECTED = 默认勾选；默认不显示
 * @see CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED
 * @see CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_SELECTED
 */
export const getPrePayAgreementSelectState = (orderLine) => {
    if (orderLine && orderLine.attributes) {
        return orderLine.attributes.popupOnPay;
    }
    return '';
};

/**
 * 非国际版商品，服务端控制是否需要勾选协议。
 * 当出现勾选框如果没有选中支付协议，弹窗提示。点击“同意”直接发起支付，点击“不同意”展示提示框
 * @param {*} orderLine
 * @returns
 */
export const needShowPrePayAgreementSelect = (orderLine) => {
    if (orderLine) {
        const state = getPrePayAgreementSelectState(orderLine);
        if (state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED
            || state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_SELECTED
            || state === CONFIG_PRE_PAY_AGREEMENT_SELECT_STATE_UNSELECTED_UNBLOCK) {
            return true;
        }
    }
    return false;
};

/**
 * 判断是否是支付渠道优惠
 * @param {Object} orderLine 订单行
 * @param {Object} payChannel 支付渠道
 * @param {Object} tipsInfo 支付渠道优惠信息
 */
export const isPayChannelTip = (orderLine, payChannel, tipsInfo) => {
    let result = false;
    if (tipsInfo && tipsInfo.promotion && isTypeString(tipsInfo.promotion.payChannelPrice)) {
        if (isTypeString(tipsInfo.productId) &&
            isTypeString(tipsInfo.skuId) && orderLine &&
            isTypeString(orderLine.productId) &&
            isTypeString(orderLine.skuId)) {
            if (tipsInfo.productId === orderLine.productId && tipsInfo.skuId === orderLine.skuId) {
                if (isTypeString(tipsInfo.payChannelId) && payChannel && isTypeString(payChannel.payChannelId)) {
                    if (tipsInfo.payChannelId === payChannel.payChannelId) {
                        result = true;
                    }
                }
            }
        }
    }
    return result;
};

/**
 * 遍历商品的优惠组合及选中的优惠券确认最终的优惠
 * @param {Object} product 商品
 * @param {String} activityId 选中活动ID
 */
const getCombinedPromotions = (product, activityId) => {
    let orderLinePromotion = null;
    if (product) {
        const {combinedPromotions} = product;
        if (isNotEmptyArray(combinedPromotions)) {
            combinedPromotions.forEach((promotion) => {
                if (promotion && promotion.condition) {
                    if (isNotEmptyArray(promotion.condition.combinedIds)) {
                        promotion.condition.combinedIds.forEach((combined) => {
                            if (combined) {
                                if (
                                    combined.type === 'cycle_buy_activity' ||
                                    combined.type === 'pay_channel_activity' ||
                                    combined.type === 'coupon'
                                ) {
                                    if (combined.id === activityId) {
                                        orderLinePromotion = promotion.promotion;
                                    }
                                }
                            }
                        });
                    }
                }
            });
            // 如果没关联到优惠券，或者用户不使用优惠券，查看combinedPromotions中的第一个优惠，和臻龙确认过
            if (orderLinePromotion === null) {
                let hasCouponPromotion = false;
                const firstCombinedPromotion = combinedPromotions[0];
                if (
                    firstCombinedPromotion &&
                    firstCombinedPromotion.promotion &&
                    isNotEmptyArray(firstCombinedPromotion.promotion.activities)
                ) {
                    // 要过判断第一个优惠组合是否包含优惠券或勾选自续费，如果包括那就不能使用,防止服务端异常情况出现;20200908增加过滤支付渠道优惠
                    if (
                        firstCombinedPromotion &&
                        firstCombinedPromotion.condition &&
                        isNotEmptyArray(firstCombinedPromotion.condition.combinedIds)
                    ) {
                        firstCombinedPromotion.condition.combinedIds.forEach((combined) => {
                            if (
                                combined &&
                                (combined.type === 'coupon' ||
                                    combined.type === 'cycle_buy_activity' ||
                                    combined.type === 'pay_channel_activity')
                            ) {
                                hasCouponPromotion = true;
                            }
                        });
                    }
                }
                if (!hasCouponPromotion) {
                    orderLinePromotion = combinedPromotions[0] && combinedPromotions[0].promotion;
                }
            }
        }
    }
    return orderLinePromotion;
};


/**
 * 获取选中优惠券的Id
 * @param {Object} product 商品
 * @param {Object} couponInfo 优惠券信息
 */
const getCouponPromotions = (product, couponInfo) => {
    let couponId = null;
    const isNouseCoupon = couponInfo && couponInfo.isNouseCoupon;
    const coupons = couponInfo && couponInfo.coupons;
    const selectIndex = (couponInfo && couponInfo.selectIndex) || 0;
    let coupon = {};
    if (isNotEmptyArray(coupons) && selectIndex >= 0 && selectIndex < coupons.length) {
        coupon = coupons[selectIndex];
    }
    if (!isNouseCoupon && coupon.couponId) {
        // 使用优惠券
        couponId = coupon.couponId;
    } else if (isNouseCoupon) {
        // 不使用优惠券
        couponId = '';
    } else if (product && isNotEmptyArray(product.coupons)) {
        const count = product.coupons.length;
        for (let i = 0; i < count; i++) {
            const couponTemp = product.coupons[i];
            if (couponTemp && parseToBool(couponTemp.state)) {
                couponId = couponTemp.couponId;
                break;
            }
        }
    }
    return couponId;
};

/** * 获取商品支持的支付渠道优惠
 * @param {Object} product 当前选中商品
 * @param {string} payChannelId 当前选中支付渠道
 */
const getProductPayChannelPromotions = (product, payChannelId) => {
    let activityId = null;
    const noPriceRelatedPromotion = [];
    if (product) {
        const productPayChannelPromotions = product.payChannelPromotions;
        // 遍历商品支持的支付渠道优惠
        if (isNotEmptyArray(productPayChannelPromotions) && payChannelId) {
            productPayChannelPromotions.forEach((promotion) => {
                if (promotion && promotion.attributes && promotion.attributes.pay_channel === payChannelId) {
                    const {receivings} = promotion;
                    if (isNotEmptyArray(receivings)) {
                        let priceRelatedActivityId = '';
                        receivings.forEach((receiving) => {
                            if (receiving) {
                                // 支付渠道优惠，无论是否和价格相关，只要有优惠则支付渠道就算有优惠，与澄思确认
                                activityId = receiving.activityId;
                                if (parseToBool(receiving.priceRelated)) {
                                    priceRelatedActivityId = receiving.activityId;
                                } else {
                                    // 和价钱无关的活动
                                    noPriceRelatedPromotion.push({
                                        activityId: receiving.activityId,
                                        receivingId: receiving.receivingId,
                                    });
                                }
                            }
                        });
                        if (priceRelatedActivityId !== '') {
                            activityId = priceRelatedActivityId;
                        }
                    }
                }
            });
        }
    }
    return {activityId, noPriceRelatedPromotion};
};

/**
 * 获取连续包月勾选的优惠活动ID和其他和价格无关的优惠信息
 * @param {Object} product 当前选中商品
 */
const getCycleBuyOptionPromotions = (product) => {
    let activityId = '';
    const noPriceRelatedPromotion = [];
    if (product) {
        // 商品上携带的连续包优惠
        const {cycleBuyOptionPromotions} = product;
        // 找到勾选自续费的activityId
        if (isNotEmptyArray(cycleBuyOptionPromotions) && cycleBuyOptionPromotions[0]) {
            const {receivings} = cycleBuyOptionPromotions[0];
            if (isNotEmptyArray(receivings)) {
                receivings.forEach((receiving) => {
                    if (receiving) {
                        if (parseToBool(receiving.priceRelated)) {
                            activityId = receiving.activityId;
                        } else {
                            // 和价钱无关的活动
                            noPriceRelatedPromotion.push({
                                activityId: receiving.activityId,
                                receivingId: receiving.receivingId
                            });
                        }
                    }
                });
            }
        }
    }
    return {activityId, noPriceRelatedPromotion};
};

/**
 * 获取支付渠道协议前置信息
 * @param {Object} payChannel 当前支付渠道
 * @param {boolean} cycle_buy_supported  是否为连续包商品
 * @returns
 */
export const getPayAgreementInfo = (payChannel, cycle_buy_supported) => {
    const attributes = parseToJson(payChannel && payChannel.attributes);
    //  mock数据
    // attributes.show_protocol_on_pay_channel = "{\"protocol_text\":\"开通连续包后在会员到期前优酷将自动发起续费，可随时在支付宝解约。\",\"protocol_url\":\"https://render.alipay.com/p/f/fd-jcewajz2/index.html\",\"selected_by_default\":true}";
    if (attributes && cycle_buy_supported && attributes.show_protocol_on_pay_channel) {
        const protocol_on_pay_channel = parseToJson(attributes.show_protocol_on_pay_channel);
        protocol_on_pay_channel.selected_by_default = parseToBool(protocol_on_pay_channel.selected_by_default);
        return protocol_on_pay_channel;
    }
    return null;
};


/**
 * 1、支付按钮4中情况：
 *    a、如果是连续包商品，并且支付渠道包含连续包协议----《开通并支付》
 *    b、如果是U钻支付渠道，并且U钻不足----《充值购买》
 *    c、如果是家庭卡收银台----《为Ta支付》
 *    d、默认状态----《立即支付》
 * 2、支付价格
 *    a、话费支付
 *      - 有话费优惠，取话费优惠价格
 *      - 无话费优惠，取话费自身价格
 *    b、
 *    2、组合优惠价格， 先取组合优惠价格，如果没有组合优惠，取商品价格或U钻价格
 *    3、
 * 3、优惠信息
 * @param {Object} orderLine 当前选中商品
 * @param {Object} payChannel 当前选中支付渠道
 // * @param {Object} isMSCardsCashier 是否为家庭卡收银台
 // * @param {Number} coinBalance 当前用户U钻数量
 * @param {Boolean} isSelectedCycleBuyOption 是否选中了连续包勾选
 * @param {Object} couponInfo 当前选中但优惠券信息
 */

// const coinBalance = 0; // U钻数量
// const isSelectedCycleBuyOption = true; // 是否勾选了连续包选项
// const couponInfo = { type: 'coupon', data: 'coupon' } // 优惠券详情 type: 'used'、'noUse'、'def' 使用，不使用，默认
export const getRenderParams = (
    orderLine,
    payChannel,
    isSelectedCycleBuyOption,
    couponInfo,
) => {
    // 支付按钮文案
    let payButtonText = '确认协议并支付'; // isMSCardsCashier ? '为Ta支付' : '立即支付';
    // 支付价格
    let payPrice = '';

    let priceUnit = '¥';
    // 支付优惠文案
    let payBtnBonus = '';
    let payBtnBonusNumber = 0;
    // 当前支付渠道
    const payChannelId = payChannel && payChannel.payChannelId;
    // 支付按钮是否有效
    let btnPayAvailable = 'true';
    const product = getProduct(orderLine);
    let orderLinePromotion = null;
    // 如果product不为空，那么orderLine不可能为空，可直接使用
    if (product) {
        // 和价格无关的优惠
        let noPriceRelatedPromotions = [];
        // 商品优惠组合
        const combinedPromotions = product.combinedPromotions || [];
        // 选中优惠活动的ID，分为连续包优惠、支付渠道优惠和优惠券优惠，三个不可能同时出现
        let selectActivityId = '';
        // 确定用户选中或系统默认选中第一个Coupon组合
        if (isNotEmptyArray(combinedPromotions)) {
            // 首现判断是否勾选自续费，如果勾选自续费则不使用任何优惠券，只使用自续费优惠，已经与真龙确认
            if (isSelectedCycleBuyOption) {
                // 勾选了连续包
                const {activityId, noPriceRelatedPromotion} = getCycleBuyOptionPromotions(product);
                noPriceRelatedPromotions = noPriceRelatedPromotion;
                selectActivityId = activityId;
            } else {
                const {activityId, noPriceRelatedPromotion} = getProductPayChannelPromotions(product, payChannelId);
                // 之前的TODO 这个如果上优惠券活动ID， 是否要带上
                noPriceRelatedPromotions = noPriceRelatedPromotion;
                if (activityId) {
                    selectActivityId = activityId;
                } else {
                    selectActivityId = getCouponPromotions(product, couponInfo);
                }
            }
            orderLinePromotion = getCombinedPromotions(product, selectActivityId);
            if (orderLinePromotion && orderLinePromotion.bonus && orderLinePromotion.bonus > 0) {
                payBtnBonus = orderLinePromotion.bonus / 100;
            }
        }
        // 如果有优惠组合，取优惠组合价格，如果没有，取商品价格
        if (orderLinePromotion) {
            // 把价钱无关的优惠塞到优惠中
            if (isNotEmptyArray(noPriceRelatedPromotions)) {
                orderLinePromotion.noPriceRelatedPromotion = noPriceRelatedPromotions;
            }

            if (orderLinePromotion.promUnitPrice) {
                payPrice = orderLinePromotion.promUnitPrice;
            } else {
                payPrice = orderLine.payPrice;
            }
        }
            // else if (
            //     isCoinChannel(payChannelId) &&
            //     product.priceUnitGroup &&
            //     product.priceUnitGroup.priceUnitMap &&
            //     product.priceUnitGroup.priceUnitMap.UCOIN.unitPrice
            // ) {
            //     // 如果服务端没有返回combinedPromotions，使用商品默认的支付价钱
            //     payPrice = product.priceUnitGroup.priceUnitMap.UCOIN.unitPrice;
        // }
        else {
            payPrice = orderLine.payPrice;
        }

        // 判断是否下发货币符号
        if (orderLine && orderLine.attributes && orderLine.attributes.currency_symbol && orderLine.attributes.currency_symbol !== '') {
            priceUnit = orderLine.attributes.currency_symbol;
        }

        // 处理是否包含和价格无关的优惠，并得出虚拟的优惠总额
        let noPriceRelatedPromotionBenefitOriginalPrice = 0;
        if (
            isNotEmptyArray(product.promotions) &&
            product.promotions[0] &&
            isNotEmptyArray(product.promotions[0].receivings)
        ) {
            const firstPromotion = product.promotions[0];
            if (firstPromotion && isNotEmptyArray(firstPromotion.receivings)) {
                firstPromotion.receivings.forEach((receiving) => {
                    if (receiving && !parseToBool(receiving.priceRelated)) {
                        const {properties} = receiving;
                        if (
                            properties &&
                            properties.benefitDesc &&
                            properties.benefitDiscountPrice &&
                            properties.benefitOriginalPrice
                        ) {
                            noPriceRelatedPromotionBenefitOriginalPrice += properties.benefitOriginalPrice / 100;
                        }
                    }
                });
            }
        }

        // 非批量购买收银台
        payPrice /= 100;
        if (payBtnBonus) {
            payBtnBonusNumber = parseFloat(payBtnBonus + noPriceRelatedPromotionBenefitOriginalPrice).toFixed(2);
            payBtnBonus = `已减¥${payBtnBonusNumber}`;
        }
        // }
    }
    return {
        payButtonText,
        payPrice,
        priceUnit,
        payBtnBonus,
        payChannelId,
        orderLinePromotion,
        btnPayAvailable,
        payBtnBonusNumber
    };
};
