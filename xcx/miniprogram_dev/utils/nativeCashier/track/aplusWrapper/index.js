/**************************************************
 * @description:
 * 1. 针对Aplus埋点SDK进行一层封装，使其适配收银台

 *  - created time: 2024/7/23
 *  - v1: 参考优酷小程序埋点sdk的封装 & 主客收银台黄金令牌的封装
 *  - v2: 调整埋点上报取数 muuid，ver 来自端内全局变量；
 *          通用字段： appid、client、appkey、muuid、version、cashierVersion
 **************************************************/
import { aplus_universal as aplus } from '../../sdk/aplus/aplus_universal';
import { isEmptyStr, isNotEmptyArray } from "../../data/index";
import CashierDataManager from "../../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../../store/constants";
import { getCurrentPage } from "../../router/RouterHelper";
import { envType } from "../../../context/contextHelper";
import { ALARM_CONFIG, APLUS_CONFIG, APP_INFO_CONFIG, MTOP_CONFIG } from '../../../config/cashierConfig';
import { getCachedSystemInfo } from "../../common/index";

class CommonTrack {
    static trackInstance = null;
    trackConfig = {};
    /**
     * @description: 为后续扩展其他端小程序留有通用的入口
     */
    constructor() {
        const systemInfo = getCachedSystemInfo();
        const app = getApp();
        // 埋点全局配置
        this.trackConfig = {
            base: {
                appid: APP_INFO_CONFIG.APP_ID_LIST[envType],
                // 黄金令箭上报 APPKEY为具体标志宿主环境的appkey，非mtop数字
                client: APLUS_CONFIG.APP_KEY_LIST[envType]?.[systemInfo.appName]  // 抖音取appName
                    || APLUS_CONFIG.APP_KEY_LIST[envType]?.[systemInfo.host]  // 快手、百度取host
                    || APLUS_CONFIG.APP_KEY_LIST[envType]?.default           // 微信取default， 字节系其他APP、百度系其他宿主APP取default
                    || APLUS_CONFIG.APP_KEY_LIST.DEFAULT_APP_KEY,           // 默认的兜底
                appkey: MTOP_CONFIG.APP_KEY_LIST[envType],
                muuid: app?.globalData?.muuid || '',
                version: app?.globalData?.ver || '',
                cashierVersion: ALARM_CONFIG.VERSION[envType],
            },
            logKey: {
                exp: APLUS_CONFIG.APLUS_LOG_KEY[envType] || APLUS_CONFIG.APLUS_LOG_KEY[APLUS_CONFIG.APLUS_DEFAULT_KEY],  // 曝光key和小程序保持一致
                clk: APLUS_CONFIG.APLUS_LOG_KEY[envType] || APLUS_CONFIG.APLUS_LOG_KEY[APLUS_CONFIG.APLUS_DEFAULT_KEY],
            },
            metaInfo: {
                "aplus-rhost-multi": [{
                    "aplus-rhost-v": APLUS_CONFIG.APLUS_HOST[envType] || APLUS_CONFIG.APLUS_HOST[APLUS_CONFIG.APLUS_DEFAULT_KEY],
                    "aplus-rhost-g": APLUS_CONFIG.APLUS_HOST[envType] || APLUS_CONFIG.APLUS_HOST[APLUS_CONFIG.APLUS_DEFAULT_KEY],
                }],
            },
            systemInfo,
            cntSpmAB: {
                spmA: '0',
                spmB: '0',
                isUpdated: false,
            },

        }
    }

    /**
     * @description: 获取实例
     */
    static getInstance() {
        if (CommonTrack.trackInstance == null) {
            CommonTrack.trackInstance = new CommonTrack();
            CommonTrack.trackInstance.init();
        }
        return CommonTrack.trackInstance;
    }

    init() {

        const rHosts = this.trackConfig.metaInfo['aplus-rhost-multi'][0];
        aplus.setMetaInfo('aplus-rhost-v', rHosts["aplus-rhost-v"]);
        aplus.setMetaInfo('aplus-rhost-g', rHosts["aplus-rhost-g"]);

    }

