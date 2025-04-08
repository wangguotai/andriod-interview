/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/


// components/cashier/banner/banner.js
import { isNotEmptyArray } from "../../utils/nativeCashier/data/index";
import { sendExp } from "../../utils/nativeCashier/track/TrackHelper";

function getScale(width, height) {
    if (width > 0 && height > 0) {
        return height / width;
    } else {
        return 148 / 750;
    }
}

function parseBannerInfo(dataSource) {
    if (isNotEmptyArray(dataSource)) {
        const data = dataSource[0].data || {};
        const {action} = data || {};
        const uri = data.img || '';
        let scale;
        if (data.extraExtend && data.extraExtend.width && data.extraExtend.height) {
            scale = getScale(data.extraExtend.width, data.extraExtend.height);
        } else {
            getScale(0, 0);
        }
        return {uri, action, scale};
    }

    return {};
}

// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const bannerComponent = {

    /**
     * 组件的属性列表
     */
    properties: {
        componentInfo: {
            type: Object,
            value: {},
            observer: function (newVal) {
                if (newVal) {
                    const {dataSource, doAction} = newVal;
                    const {uri, action, scale} = parseBannerInfo(dataSource);
                    const width = 690;
                    const style = `width: ${width}rpx; height: ${width * scale}rpx;`;
                    sendExp({}, action);
                    this.setData({
                        uri,
                        style,
                        doAction,
                        action
                    });
                }
            }
        },
    },

    /**
     * 组件的初始数据
     */
    data: {},

    /**
     * 组件的方法列表
     */
    methods: {
    }
};

