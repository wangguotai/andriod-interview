import mtop from '@ali/mtop_sdk_mini_cashier';
import { MTOP_CONFIG } from "../../config/cashierConfig";
import { envType } from "../../context/contextHelper";

class MTopApi {
    static instance = null;

    config = {
        prefix: 'acs',
        type: 'get',
        dateType: 'jsonp',
        appKey: MTOP_CONFIG.APP_KEY_LIST[envType], // 小程序 mTop APPKEY
    }

    /**
     * 获取实例
     * @param {*} envs
     * @return {MTopApi} 实例
     */
    static getInstance(envs) {
        if (MTopApi.instance == null) {
            MTopApi.instance = new MTopApi(envs);
        }
        return MTopApi.instance;
    }

    constructor(envs) {
        if (envs === 'pre') {
            this.config.prefix = 'pre-acs';
        }
    }


    setPrefix(prefix) {
        this.config.prefix = prefix;
        return this;
    }

    setRequestType(type) {
        this.config.type = type;
        return this;
    }

    setUserSessionInfo(sessionInfo) {
        mtop?.loginClient(sessionInfo); // 将登录token值透传给loginClient
    }

    /**
     * 请求mtop接口
     * @param {String} apiName
     * @param {Object} params
     * @param {Function} successCallback
     * @param {Function} failureCallback
     * @param {Boolean} usePost 是否使用post请求
     */
    requestMtopApi(apiName, params = {}, successCallback, failureCallback, usePost = false) {
        const options = {
            prefix: this.config.prefix,
            type: usePost ? 'post' : this.config.type,
            api: apiName,
            dateType: this.config.dateType,
            data: params,
            appKey: this.config.appKey,
        };

        // 调起网络请求
        mtop.request(options).then(res => {
            successCallback && successCallback(res);
        }).catch(ret => {
            failureCallback && failureCallback(ret);
        });
    }

    /**
     * XMI网络请求 迁移主客的命名
     * @param {String} apiName
     * @param {Object} params
     * @param {Function} successCallback
     * @param {Function} failureCallback
     * @param {Boolean} usePost 是否使用post请求
     * @description 请求mtop接口
     */
    requestXmiApi(apiName, params = {}, successCallback, failureCallback, usePost = false) {
        let xmiParams = {
            req: JSON.stringify(params),
        };
        this.requestMtopApi(apiName, xmiParams, successCallback, failureCallback, usePost);
    }
}

/**
 * 获取实例
 * @param {String} envs
 * @return {MTopApi} 实例
 */
export const getMTopInstance = (envs = 'release') => {
    return MTopApi.getInstance(envs);
}


