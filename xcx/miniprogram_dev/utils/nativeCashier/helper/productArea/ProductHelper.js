/**************************************************
 * @description:
 *  - created time: 2024/6/28
 *  -
 **************************************************/


import { getElementAtIndex, isEmptyStr, isNotEmptyArray, parseStrToInt, parseToBool } from "../../data/index";
import { sendBroadcast } from "../../BroadcastReceiver";

/**
 * 连续包勾选逻辑处理
 * @param {Object} product 当前选中的商品
 * @param {Boolean} isSelectCycleBug 是否勾选了连续包，默认未勾选
 */
export const getCycleBuyOptionParams = (product, isSelectCycleBug = false) => {
    let cycleBugText = null;
    let cycleBugDesc = null;
    // 自续费商品应该怎么处理：默认取数组的第一个和优惠的第一个，已经和服务端臻龙确认
    if (product && isNotEmptyArray(product.cycleBuyOptionPromotions)) {
        const firstReceivings = product.cycleBuyOptionPromotions[0];
        if (isNotEmptyArray(firstReceivings.receivings)) {
            const firstReceiving = firstReceivings.receivings[0];
            if (firstReceiving && firstReceiving.properties) {
                if (isSelectCycleBug) {
                    cycleBugText = firstReceiving.properties.cashierBottomTips;
                } else {
                    cycleBugText = firstReceiving.properties.cashierTopTips;
                }
                if (cycleBugText) {
                    firstReceivings.receivings.forEach((receiving) => {
                        if (receiving && receiving.desc) {
                            if (cycleBugDesc) {
                                cycleBugDesc = `，${receiving.desc}`;
                            } else {
                                cycleBugDesc = receiving.desc;
                            }
                        }
                    });
                }
            }
        }
    }
    return {
        cycleBugText,
        cycleBugDesc,
    };
};


/**
 * 判断两个支付渠道是否一致
 * @param {Object} newPayChannel 新Channel
 * @param {Object} oldPayChannel 上次选中的Channel
 */
export const equalsPayChannel = (newPayChannel, oldPayChannel) => {
    if (newPayChannel && oldPayChannel) {
        return newPayChannel.payChannelId === oldPayChannel.payChannelId;
    }
    return false;
};

/**
 * 获取订单中的所有商品
 * @param {Array} dataSource
 */
const getOrderLines = (dataSource) => {
    const mData = getElementAtIndex(dataSource, 0);
    const orderLines = [];
    let defSelectedProductIndex = 0;
    if (mData && isNotEmptyArray(mData.nodes)) {
        mData.nodes.forEach((orderLine, index) => {
            if (orderLine?.data?.line) {
                const line = Object.assign({}, orderLine.data.line, { action: orderLine.data.action });
                orderLines.push(line);
                // 初始化默认商品索引
                if (defSelectedProductIndex === 0 && orderLine.data.line.attributes) {
                    if (parseToBool(orderLine.data.line.attributes.selected)) {
                        defSelectedProductIndex = index;
                    }
                }
            }
        });
    }
    return {
        orderLines,
        defSelectedProductIndex,
    };
}

/**
 * 获取单个商品中的product对象
 * @param {Object} orderLine {"line":{"product":{}}}
 */
const getProduct = (orderLine) => {
    if (orderLine) {
        return orderLine.product;
    }
    return null;
};

/**
 * 获取主商品
 * @param {Object} orderLine 商品
 * @returns {Object|null} 如果是主推商品，返回这个商品，否则返回null
 */
const getProductForMainGoods = (orderLine) => {
    if (orderLine?.product && parseToBool(orderLine?.attributes?.crm_main_goods)) {
        const mainGoodsProduct = {
            productId: orderLine.productId,
            skuId: orderLine.skuId,
        }
        const promotions = [];
        orderLine.product.promotions?.forEach((item) => {
            item?.receivings?.forEach((activity) => {
                if (activity?.activityId) {
                    promotions.push({ activityId: activity.activityId });
                }
            });
        });
        if (isNotEmptyArray(promotions)) {
            mainGoodsProduct.promotions = promotions;
        }
        return mainGoodsProduct;
    }
    return null;
};

/**
 * 获取当前选中商品的描述和优惠信息文案
 *
 * @param {Object} product 当前选中商品
 * @param {Object} payChannel 支付渠道
 */
