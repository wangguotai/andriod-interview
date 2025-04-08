/**************************************************
 * Created by wzz on 2019/10/22 19:19 PM.
 **************************************************/
"use strict";
Page({
  data: {
    canIUse: wx.canIUse("web-view"),
    pageUrl: "",
  },
  onLoad(options) {
    let pageUrl = decodeURIComponent(options.url);

    this.setData({
      pageUrl: pageUrl,
      isShowBackBtn: !!options.isShowBackBtn,
      from: options.from
        ? decodeURIComponent(options.from)
        : "pages/newHome/index",
    });

    // 如果来源页面是N选1权益，需要通过写storage,来触发页面查询权益领取状态
    if (this.data.from === "pages/thirdpartyRights/index") {
      wx.setStorageSync("isFromThirdparty", "true");
    }

    if (options.name) {
      let name = decodeURIComponent(options.name);
      wx.setNavigationBarTitle({
        title: name,
      });
    }
  },
  onShow(options) {},
  handleTap() {
    this.data.isShowBackBtn &&
      wx.switchTab({
        url: `../../${this.data.from}`,
      });
  },
});
