/**************************************************
 * @description:
 *  - created time: 2024/7/3
 *  -
 **************************************************/


import { convertToObject, isEmptyStr, isNotEmptyArray } from "../../data/index";
import { alarmEvent } from "../../track/TrackHelper";
import { BAIDU_MINI_PAY_CONFIG, BYTE_DANCE_MINI_PAY_CONFIG, KS_MINI_PAY_CONFIG, WX_MINI_CONFIG } from "../../../config/cashierConfig";
import { isBaiduAndIqiyiMiniProgram, isByteDanceMiniProgram, isKuaishouMiniProgram, isWxMiniProgram } from "../../../context/contextHelper";


const getProductForChannel = (orderLine) => {
    const product = {
        productPayChannels: [],
        productAbtestInfo: '',
    }
    if (orderLine && orderLine.attributes && orderLine.attributes.certain_pay_channels) {
        const certainPayChannels = convertToObject(orderLine.attributes.certain_pay_channels);
        if (isNotEmptyArray(certainPayChannels)) {
            product.productPayChannels = certainPayChannels;
        }
        const certainPayChannelsTrackInfo = convertToObject(orderLine.attributes.certain_pay_channel_trackInfo);
        if (certainPayChannelsTrackInfo) {
            product.productAbtestInfo = certainPayChannelsTrackInfo.abtestInfo;
        }
    }
    return product;
}

/**
 * 判断当前商品是否支持的该支付渠道
 * @param {Object} productPayChannel 当前选中商品支持的支付渠道
 * @param {String} payChannelId 支付渠道ID
 */
const isProductSupportChannel = (productPayChannel, payChannelId) => {
    const result = {
        isSupport: false,
        visible: 0,
    };
    if (payChannelId && productPayChannel) {
        if (productPayChannel && productPayChannel.payChannelId.toString() === payChannelId.toString()) {
            result.isSupport = true;
            result.visible = productPayChannel.visible;
        }
    }
    return result;
};

/**
 *  处理有效的支付渠道列表（以商品下发的支付渠道数据为基础的和支付渠道组件下发的数据做交集），获取默认选中的支付渠道、所有有效的支付渠道
 * @param {Array} payChannels 支付渠道元数据
 * @param {Object} orderLine 当前选中商品
 *
 * @returns {Object} 返回所有有效的支付渠道
 */
const handlePayChannels = (payChannels, orderLine) => {

    // 所有有效的支付渠道
    const allPayChannels = [];
    // 所有可见的支付渠道
    const visiblePayChannels = [];

    let isShowFold = false;
    if (isNotEmptyArray(payChannels)) {
        const { productPayChannels, productAbtestInfo } = getProductForChannel(orderLine);
        // 按照当前选中商品所支持的支付渠道顺序获得所有渠道信息
        if (productPayChannels && productPayChannels.length > 0) {
            productPayChannels.forEach((productChannel) => {
                for (let index = 0; index < payChannels.length; index++) {
                    const payChannelData = payChannels[index];
                    if (payChannelData && payChannelData.data && payChannelData.data.payChannelId) {
                        // 使用浅拷贝，防止基本数据类型 isSelected 在切换商品后干扰
                        const payChannel = Object.assign({}, payChannelData.data);
                        const payChannelId = payChannel.payChannelId.toString();
                        // 判断该商品是否支持该支付渠道
                        const { isSupport, visible } = isProductSupportChannel(productChannel, payChannelId);
                        if (isSupport) {
                            let payChannelValid = false;
                            // 支付渠道支付策略abTestInfo需要替换为对应商品支付策略
                            if (productAbtestInfo && payChannelData.data.action && payChannelData.data.action.report && payChannelData.data.action.report.trackInfo) {
                                if (!isEmptyStr(productAbtestInfo)) {
                                    payChannelData.data.action.report.trackInfo.abTestInfo1 = productAbtestInfo;
                                }
                            }
                            // *DIFF* 不同小程序支付渠道的差异
                            // 支付渠道对应的ID查询 https://visp.youku.com/tools/channel
                            if (isKuaishouMiniProgram() && KS_MINI_PAY_CONFIG.ALL_PAY_CHANNEL_ID.includes(payChannelId)) {
                                payChannelValid = true;
                            } else if (isByteDanceMiniProgram() && BYTE_DANCE_MINI_PAY_CONFIG.ALL_PAY_CHANNEL_ID.includes(payChannelId)) {
                                payChannelValid = true;
                            } else if (isWxMiniProgram() && WX_MINI_CONFIG.PAY_INFO.ALL_PAY_CHANNEL_ID.includes(payChannelId)) {
                                payChannel.payChannelId = WX_MINI_CONFIG.PAY_INFO.FINAL_PAY_CHANNEL_ID;
                                payChannelValid = true;
                            } else if (isBaiduAndIqiyiMiniProgram()) {  // 百度小程序
                                payChannelValid = true;
                            }
                            if (payChannelValid) {
                                payChannel.visible = visible;
                                allPayChannels.push(payChannel);
                                if (visible === 1) {
                                    visiblePayChannels.push(payChannel);
                                } else {
                                    isShowFold = true;
                                }
                            }
                        }
                    }
                }
            });
        } else {
            if (orderLine) {
                alarmEvent('6110', '商品上下发的支持的支付渠道为空', null);
            }
        }
    }
    //  构建兜底方案
    if (allPayChannels.length === 0) {
        if (isKuaishouMiniProgram()) {
            allPayChannels.push({
                payChannelId: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_CHANNEL_ID,
                payImg: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_ICON,
                payTitle: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TITLE,
                terminal: KS_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TERMINAL,
            });
        } else if (isWxMiniProgram()) {
            allPayChannels.push({
                payChannelId: WX_MINI_CONFIG.PAY_INFO.FINAL_PAY_CHANNEL_ID,
                payImg: WX_MINI_CONFIG.PAY_INFO.WX_PAY_CHANNEL_ICON,
                payTitle: WX_MINI_CONFIG.PAY_INFO.WX_PAY_CHANNEL_TITLE,
                terminal: WX_MINI_CONFIG.PAY_INFO.WX_PAY_CHANNEL_TERMINAL,
            });
        } else if (isBaiduAndIqiyiMiniProgram()) {
            allPayChannels.push({
                payChannelId: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_CHANNEL_ID,
                payImg: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_ICON,
                payTitle: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TITLE,
                terminal: BAIDU_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.ALI_PAY_TERMINAL,
            });
        } else if (isByteDanceMiniProgram()) {
            allPayChannels.push({
                payChannelId: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_CHANNEL_ID,
                payImg: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_ICON,
                payTitle: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_TITLE,
                terminal: BYTE_DANCE_MINI_PAY_CONFIG.DEFAULT_PAY_INFO.DOUYIN_PAY_TERMINAL,
            });
        }
        console.error('WGT ==== 6109 支付渠道组件下发的支付渠道异常，将使用兜底方案');
        alarmEvent('6109', '支付渠道组件下发的支付渠道异常，将使用兜底方案', null);
    }
    return {
        allPayChannels, // 所有有效的支付渠道
        visiblePayChannels, // 所有可见的支付渠道
        channelIndex: 0,    // 默认选中的支付下标
        isShowFold, // 是否展示折叠
    };
};

export {
    handlePayChannels,
};
