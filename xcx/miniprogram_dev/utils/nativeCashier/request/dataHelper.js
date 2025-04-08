/* jshint esversion: 6 */
/**************************************************
 * @description:
 *  - created time: 2024/6/19
 *  -
 **************************************************/

import { convertToObject, extractQueryParameters, getField, isEmptyStr, isNonEmptyObject, isNotEmptyArray, isTypeString, parseToBool } from "../data/index";
import { generateTags, getProductStyle, getUserComponentHeight, getUserInfoType, isBannerComponent, isMainProductComponentType, isPayAgreementComponentType, isPayButtonComponentType, isPayChannelComponentType, isSimpleScreenCashier, isTopBannerComponentType, isUnPayedOrderComponentType, isUserComponentType, isVipBenefitComponentType } from "../biz/index";
import CashierDataManager from "../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../store/constants";
import { doAction } from "../eventHandler/index";
import { registerListener, sendBroadcast } from "../BroadcastReceiver";
import { getMTopInstance } from "./MTopApi";
import { getAlarmInstance } from "../track/alarmWrapper/index";
import { getTrackInstance } from "../track/aplusWrapper/index";
import { getPassportInstance } from "../account/pspSdk";
import { CASHIER_ENVIRONMENT, COMMON_CONFIG } from "../../config/cashierConfig";
import { alarmEventWithPreDesc } from "../track/TrackHelper";

const app = getApp();

// region 全局变量
const extend = {};
// tab列表
let mTabs = [];
let mModules = [];

// 主推商品
let mainGoodsProduct = null;

// 通用crm老标签（渲染返回）
let renderCRMParams = null;

// 支付渠道优惠文案数据 与耕田哥沟通微信支付没有暂时保留
let payChannelVmpRenderData = null;

// 绑定手机号弹窗数据
let bindPhoneNumberData = null;

// endregion

// region 全局变量的get和set函数

export function getBindPhoneNumberData() {
    return bindPhoneNumberData;
}

export function setBindPhoneNumberData(data) {
    bindPhoneNumberData = data;
}


export const getPayChannelVmpRenderData = () => {
    return payChannelVmpRenderData;
};

export const setRenderCRMParams = (params) => {
    renderCRMParams = params;
};

export const getRenderCRMParams = () => {
    return renderCRMParams;
};

/**
 * 设置主推商品
 * @param {Object} product 主推商品
 */
const setMainGoodsProduct = (product) => {
    mainGoodsProduct = product;
};

/**
 * 获取主推商品
 */
const getMainGoodsProduct = () => {
    return mainGoodsProduct;
};

function getExtend() {
    return extend;
}

function getTabs() {
    return mTabs;
}

function getModules() {
    return mModules;
}

function setReportABTrackInfo(dataSource) {
    let reportTrackInfo = {};
    if (isNotEmptyArray(dataSource)) {
        const item = dataSource[0];
        if (isNonEmptyObject(item) && item.nodes && isNotEmptyArray(item.nodes)) {
            const node = item.nodes[0];
            if (isNonEmptyObject(node) && node.data && node.data.action && node.data.action.report && node.data.action.report.trackInfo) {
                const { trackInfo } = node.data.action.report;
                if (isNonEmptyObject(trackInfo)) {
                    reportTrackInfo = trackInfo;
                }
            }
        }
    }

    if (isNonEmptyObject(reportTrackInfo) && reportTrackInfo.abTestInfo) {
        if (isTypeString(reportTrackInfo.abTestInfo)) {
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.AB_TEST_INFO, reportTrackInfo.abTestInfo);
        }
    }
}

function getReportABTrackInfo() {
    return CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.AB_TEST_INFO) || '';
}

// endregion

// region 收银台初始化

/**
 * 初始化扩展参数对象
 * @param {Object} paramsObj - 包含初始化所需参数的对象
 */