const getProductAndReceivingDesc = (product, payChannel) => {
    const result = {
        productDesc: '',
        receivingDesc: '',
        dataDescParams: {},
        svipRenew: {},
    };
    if (product) {
        // 小程序不支持话费支付
        if (parseStrToInt(payChannel?.payChannelId) !== 27) {
            result.productDesc = product.promotionDesc;
            result.dataDescParams.compliance_poptxt = product.promotionPopupTitle;
            result.dataDescParams.compliance_popdesc = product.promotionPopupDesc;

            if (isNotEmptyArray(product.promotions)) {
                const { receivings } = product.promotions[0];
                if (isNotEmptyArray(receivings)) {
                    receivings.forEach((receiving) => {
                        if (receiving && receiving.properties) {
                            const {
                                svip_renew_cycle_buy_desc,
                                svip_renew_title,
                                svip_renew_sign_unit_price,
                                svip_renew_sign_desc
                            } = receiving.properties;
                            result.svipRenew = {
                                svip_renew_cycle_buy_desc,
                                svip_renew_title,
                                svip_renew_sign_unit_price,
                                svip_renew_sign_desc,
                            };
                        }
                    });
                }
            }
        }
    }

    return result;
};

/**
 * 获取商品优惠信息
 * @param {Object} product
 * @returns
 */
const getPromotionProperties = (product) => {
    if (product && isNotEmptyArray(product.promotions)) {
        const fristPromotion = product.promotions[0];
        if (fristPromotion && isNotEmptyArray(fristPromotion.receivings)) {
            const fristReceiving = fristPromotion.receivings[0];
            return fristReceiving && fristReceiving.properties;
        }
    }
    return null;
};

/**
 * 获取商品倒计时参数
 * @param {Object} product
 * @returns
 */
const getProductCountDownParams = (product) => {
    // 商品倒计时测试数据
    // return {
    //   countdown: 100000000,
    //   countdownTitle: '限时优惠活动',
    // };
    const properties = getPromotionProperties(product);
    if (properties && properties.countDownMs) {
        const countdown = parseStrToInt(properties.countDownMs, 0) || 0;
        return {
            countdown: countdown > 1000 ? countdown - 1000 : null,
            countdownTitle: properties.countDownText,
        };
    }
    return null;
};


/**
 * 获取商品参数
 * @param {Object} orderLine 商品
 * @param {Object} payChannel 支付渠道
 * @returns {Object | null}
 */
const getProductParams = (orderLine, payChannel) => {
    const product = getProduct(orderLine);
    if (orderLine && product) {
        const { payChannelId } = payChannel || {};
        let price = 0;
        let priceUnit = '¥';
        let prePrice = null;
        const { title } = product;
        const tips = product.productBubble;
        const subtitle = product.productSubDesc;
        const bottomBubble = product.productBottomBubble;

        // 商品倒计时参数
        const { countdown, countdownTitle } = getProductCountDownParams(product) || {};

        // 判断是否下发货币符号
        if (orderLine && orderLine.attributes && orderLine.attributes.currency_symbol && orderLine.attributes.currency_symbol !== '') {
            priceUnit = orderLine.attributes.currency_symbol;
        }
        // 不为话费支付，其他支付渠道价格，注：微信小程序仅使用微信支付
        if (payChannelId !== '27' && orderLine.payPrice && orderLine.tagPrice) {
            price = orderLine.payPrice / 100;
            if (orderLine.payPrice !== orderLine.tagPrice) {
                prePrice = orderLine.tagPrice / 100;
            }
        }

        let commodityImage = '';
        let commodityImageHeight = '';
        if (product.promotions?.[0]?.receivings?.[0]?.properties?.commodityImage) {
            commodityImage = product.promotions[0].receivings[0].properties.commodityImage;
            commodityImageHeight = `height: ${orderLine.product.promotions[0].receivings[0].properties.commodityImageHeight * 690 / orderLine.product.promotions[0].receivings[0].properties.commodityImageWidth}rpx`;
        }


        return {
            price, // 支付价格
            priceUnit, // 货币符号
            prePrice, // 原价格
            title, // 商品名称
            subtitle, // 副标题，与商品原价格互斥
            tips, // 角标
            bottomBubble, // 底部气泡   话费支付不展示， slideB样式不展示
            countdown,  // 商品倒计时时间
            countdownTitle, // 商品倒计时文案
            commodityImage, // 补充加赠商品图片
            commodityImageHeight, // 补充加赠商品图片高度
        };
    }
    return {};
};


