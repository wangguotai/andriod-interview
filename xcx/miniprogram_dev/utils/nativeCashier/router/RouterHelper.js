/**************************************************
 * @description:
 *  - created time: 2024/7/24
 *  -
 **************************************************/
import { getPlatformContext, isBaiduAndIqiyiMiniProgram, isByteDanceMiniProgram, isKuaishouMiniProgram, isWxMiniProgram } from "../../context/contextHelper";
import { alarmEventWithPreDesc } from "../track/TrackHelper";

export const getCurrentPage = () => {
    try {
        const pages = getCurrentPages();
        return pages[pages.length - 1];
    } catch (e) {
        return null;
    }
}

export const getCurrentPageName = () => {
    const page = getCurrentPage();
    return page && page.route || '';
}


/**
 * 打开全屏收银台
 * @param cashierInfo
 */
export const openFullCashier = (cashierInfo) => {
    const ctx = getPlatformContext();
    let params = '';
    try {
        params = encodeURIComponent(JSON.stringify(cashierInfo));
    } catch (e) {
        console.error("全屏收银台解析失败", e);
    }
    const suffixUrl = `/pages/fullCashier/fullCashier?cashierInfo=${params}`;
    let url = '';
    if (isBaiduAndIqiyiMiniProgram()) {
        url = '/node_modules/@ali/yk-cashier-sdk-baidu/baidu_dist' + suffixUrl;
    } else if (isKuaishouMiniProgram()) {
        url = '/npm/cashier_sdk/kuaishou_dist' + suffixUrl;
    } else if (isByteDanceMiniProgram()) {
        url = `ext://@ali/yk-cashier-sdk-douyin/fullCashier?cashierInfo=${params}`;
    } else if (isWxMiniProgram()) {
        url = '/miniprogram_npm/@ali/yk-cashier-sdk-wx' + suffixUrl;
    }
    ctx.navigateTo({
        url,
        fail: (err) => {
            alarmEventWithPreDesc('7016', '[Error] ==== RouterHelper::openFullCashier跳转失败 ====', err);
            console.error('navigateTo fail', err);
        }
    });
}