function initExtend(paramsObj, linkParams) {
    extend.sceneType = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.SCENE_TYPE) || '';
    // 获取哥伦布灰度标识
    extend.gray = (getField(linkParams, 'gray') || getField(paramsObj, 'gray')) === '1' ? 1 : 0;
    extend.en_spm = getField(linkParams, 'en_spm') || getField(paramsObj, 'en_spm') || '0.0.0.0';
    extend.en_scm = getField(linkParams, 'en_scm') || getField(paramsObj, 'en_scm') || '';

    extend.actv_spm = getField(paramsObj, 'actv_spm') || getField(linkParams, 'actv_spm') || '';
    extend.actv_scm = getField(paramsObj, 'actv_scm') || getField(linkParams, 'actv_scm') || '';
    extend.en_component_id = getField(paramsObj, 'en_component_id');
    extend.en_sid = getField(linkParams, 'en_sid') || getField(paramsObj, 'en_sid');
    extend.en_vid = getField(linkParams, 'en_vid') || getField(paramsObj, 'en_vid');
    extend.en_info = getField(linkParams, 'en_info') || getField(paramsObj, 'en_info');
    extend.refer = getField(linkParams, 'refer') || getField(paramsObj, 'refer');
    extend.en_id = getField(linkParams, 'en_id') || getField(paramsObj, 'en_id');
    extend.from = getField(linkParams, 'from') || getField(paramsObj, 'from');
    extend.isAutoShow = getField(linkParams, 'isAutoShow') || getField(paramsObj, 'isAutoShow') || '0';
    extend.params = paramsObj;
    extend.pageSPM = 'a2h07.11146698';
    extend.targetType = paramsObj.targetType || 0;
    extend.orderSequence = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.OPEN_ID);
    extend.tabStyle = getField(linkParams, 'tabStyle');
    extend.tags = generateTags(paramsObj);
    // extend.isAutoPay = paramsObj.attributes.isAutoPay;
    // extend.wxtip = +paramsObj.attributes.wxtip || 0; // 1 微信软拦截 2 微信硬拦截

    // ********************* 微信小程序中添加的字段 ****************************
    extend.products = paramsObj.products;
    extend.attributes = paramsObj.attributes;
    extend.isLogined = app?.globalData?.isLogin || app?.cashier?.isLogined || false;
    extend.envs = getField(linkParams, 'envs') || getField(paramsObj, 'envs');

    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.PRE_PAGE_SPM_AB, extend.en_spm);
    // ***************************** end ************************************
}

/**
 * 初始化参数对象并设置默认值
 * @param {string} urlString - 链接中的参数字符串
 * @returns {Object} 处理后的参数对象
 */
function initParams(urlString) {
    const linkParams = extractQueryParameters(urlString || '');
    const paramsObj = convertToObject(linkParams.h5params || '') || {};
    // 默认值参考主客收银台
    if (!paramsObj.channel) {
        paramsObj.channel = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CREATE_ORDER_PARAM_CHANNEL) || '';
    }
    if (!paramsObj.activityCode) {
        paramsObj.activityCode = 'buy_h5';
    }
    if (!paramsObj.pageKey) {
        paramsObj.pageKey = 'MINIAPPSTANDARD_YOUKU';
    }

    // 如果业务方没有biz 指定默认的biz
    if (!paramsObj.biz) {
        paramsObj.biz = 'default';
    }
    paramsObj.attributes = convertToObject(paramsObj.attributes) || {};
    return { paramsObj, linkParams };
}

/**
 * 初始化属性并将其附加到attributes对象上.
 * 将相关全链路参数(en_scm, en_spm, en_id, en_from, en_refer, en_sid, en_vid)带到订单
 *
 * @param {Object} paramsObj - 包含初始化所需参数的对象.
 */
function initAttributes(paramsObj) {
    const attributes = paramsObj.attributes;
    if (extend.en_scm) {
        attributes.en_scm = extend.en_scm;
    }

    if (extend.en_spm) {
        attributes.en_spm = extend.en_spm;
    }

    if (extend.en_id) {
        attributes.en_id = extend.en_id;
    }

    if (extend.from) {
        attributes.from = extend.from;
    }

    if (extend.refer) {
        attributes.refer = extend.refer;
    }

    if (extend.en_sid) {
        attributes.en_sid = extend.en_sid;
    }

    if (extend.en_vid) {
        attributes.en_vid = extend.en_vid;
    }


    // 主副卡参数处理 0:副卡续费 1:回赠
    // attributes.targetType = paramsObj.targetType || 0;

    // 支持IAP老用户优惠的收银台（之后要H5支持IAP支付，所以将改参数设置为通用参数）
    attributes.support_iap_olduser_cashier = true;
    // OTT订单关联参数
    attributes.order_seq = extend.orderSequence;
}

