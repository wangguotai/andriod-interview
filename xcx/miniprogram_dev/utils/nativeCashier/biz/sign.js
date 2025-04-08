/**************************************************
 * @description:
 *  - created time: 2024/12/23
 *  - 请求签名模块
 **************************************************/

/**
 * @description: 创建微信openId的签名
 * @param {*} paramObj
 * @param {*} timeStamp
 */

import md5 from './md5';

export const createSignForWxOpenId = (paramObj, timeStamp) => {
    const newKey = Object.keys(paramObj).sort();
    const arr = [];
    for (var i = 0; i < newKey.length; i++) {
        arr.push(newKey[i] + '=' + paramObj[newKey[i]]);
    }
    const _params = arr.join('&');
    return md5(_params + timeStamp + 'YOUKUWEIXINXIAOCHENGXU');
}