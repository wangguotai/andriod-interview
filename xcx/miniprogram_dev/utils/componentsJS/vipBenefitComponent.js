/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/

import { alarmEvent, sendClick, sendExp } from "../nativeCashier/track/TrackHelper";
import { doAction } from "../nativeCashier/eventHandler/index";
// import { isPre } from "../context/contextHelper";
// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const vipBenefitComponent = {

    /**
     * 组件的属性列表
     */
    properties: {
        componentInfo: {
            type: Object,
            value: {},
            observer: function (newVal) {
                if (newVal) {
                    const { dataSource, title } = newVal;

                    const mobileNode = dataSource?.find(item => item?.data?.extraExtend?.mark === 'benefit_img');
                    if (mobileNode) {
                        // 除数不能为0,计算后的默认值,h/w
                        let image_h_w_Scale = 1.069;

                        const extraExtend = mobileNode.data.extraExtend

                        const width = extraExtend.width || '';
                        const height = extraExtend.height || '';

                        if (parseFloat(height) > 0 && parseFloat(width) > 0) {
                            image_h_w_Scale = parseFloat(height) / parseFloat(width);
                        }

                        this.setData({
                            imgStyles: `padding-left:30rpx; padding-right:30rpx; width: 690rpx; height: ${690 * image_h_w_Scale}rpx;`,
                            imageUrl: mobileNode?.data?.img || '',
                            action: mobileNode?.data?.action || {},
                            jumpUrl: mobileNode?.data?.action?.extra?.value || '',
                            dataSource,
                            title,
                        });
                        // 曝光
                        sendExp({}, this.data.action);

                    }
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
        handleVipBenefitClick(e) {
            let { url } = e.currentTarget.dataset;
            try {
                let [baseUrl, query] = url.split('?');
                // if(isPre){
                //     baseUrl = 'https://h5pre.vip.youku.com/privilege_list';
                // }
                // 小程序中隐藏返回按钮，隐藏支付按钮
                ['hideheader', 'hidepaybutton'].forEach(key => {
                    query += `&${key}=1`;
                });
                url = baseUrl + '?' + query;
            } catch (e) {
                console.error('会员特权页URL解析异常', url, e);
                alarmEvent('7006', `会员特权页URL解析异常: ${url}，error: ${e?.message}`, null);
            }
            doAction({
                type: 'JUMP_TO_URL',
                value: url,
                from: 'vipBenefit-handleVipBenefitClick',
            });
            sendClick({}, this.data.action);
            alarmEvent('7020', `会员特权页点击`, null);
        }
    }
}