    /**
     * @description: 更新页面SPM
     */
    updatePageSPM() {
        const cntSpmAB = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CNT_PAGE_SPM)?.split('.');
        if (cntSpmAB?.length > 1) {
            this.trackConfig.cntSpmAB = {
                spmA: cntSpmAB[0],
                spmB: cntSpmAB[1],
                isUpdated: true,
            }
        }
    }

    updatePageSPMInner(spmA, spmB) {
        if (!isEmptyStr(spmA) && !isEmptyStr(spmB)) {
            this.trackConfig.cntSpmAB = {
                spmA,
                spmB,
                isUpdated: true,
            }
        }
    }


    multiLog(sendLog) {
        if (isNotEmptyArray(this.trackConfig.metaInfo['aplus-rhost-multi'])) {
            this.trackConfig.metaInfo['aplus-rhost-multi'].forEach((item) => {
                return new Promise(() => {
                    const rhostV = item['aplus-rhost-v'];
                    const rhostG = item['aplus-rhost-g'];
                    sendLog(rhostV, rhostG);
                });
            })
        }
    }

    /**
     * @description: 发送PV埋点，为第一个触发的事件
     * @param {*} spmA
     * @param {*} spmB
     * @param {*} pvParams
     */
    sendPV(spmA, spmB, pvParams = {}) {
        try {
            if (isEmptyStr(pvParams.trace_id)) {
                pvParams.trace_id = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.TRACE_ID) || '';
            }
            const sendLog = (rhostV, rhostG) => {
                const currentPage = getCurrentPage();
                const preSpmUrl = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.PRE_PAGE_SPM_AB) || '0.0.0.0';
                if (this.trackConfig.cntSpmAB.isUpdated) {
                    aplus.setPageSPM(this.trackConfig.cntSpmAB.spmA, this.trackConfig.cntSpmAB.spmB);
                } else {
                    aplus.setPageSPM(spmA, spmB);
                    this.updatePageSPMInner(spmA, spmB);
                }
                Object.assign(pvParams, {
                    url: currentPage.route,
                    ...this.trackConfig.base
                });
                aplus.enter({
                    'pageName': 'cashier',
                    'pageUrl': CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CASHIER_LINK_URL) || encodeURIComponent('https://t.youku.com/app/visp/cashier/index'),
                    'spmUrl': preSpmUrl,
                }, pvParams, rhostV);
                // console.log(`cashier Track PageView {pageName: 'cashier', pageUrl:${currentPage.route}, spmUrl: ${preSpmUrl}, pvParams: ${JSON.stringify(pvParams)}, rhostV: ${rhostV} }`);
            }
            this.multiLog(sendLog.bind(this));
        } catch (e) {
            console.error('sendPV error', e);
        }
    }

    /**
     * @description: 发送曝光埋点
     * @param {Object} params
     * @param {Object} action
     */
    sendExp(params, action = {}) {
        const aplusParams = Object.assign(params || {}, this.getActionParams(action));
        if (aplusParams && typeof aplusParams.track_info === 'object') {
            aplusParams.track_info = JSON.stringify(aplusParams.track_info);
        }
        this.commonRecord('EXP', aplusParams, this.trackConfig.logKey.exp);
    }

    /**
     * @description: 发送点击埋点
     * @param params
     * @param {*} action
     */
    sendClick(params = {}, action) {

        const aplusParams = Object.assign(params || {}, this.getActionParams(action));
        if (aplusParams && typeof aplusParams.track_info === 'object') {
            aplusParams.track_info = JSON.stringify(aplusParams.track_info);
        }
        this.commonRecord('CLK', aplusParams, this.trackConfig.logKey.clk);
    }

    /**
     * @description: 发送黄金令箭
     * @param {*} gmKey EXP or CLK
     * @param {*} expData
     * @param {*} logKey
     */
    commonRecord(gmKey, expData, logKey) {
        const systemInfo = this.trackConfig?.systemInfo;
        if (systemInfo) {
            Object.assign(expData, {
                device: systemInfo.model,
                brand: systemInfo.brand,
                os: systemInfo.platform,
            }, {
                ...this.trackConfig.base,
                pageurl: getCurrentPage()?.route,
                url: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.CASHIER_LINK_URL) || encodeURIComponent('https://t.youku.com/app/visp/cashier/index'),
            });
        }
        const sendLog = (rhostV, rhostG) => {
            const goKeyParams = objToQueryString(expData);
            // console.log(`cashier Track commonRecord {logKey:${logKey}, gmKey:${gmKey}, rhostG: ${rhostG}, method: POST, goKeyParams: ${goKeyParams} }`);
            aplus.setPageSPM(this.trackConfig.cntSpmAB.spmA, this.trackConfig.cntSpmAB.spmB);
            aplus.record(logKey, gmKey, goKeyParams, 'POST', rhostG);
        };
        this.multiLog(sendLog.bind(this));
    }

    /**
     * 从接口action字段中拼接曝光参数
     * @param action
     */
    getActionParams(action) {
        const params = {};
        if (action && action.report) {
            params.spm = action.report.spmAB + '.' + action.report.spmC + '.' + action.report.spmD;
            params.scm = action.report.scmAB + '.' + action.report.scmC + '.' + action.report.scmD;
            if (action.report.trackInfo) {
                params.track_info = JSON.stringify(action.report.trackInfo);
            }
            const trace_id = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.TRACE_ID);
            if (trace_id) {
                params.trace_id = trace_id;
            }
        }
        return params;
    }

}

function objToQueryString(obj) {
    return Object.keys(obj)
        .map(k => `${k}=${obj[k]}`)
        .join('&');
}

export const getTrackInstance = () => {
    return CommonTrack.getInstance();
}