// 注册全局的数据监听器
function registerListeners() {
    /**
     * 虚拟组件数据
     */
    registerListener('onVirtualChanged', 'dataHelper', (event) => {
        if (event && event.type) {
            const { data } = event;
            if (event.type === 'mainGoodsProduct') {
                // 主推商品
                mainGoodsProduct = data;
            }
        }
    });
}

/**
 * 初始化 MTOP & 埋点 & 预警 SDK，并将其挂载在 app的cashier全局引用中，进行使用
 */
function initSDK() {
    const envs = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CASHIER_ENVIRONMENT);
    const mtop = getMTopInstance(envs);
    const alarmSDK = getAlarmInstance();
    const trackSDK = getTrackInstance();
    const loginSDK = getPassportInstance();
    const isLogined = false;
    app.cashier = {
        mtop,
        alarmSDK,
        trackSDK,
        loginSDK,
        isLogined,
    }
}


/**
 * 初始化收银台，收银台业务的总入口，参考主客收银台的initApp()
 */
function initCashier(urlString) {
    // 解析收银台链接，设置对应的全局变量
    const { paramsObj, linkParams } = initParams(urlString);
    // 向extend中添加参数
    initExtend(paramsObj, linkParams);
    // 向attributes中添加参数
    initAttributes(paramsObj);
    // ========================存入全局参数===========================
    // CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.CASHIER_ENVIRONMENT, CASHIER_ENVIRONMENT.DEFAULT_ENV);
    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.CASHIER_ENVIRONMENT, extend.envs || CASHIER_ENVIRONMENT.DEFAULT_ENV);
    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.PAGE_KEY, paramsObj.pageKey || 'MINIAPPSTANDARD_YOUKU');
    // 注册全局的数据监听器
    registerListeners();
    // ===================  初始化埋点 & 预警 SDK =====================
    initSDK();
}

// endregion

// region 接口数据解析