/**
 * 获取 1、2、多个 商品渲染数据
 * 1. 小程序没有做的功能: 倒计时；
 * @param {Array} orderLines 订单
 * @param {Number} selectIndex 选中的商品索引
 * @param {String} payChannel 支付渠道 初始化时 payChannel为null
 * @param {String} type 商品类型
 * @param {boolean} hintBottomTips 底部提示
 * @returns {Array}
 */
const getProductViewRenderData = (orderLines, selectIndex, payChannel, type, hintBottomTips) => {
    if (isNotEmptyArray(orderLines)) {
        const itemCount = orderLines.length;
        let maxPrice = 0;
        for (let i = 0; i < itemCount; i++) {
            if (orderLines[i] && parseInt(orderLines[i].tagPrice) > parseInt(maxPrice)) {
                maxPrice = orderLines[i].tagPrice;
            }
        }
        return orderLines.map((orderLine, index) => {

            const isSelected = selectIndex === index;


            const product = getProduct(orderLine);
            if (product) {
                // 连续包月勾选，标题及优惠信息描述
                const { cycleBugText, cycleBugDesc } = getCycleBuyOptionParams(product);
                // 更新主推商品
                const mainGoodsProduct = getProductForMainGoods(orderLine)
                if (mainGoodsProduct) {
                    sendBroadcast('onVirtualChanged', {
                        type: 'mainGoodsProduct',
                        data: mainGoodsProduct,
                    });
                }
                const {
                    price, // 支付价格
                    priceUnit, // 货币符号
                    prePrice, // 原价格
                    title, // 商品名称
                    subtitle, // 副标题，与商品原价格互斥
                    tips, // 角标
                    bottomBubble, // 底部气泡   话费支付不展示， slideB样式不展示
                    countdown,  // 商品倒计时时间
                    countdownTitle, // 商品倒计时文案
                    commodityImage, // 补充加赠商品图片
                    commodityImageHeight, // 补充加赠商品图片高度
                } = getProductParams(orderLine, payChannel);

                const { dataDescParams, productDesc, svipRenew } = getProductAndReceivingDesc(product, payChannel);
                const {
                    svip_renew_cycle_buy_desc,
                    svip_renew_title,
                    svip_renew_sign_unit_price,
                    svip_renew_sign_desc
                } = svipRenew || '';
                const hasProductSecondDesc = svip_renew_cycle_buy_desc || svip_renew_title || svip_renew_sign_unit_price || svip_renew_sign_desc;
                // 判断当前商品是否有优惠信息描述
                const hasDescOrSvipRenew = !!(isSelected && ((productDesc && productDesc !== '') || hasProductSecondDesc));

                const { compliance_poptxt, compliance_popdesc } = dataDescParams || '';
                // 是否有惊喜券弹窗
                const hasCompliancePop = !isEmptyStr(compliance_poptxt) && !isEmptyStr(compliance_popdesc);
                let popContentArr = [];
                if (hasCompliancePop) {
                    popContentArr = compliance_popdesc.split('\n')?.filter(Boolean)?.map((item, i) => {
                        return {
                            key: i,
                            iconUrl: 'https://gw.alicdn.com/imgextra/i2/O1CN01sMolSt1mQgq9CCZjl_!!6000000004949-2-tps-12-12.png',
                            text: item,
                        }
                    })
                }

                // 针对两个及以上商品
                let showType = 4
                if (bottomBubble) {
                    showType = isSelected ? 2 : 3;
                }

                return {
                    key: index,
                    id: index,
                    price,
                    prePrice,
                    unit: priceUnit,
                    tips,
                    title,
                    subtitle,
                    isSelected,
                    bottomBubble: type === 'slideB' || hintBottomTips ? '' : bottomBubble,
                    maxPrice,
                    upgradeinfo: orderLine.upgradeinfo,
                    hasDescOrSvipRenew,
                    orderLine,
                    productId: '',
                    countdown,
                    countdownTitle,
                    showType,
                    productDesc,
                    hasCompliancePop,
                    popContentArr,
                    compliance_poptxt,
                    productInfo: product,
                    cycleBugText,
                    cycleBugDesc,
                    productSelfDesc: product.productSelfDesc, // 此处允许为空，渲染时该模块则不显示
                    productSelfTitle: product.productSelfTitle, // 此处允许为空，渲染时该模块则不显示
                    commodityImage, // 补充加赠商品图片
                    commodityImageHeight, // 补充加赠商品图片高度
                };
            }
        }).filter(Boolean);
    }
    return [];
}

export {
    getOrderLines,
    getProduct,
    getProductViewRenderData,
};
