const app = getApp();

const openFullCashier = (cashierInfo) => {
    let params = '';
    try {
        params = encodeURIComponent(JSON.stringify(cashierInfo));
    } catch (e) {
        console.error("全屏收银台解析失败", e);
    }
    wx.navigateTo({
        url: `${app?.globalData?.isInDevelopment ? '' : '@ali-npm-test/cashier-sdk-wx'}/pages/fullCashier/fullCashier?cashierInfo=${params}`,
    });
}

Page({
    data: {
        cashierEntryHalfBtn: '打开半屏收银台',
        cashierEntryFullBtn: '打开全屏收银台',
        cashierInfo: {},
        isShowCashier: false,
    },
    onLoad: function (options) {

    },
    onHalfCashierEntryBtnTap(e) {
        this.setData({
            isShowCashier: true,
            cashierInfo: {
                // cashierUrl: "weixin://dl/business?sceneType=fullScreenCashier4&h5params=%7B\"pageKey\"%3A\"COMMON24_YOUKUSVIP\"%2C\"biz\"%3A\"cibn\"%2C\"subBiz\"%3A\"mix\"%7D&en_spm=youku.visp.tools.spm_569217&en_scm=youku.visp.tools.scm&tabStyle=1",
                tags: "ykminiapp,wechat@ykminiapp,YOUKU_WECHAT_MINI_APP,wechatanchor,playPayment",
                // cashierUrl: "http://test.youku.com:3333/?sceneType=fullScreenCashier4&h5params={%22pageKey%22:%22COMMON24_YOUKUSVIP%22,%22biz%22:%22cibn%22,%22subBiz%22:%22mix%22,%20%22products%22:[{%22productId%22:%22128%22,%22promotions%22:[{%22activityId%22:%2253830%22}],%22skuId%22:%2212808098%22}]}&en_spm=youku.visp.tools.spm_569217&en_scm=youku.visp.tools.scm&tabStyle=1"
                // cashierUrl: "https://activity.youku.com/app/visp/cashier_4/index?h5params=%7B%22pageKey%22%3A%22COMMON24_YOUKUSVIP%22%2C%22biz%22%3A%22cibn%22%2C%22subBiz%22%3A%22mix%22%2C%22scenario%22%3A%22*%22%2C%22products%22%3A%5B%7B%22productId%22%3A%22128%22%2C%22skuId%22%3A%221%22%7D%5D%2C%22attributes%22%3A%22%7B%5C%22ctid%5C%22%3A%5C%2221361cc017405348596828103efd2d%5C%22%2C%5C%22r2%5C%22%3A%5C%221%5C%22%7D%22%7D&sceneType=fullScreenCashier4&en_spm=a2os05.342&tabStyle=1&hideNavigatorBar=true"
                // cashierUrl: "http://test.youku.com:3333/?h5params=%7B%22pageKey%22%3A%22COMMON24_YOUKUSVIP%22%2C%22biz%22%3A%22cibn%22%2C%22subBiz%22%3A%22mix%22%2C%22scenario%22%3A%22*%22%2C%22attributes%22%3A%22%7B%5C%22ctid%5C%22%3A%5C%22212c620317405356918206217e3fd0%5C%22%2C%5C%22r2%5C%22%3A%5C%221%5C%22%7D%22%7D&sceneType=fullScreenCashier4&en_spm=a2os05.342&tabStyle=1&hideNavigatorBar=true"
                cashierUrl: "https://activity.youku.com/app/visp/cashier_4/index?h5params=%7B%22pageKey%22%3A%22COMMON24_YOUKUSVIP%22%2C%22biz%22%3A%22cibn%22%2C%22subBiz%22%3A%22mix%22%2C%22scenario%22%3A%22*%22%2C%22attributes%22%3A%22%7B%5C%22ctid%5C%22%3A%5C%222105ac9517405386763156703e60e0%5C%22%2C%5C%22r2%5C%22%3A%5C%221%5C%22%7D%22%7D&sceneType=fullScreenCashier4&en_spm=a2os05.342&tabStyle=1&hideNavigatorBar=true"
            }
        });
    },

    onFullCashierEntryBtnTap(e){
        openFullCashier({
            tags: "ykminiapp,wechat@ykminiapp,YOUKU_WECHAT_MINI_APP,indexUserCenter",
            cashierUrl: "weixin://dl/business?h5params=%7B%22pageKey%22%3A%22MINIAPPSTANDARD_YOUKU%22%2C%22activityCode%22%3A%22xcss3y15%22%2C%22attributes%22%3A%22%7B%5C%22no_surprise%5C%22%3A%5C%22true%5C%22%2C%5C%22crm_params%5C%22%3A%5C%22%7B%5C%5C%5C%22cvid%5C%5C%5C%22%3A%5C%5C%5C%22%5C%5C%5C%22%2C%5C%5C%5C%22cgid%5C%5C%5C%22%3A%5C%5C%5C%22%5C%5C%5C%22%2C%5C%5C%5C%22touch_point_code%5C%5C%5C%22%3A%5C%5C%5C%22PlayPageGoldCard%5C%5C%5C%22%2C%5C%5C%5C%22tab%5C%5C%5C%22%3A%5C%5C%5C%221%5C%5C%5C%22%2C%5C%5C%5C%22csid%5C%5C%5C%22%3A%5C%5C%5C%22%5C%5C%5C%22%7D%5C%22%2C%5C%22ctid%5C%22%3A%5C%22213ddbe117338160382177055e900f%5C%22%2C%5C%22r2%5C%22%3A%5C%221%5C%22%7D%22%7D&sceneType=simpleScreenCashier&tabStyle=1&hideNavigatorBar=true&crmCode=PlayPageGoldCard&en_scm=20140732.0.0.crm_20140732-manual-999_1_0_0-108720_101178_0_1_19855_1925_1733816038237_28405bffe3a34452a289960a9939a8ecZ0_0_0-syt_HALFSTANDARDRENDER&en_spm=&from=PlayPageGoldCard",
        })
    },
    // 关闭半屏收银台
    closeHalfCashier(e) {
        this.setData({
            isShowCashier: false,
        });
        // {
        //   isLoginStatusChanged: true / false,
        //   isPaySuccess: true / false,
        //   extaParams: {}
        // }
        const { isLoginStatusChanged, isPaySuccess } = e.detail;
        // (isLoginStatusChanged || isPaySuccess) && this.refrashData();
    },
});
