/**************************************************
 * @description: 处理收银台的事件响应
 *  - created time: 2024/6/26
 *  -
 **************************************************/

import { getPlatformContext, isByteDanceMiniProgram } from "../../context/contextHelper";
import { isSimpleScreenCashier } from "../biz/index";

/**
 * 公共点击事件处理
 * @param {Object} action {type: 行动类型, value: 跳转链接，from: 来源模块}
 */
const doAction = (action) => {
    if (action) {
        const { type, value, from } = action;
        switch (type) {
            case 'JUMP_TO_URL':
                jumpToUrl(value, from);
                break;
            default:
                console.error('doAction 未知的类型 type, value', type, value);
                break;
        }
    }
};

/**
 * 打开小程序的webView，跳转到指定url
 * @param {Object} outUrl
 * @param from
 */
function jumpToUrl(outUrl, from) {
    const ctx = getPlatformContext();
    try{
        let myUrl = outUrl;
        // 兼容爱奇艺小程序H5链路支付跳转
        if(outUrl?.needEncodeTwice){
            myUrl = encodeURIComponent(outUrl.innerUrl);
        }
        const encodeUrl = encodeURIComponent(myUrl);
        let webViewPagePath;
        // 字节小程序从 全屏收银台 npm中跳转至外部页面需要使用 usr://{pagePath}协议
        if(isByteDanceMiniProgram() && !isSimpleScreenCashier()) {
            webViewPagePath = `usr://pages/webview/index?url=${encodeUrl}`;
        } else {
            webViewPagePath = `/pages/webview/index?url=${encodeUrl}`;
        }

        ctx.navigateTo({
            url: webViewPagePath,
        });
    } catch (e) {
        ctx.showToast({
            title: '跳转失败',
            icon: 'error',
        });
        console.error('jumpToUrl 跳转失败, from', e, from);
    }
}

export {
    doAction,
}
