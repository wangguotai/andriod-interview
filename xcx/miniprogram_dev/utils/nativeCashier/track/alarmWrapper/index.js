/**************************************************
 * @description: 预警埋点 小程序sdk
 *   - 预警埋点，用于监控异常
 *   - 仿照小程序埋点sdk设计，封装上报类，单例模式对外暴露使用
 *  - created time: 2024/7/25
 *
 *
 **************************************************/
import CashierDataManager from "../../store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../../store/constants";
import { getPageKey } from "../../biz/index";
import { getMTopInstance } from "../../request/MTopApi";
import { ALARM_CONFIG, APLUS_CONFIG } from "../../../config/cashierConfig";
import { envType} from "../../../context/contextHelper";
import { getCachedSystemInfo } from "../../common/index";



class AlarmSDK {
    constructor() {
        const systemInfo = getCachedSystemInfo();
        // 小程序环境标识
        this.sdkConfig = {
            apiName: ALARM_CONFIG.ALARM_API,
            extendParams: {
                v: ALARM_CONFIG.VERSION[envType],
                pk: getPageKey(),
                appName: APLUS_CONFIG.APP_KEY_LIST[envType]?.[systemInfo.appName]  // 抖音取appName
                    || APLUS_CONFIG.APP_KEY_LIST[envType]?.[systemInfo.host]  // 快手、百度取host
                    || APLUS_CONFIG.APP_KEY_LIST[envType]?.default           // 微信取default， 字节系其他APP、百度系其他宿主APP取default
                    || APLUS_CONFIG.APP_KEY_LIST.DEFAULT_APP_KEY,           // 默认的兜底
            },
            biz_type: ALARM_CONFIG.BIZ_TYPE_LIST[envType],
        }
    }
    static instance = null;

    static getInstance() {
        if (!AlarmSDK.instance) {
            AlarmSDK.instance = new AlarmSDK();
        }
        return AlarmSDK.instance;
    }

    /**
     * 预警埋点
     * @param {*} client_code
     * @param {*} client_msg 具体描述
     * @param {*} client_msg_obj 补充的jsonObject描述
     * @param {*} callback
     */
    alarmEventWithPreDesc(client_code, client_msg, client_msg_obj, callback) {
        try {
            this.alarmWithPreDesc(client_code, client_msg, client_msg_obj).then((result) => {
                callback && callback(result);
            }).catch((result) => {
                callback && callback(result);
            });
        } catch (e) {
            console.error('预警埋点上报异常', e);
        }

    }

    /**
     *  预警埋点
     */
    alarm(client_code, client_msg) {
        return this.alarmWithPreDesc(client_code, '', client_msg);
    }

    /**
     *  预警埋点
     * @param {*} client_code
     * @param {*} client_msg 具体描述
     * @param {*} client_msg_obj 补充的jsonObject描述
     * @returns
     */
    alarmWithPreDesc(client_code, client_msg = '', client_msg_obj) {
        const extend = this.sdkConfig.extendParams;
        const trace_id = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.TRACE_ID) || '';
        const bizType = this.sdkConfig.biz_type;
        const apiName = this.sdkConfig.apiName;
        return new Promise(function (resolve, reject) {
            /**
             * 接口请求成功
             * @param result
             */
            function successCallback(result) {
                resolve(result);
            }

            /**
             * 接口请求失败
             * @param result
             */
            function failureCallback(result) {
                reject(result);
            }

            const params = {
                client_msg: client_msg_obj,
                trace_id,
                extend,
            }

            const options = {
                biz_type: bizType,
                client_code,
                client_msg: client_msg + ' ' + JSON.stringify(params),
            }

            // 调用mtop请求上报
            const mtop = getMTopInstance();
            // console.log('预警埋点上报\n', options);
            // 避免不同小程序 数据量不一致导致 ALARM失败 统一改为POST请求
            mtop.requestXmiApi(apiName, options, successCallback, failureCallback, true);
        });
    }
}

export const getAlarmInstance = () => {
    return AlarmSDK.getInstance();
}
