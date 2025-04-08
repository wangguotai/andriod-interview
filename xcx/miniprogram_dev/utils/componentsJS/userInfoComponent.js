/**************************************************
 * @description:
 *  - created time: 2024/11/13
 *  -
 **************************************************/
import { sendBroadcast } from "../nativeCashier/BroadcastReceiver";
import { isSimpleScreenCashier } from "../nativeCashier/biz/index";
import { compareVersion, getCachedSystemInfo, rpxToPx } from "../nativeCashier/common/index";
import CashierDataManager from "../nativeCashier/store/index";
import { CASHIER_GLOBAL_STORE_KEYS } from "../nativeCashier/store/constants";
import { sendClick, sendExp } from "../nativeCashier/track/TrackHelper";
import { getPlatformContext, isBaiduAndIqiyiMiniProgram, isByteDanceMiniProgram, isKuaishouMiniProgram, isWxMiniProgram } from "../context/contextHelper";

const app = getApp();
const ctx = getPlatformContext();
// 传入的组件参数对象，适配快手无法在非组件JS文件内执行Component调用
export const userInfoComponent = {

    // behaviors: [FullScreenBehavior],
    /**
     * 组件的属性列表
     */
    properties: {
        isLogined: {
            type: Boolean,
            value: app?.cashier?.isLogined,
        },
        componentInfo: {
            type: Object,
            value: {},
            observer: function (newVal) {
                if (newVal) {
                    this.setData({
                        isHalfCashier: CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.IS_HALF_SCREEN_CASHIER),
                        userName: newVal.userName,
                        avatarUrl: newVal.userIcon ? newVal.userIcon : 'https://gw.alicdn.com/imgextra/i1/O1CN01ny6qjr1HPXjz03Uus_!!6000000000750-2-tps-114-117.png',
                        vipIcon: newVal.vipIcon,
                        loginMobile: newVal.loginMobile,
                        desc: newVal.desc,
                        doAction: newVal.doAction,
                        type: newVal.type,
                        tinyName: newVal.tinyName,
                        expTime: newVal.expTime,
                        action: newVal.action,
                        isSvipCashier: newVal.isSvipCashier,
                    });
                    // 执行曝光逻辑
                    if (newVal.action) {
                        sendExp({}, newVal.action);
                    }
                }
            }
        },
    },

    /**
     * 组件的初始数据
     */
    data: {
        isHalfCashier: true,
        supportTopBar: true,
        isShowTopBanner: false,
    },

    lifetimes: {
        attached() {
            if (!isSimpleScreenCashier()) {
                const pageTitle = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.PAGE_TITLE) || '购买会员';
                const paddingBottom = rpxToPx(40); // 提取 paddingBottom 常量
                let fullScreenTopBarStyle;
                let navBarHeight;

                const setNavigationBar = (top, height) => {
                    fullScreenTopBarStyle = `padding-top: ${top}px; height: ${height}px; padding-bottom: ${paddingBottom}px;`;
                    navBarHeight = top + height + paddingBottom;
                    CashierDataManager.set(CASHIER_GLOBAL_STORE_KEYS.NAV_BAR_HEIGHT, navBarHeight);
                    this.setData({ pageTitle, fullScreenTopBarStyle });
                };

                if (isWxMiniProgram()) {
                    const systemInfo = getCachedSystemInfo();
                    if (compareVersion(systemInfo.version, '7.0.0') === -1) {
                        this.setData({ pageTitle, supportTopBar: false });
                    } else {
                        if (compareVersion(systemInfo.SDKVersion, '2.15.0') !== -1) {
                            const rect = ctx.getMenuButtonBoundingClientRect();
                            setNavigationBar(rect.top, rect.height);
                        } else {
                            const paddingTop = systemInfo?.statusBarHeight || 40; // 使用可选链操作符
                            setNavigationBar(paddingTop, 44);
                        }
                    }
                } else {
                    const rect = ctx.getMenuButtonBoundingClientRect();
                    setNavigationBar(rect.top, rect.height);
                }
            } else {
                // 微信&快手场景化支持banner图
                if (isWxMiniProgram() || isKuaishouMiniProgram()) {
                    const topBannerData = CashierDataManager.get(CASHIER_GLOBAL_STORE_KEYS.TOP_BANNER_DATA);
                    if (topBannerData) {
                        const topBannerImgHeightScale = parseInt(topBannerData.titlePicHeight) || 0;
                        const topBannerImgWidthScale = parseInt(topBannerData.titlePicWidth) || 0;
                        const widthDivHeightScale = (topBannerImgHeightScale > 0 && topBannerImgWidthScale > 0) ? (topBannerImgWidthScale / topBannerImgHeightScale) : 6.5;
                        this.setData({
                            isShowTopBanner: true,
                            topBannerInfo: {
                                contentGuideDesc: topBannerData.contentGuideDesc || '',
                                titlePic: topBannerData.titlePic || '',
                                titlePicStyle: `width: ${42 * widthDivHeightScale}rpx; height: 42rpx;`,
                                titleText: topBannerData.title || '',
                            },
                        });
                    }
                }
            }

        }
    },

    /**
     * 组件的方法列表
     */
    methods: {
        getPhoneNumberHandler(data) {
            let login = app.cashier.loginSDK;
            if (isBaiduAndIqiyiMiniProgram()) {
                login = app.login;
            } else if (isKuaishouMiniProgram()) {
                login = app._pspSdk;
            }
            login.needLogin(data).then((data) => {
                sendBroadcast('refreshPageAfterLogin', {
                    isLogined: true,
                });
                this.setData({
                    isLogined: true,
                });
                app.cashier.isLogined = true;
            }).catch((e) => {
                console.log(e)
            });

            sendClick({}, this.data.action);

        },
        closeCashierModal() {
            this.triggerEvent('closeCashier');
        },

        onLogOut() {
        },

        handleGoBack() {
            ctx.navigateBack({
                delta: 1,
            });
        }
    }
};