export const parseDetainmenCRMParams = (result, ms_codes) => {
    if (isNonEmptyObject(result) && isNonEmptyObject(result.data) && isTypeString(ms_codes)) {
        const data = (result.data[ms_codes] && result.data[ms_codes].data) || {};
        if (isNonEmptyObject(data) && isNotEmptyArray(data.nodes)) {
            let nodes = null;
            for (let i = 0; i < data.nodes.length; i++) {
                const node = data.nodes[i];
                if (isNonEmptyObject(node) && isNotEmptyArray(node.nodes)) {
                    nodes = node.nodes;
                    break;
                }
            }
            if (isNotEmptyArray(nodes)) {
                for (let i = 0; i < nodes.length; i++) {
                    const node = nodes[i];
                    if (isNonEmptyObject(node) && isNotEmptyArray(node.nodes)) {
                        let data = node.nodes[0];
                        if (isNonEmptyObject(data) && isTypeString(data.type)) {
                            if (data.type == '18020' ||
                                data.type == '18410' ||
                                data.type == '18030' ||
                                data.type == '18290' ||
                                data.type == '18970' ||
                                data.type == '18220' ||
                                data.type == '18221') {
                                if (isNonEmptyObject(data.data) && isNonEmptyObject(data.data.other) && isNonEmptyObject(data.data.other.attributes)) {
                                    return JSON.parse(JSON.stringify(data.data.other.attributes));
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    return null;
};

function parseData(data) {
    const extend = getExtend();
    if (data && isNotEmptyArray(data.nodes)) {
        if (!isEmptyStr(data?.data?.title)) {
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.PAGE_TITLE, data.data.title);
        }
        // 仅展示vip购买的tab
        // const vipTab = data?.nodes?.filter(node => node?.data?.biz === 'default')?.[0]
        const vipTab = data?.nodes?.filter(node => node?.nodes?.length > 0)?.[0]

        if (vipTab) {
            const tab = vipTab.data;
            tab.checked = true;
            // 设置是否是SVIP收银台
            const isSVIP = tab?.biz === COMMON_CONFIG.SVIP_CONFIG.SVIP_BIZ || tab?.cashierSkin === COMMON_CONFIG.SVIP_CONFIG.SVIP_CASHIER_SKIN;
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.IS_SVIP_CASHIER, isSVIP);
            // 更新 mTabs和mModules
            mTabs = [tab];
            mModules = vipTab.nodes?.filter(module => !!module) || [];

            // agreementToPayBtnData = null;
            CashierDataManager.delete(CASHIER_GLOBAL_STORE_KEYS.AGREEMENT_PAY_BTN_DATA);

            extend.pageSPM = tab.action?.report?.spmAB;
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.CNT_PAGE_SPM, tab.action?.report?.spmAB);

            extend.pageKey = tab.nodeKey;
            extend.params.biz = tab.biz;
            extend.params.subBiz = tab.subBiz;
            extend.params.abTestInfo = tab.action?.report?.trackInfo?.abTestInfo;

            const pageTitle = tab?.barTitle || tab?.title || '';
            CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.PAGE_TITLE, pageTitle);
        }
    }

    if (isNotEmptyArray(mModules)) {
        mModules.forEach(module => {
            if (isNotEmptyArray(module?.nodes)) {
                module.nodes.forEach(componentData => {
                    if (componentData) {
                        const dataSource = componentData.nodes;
                        const componentType = componentData.type.toString();

                        // 提前把是否登录状态准备好，保证在页面渲染前能够知道是否登录（无界面如果未登录，需要正常渲染）
                        // 老逻辑，是在UserInfo组件里面判断，并最终广播出去登录状态
                        if (isUserComponentType(componentType) && dataSource?.[0]?.data) {
                            const name = dataSource[0].data?.uname;
                            const phone = dataSource[0].data?.loginMobile;
                            let vipIcon = null;
                            if (isNotEmptyArray(dataSource[0].data?.attributes?.icon_on_light)) {
                                vipIcon = dataSource[0].data.attributes.icon_on_light;
                            }
                            extend.isLogined = !!(name || phone || vipIcon);
                            sendBroadcast('updateLoginStatus', { isLogined: extend.isLogined });
                        }
                    }
                });
            }
        });
    }
}

// endregion

// region 组件数据整理
/**
 * 格式化组件数据
 * @param {Array} modules - 接口下发的组件数据
 * @param {boolean} isShowHeader - 是否展示头部
 * @returns {Array} 格式化后的组件数据
 */
function createComponents(modules, isShowHeader = false) {
    const componentArray = [];
    modules?.forEach((module, index) => {
        const components = createComponent(module, isShowHeader, index);
        if (components) {
            componentArray.push(...components);
        }
    });
    return componentArray;
}

/**
 * 格式化组件数据
 * @param {Object} module - 接口下发的组件数据
 * @param {boolean} isShowHeader - 是否展示头部
 * @param {number} parentIndex - 本节点在父节点中的索引
 * @returns {Array} 格式化后的组件数据，用于渲染组件
 */
function createComponent(module, isShowHeader, parentIndex) {
    return module?.nodes?.map((componentData, index) => {
        try {
            if (componentData) {
                const componentInfo = {
                    typeName: componentData.typeName,
                    key: `${parentIndex}-${index}`,
                    isShowHeader,
                    isHalfCashier: isSimpleScreenCashier(),
                    isSvipCashier: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.IS_SVIP_CASHIER),  // 是否是SVIP收银台
                    doAction,
                    dataSource: componentData.nodes,
                    action: componentData.nodes?.[0]?.data?.action
                };
                const data = componentData.nodes?.[0]?.data || {};
                componentData.type = componentData.type.toString();
                if(isTopBannerComponentType(componentData.type)){  // 是否是场景化顶部banner组件
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.TOP_BANNER_DATA, componentData.nodes?.[0]?.data?.model);
                    return null;
                } else if (isUserComponentType(componentData.type)) {
                    const height = getUserComponentHeight(isShowHeader);
                    const type = getUserInfoType(componentData.type);
                    const containerHeight = height + 72;

                    // 用户信息
                    const userName = data?.uname;
                    const userIcon = data?.userIcon;
                    const vipIcon = data?.attributes?.icon_on_light;
                    const loginMobile = data?.loginMobile;

                    let expTime = data?.exptime;
                    let tinyName = null;
                    let desc;

                    if (expTime) {
                        tinyName = data?.memberName;
                    }
                    if (type === 'upgrade' && data?.attributes) {
                        desc = data.attributes?.upgrade_desc;
                        expTime = data.attributes?.upgrade_exptime;
                        if (exptime) {
                            tinyName = data.attributes?.upgrade_vip_name;
                        }
                    } else {
                        desc = data?.desc;
                    }

                    // 获取用户绑定的手机号
                    if (componentData && componentData.nodes && isNotEmptyArray(componentData.nodes) && componentData.nodes[0] && componentData.nodes[0].data) { // 与今泉约定在此获取用户绑定的手机号
                        setBindPhoneNumberData(componentData.nodes[0].data);
                    }


                    return Object.assign(componentInfo, {
                        tag: 'user_info',
                        height,
                        containerHeight,
                        type,
                        userName,
                        userIcon,
                        vipIcon,
                        loginMobile,
                        expTime,
                        tinyName,
                        desc,
                    });
                } else if (isMainProductComponentType(componentData.type)) {
                    const productStyleType = getProductStyle(componentData.type);
                    extend.params.attributes.paramtransfer = componentData?.data?.other?.attributes?.paramtransfer || '';
                    extend.params.attributes.crm_touch_point_code = componentData?.data?.other?.attributes?.crm_touch_point_code || '';
                    extend.params.attributes.traceId = componentData?.data?.other?.traceId || '';
                    extend.traceId = componentData?.data?.other?.traceId || '';
                    // 设置用于埋点&预警的traceId
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.TRACE_ID, componentData?.data?.other?.traceId || '');
                    // 获取主推商品
                    const products = componentData?.nodes?.[0]?.nodes;
                    let mainProduct = null;
                    products?.forEach((product) => {
                        if (mainProduct === null && product?.data?.line) {
                            mainProduct = parseMainGoodsProduct(product.data.line);
                        }
                    });
                    setMainGoodsProduct(mainProduct);

                    return Object.assign(componentInfo, {
                        tag: 'main_product',
                        type: productStyleType,
                        cashierType: componentData.type,
                        data: componentData.data
                    });
                } else if (isPayChannelComponentType(componentData.type)) {
                    // 填充所有的支付渠道 供继续支付时获取
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.ALL_PAY_CHANNELS, componentData.nodes);
                    return Object.assign(componentInfo, {
                        tag: 'pay_channel',
                    });
                } else if (isPayButtonComponentType(componentData.type)) {
                    // 设置trackInfo用于埋点
                    setReportABTrackInfo(componentData.nodes);
                    return Object.assign(componentInfo, {
                        tag: 'pay_button',
                    });
                } else if (isVipBenefitComponentType(componentData.type)) {
                    return Object.assign(componentInfo, {
                        title: componentData.data?.desc || '',
                        tag: 'vip_benefit',
                    });
                } else if (isBannerComponent(componentData.type)) {
                    return Object.assign(componentInfo, {
                        tag: 'banner',
                    });
                } else if (isPayAgreementComponentType(componentData.type)) {
                    // 合规附加到按钮下边的协议展示数据
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.AGREEMENT_PAY_BTN_DATA, componentData);
                } else if (isUnPayedOrderComponentType(componentData.type)) {
                    return Object.assign(componentInfo, {
                        tag: 'invisible_components',
                    });
                } else {
                    console.log('未支持的组件类型', componentData.type, componentData);
                }
            }
        } catch (e) {
            console.error('createComponent error', e);
            alarmEventWithPreDesc('7019', `组件创建过程中出现error`, e);
        }

    })?.filter(item => !!item);
}

/**
 * 获取主推商品
 * @param {Object} orderLine - 订单行
 * @returns {Object} 主推商品
 */
function parseMainGoodsProduct(orderLine) {
    if (orderLine && orderLine.product && orderLine.attributes && parseToBool(orderLine.attributes.crm_main_goods)) {
        //
        const mainGoodsProduct = {
            productId: orderLine.productId,
            skuId: orderLine.skuId,
        };
        if (isNotEmptyArray(orderLine.product.promotions)) {
            const promotions = [];
            orderLine.product.promotions.forEach((item) => {
                if (isNotEmptyArray(item.receivings)) {
                    item.receivings.forEach((activity) => {
                        if (activity?.activityId) {
                            promotions.push({ activityId: activity.activityId });
                        }
                    });
                }
            });
            if (isNotEmptyArray(promotions)) {
                mainGoodsProduct.promotions = promotions;
            }
        }
        return mainGoodsProduct;
    }
    return null;
}

// endregion
// region export
export {
    setMainGoodsProduct,
    getMainGoodsProduct,
    setReportABTrackInfo,
    getReportABTrackInfo,
    getExtend,
    getTabs,
    getModules,
    initCashier,
    parseData,
    createComponents,
};
// endregion
