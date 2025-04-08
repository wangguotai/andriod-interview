'use strict';
import Login from '@ali/mini-app-login';
import { envType } from "../../context/contextHelper";
import { APP_INFO_CONFIG, PASSPORT_CONFIG } from "../../config/cashierConfig";

class MiniAppLogin {
    static instance = null;

    static getInstance(config) {
        if (!MiniAppLogin.instance) {
            MiniAppLogin.instance = new MiniAppLogin(config);
        }
        return MiniAppLogin.instance;
    }

    constructor(config) {
        this._config = {
            ...config,
            appId: APP_INFO_CONFIG.APP_ID_LIST[envType], //小程序appid
            appName: PASSPORT_CONFIG.APP_NAME,
            appEntrance: PASSPORT_CONFIG.APP_ENTRANCE_LIST[envType || 'default'],
            gateway: PASSPORT_CONFIG.GATEWAY, //预发 'https://passportpre.youku.com'  线上: 'https://cnpassport.youku.com'
            pagePath: PASSPORT_CONFIG.PAGE_PATH,
        }
        this.havanaLogin = new Login(this._config);
    }

    getAuthCode(params) {
        return this.havanaLogin.getAuthCode(params);
    }

    webViewSync(params) {
        return this.havanaLogin.webViewSync(params);
    }

    getSession() {
        return this.havanaLogin.getSession();
    }

    /** 检查登录态
     *   isLogin {Boolean} 是否已登录
     */
    checkLogin() {
        return new Promise(async (resolve, reject) => {
            const tokenInfo = this.getSession();
            //tokenInfo为空对象时，说明退登成功，处于未登录态
            if (tokenInfo && tokenInfo.P_pck_rm && tokenInfo.P_sck) {
                resolve(true);
            } else if (JSON.stringify(tokenInfo) === "{}") {
                resolve(false);
            } else {
                reject(false);
            }
        });
    }

    /** 强制登录 用在一些必须登录的场景中
     * 如未登录则会自动唤起登录界面供用户操作；
     * 已登录则直接调用成功方法
     * 未绑定，唤起绑定弹窗
     * 未注册，输入手机号注册
     */
    needLogin(data = {}) {
        return new Promise((resolve, reject) => {
            this.havanaLogin.login({phoneDetail: data.detail || {}, h5Login: true}).then(data =>
                resolve(data)
            ).catch(e => {
                reject(e);
            });
        });
    }

    /**
     * 获取优酷登录凭证
     * @return {Object} result
     *  result.ptoken {String} 如未登录返回空字符串
     *  result.stoken {String} 如未登录返回空字符串
     */
    getToken() {
        return new Promise((resolve, reject) => {
            this.getSession(session => {
                if (Object.keys(session).length === 0) {
                    resolve(session);
                } else {
                    reject({});
                }
            });
        });
    }
}

/**
 * 获取passport实例
 * @param {*} config
 * @return {MiniAppLogin} passport封装模块的实例
 */
export const getPassportInstance = (config = null) => {
    return MiniAppLogin.getInstance(config);
}


