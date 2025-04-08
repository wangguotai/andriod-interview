const app = getApp();

Page({
  data: {
    showWebView: false,
    url: ''
  },
  onLoad(query) {
    const { url } = query;
    if (url && url !== '') {
      this.setData({
        showWebView: true,
        url: /^http(s)?:\/\//.test(url) ? url : decodeURIComponent(url)
      });
    }
  },
  onUnload: function () {
    if (!this.getMessage) {
      app.cashier.loginSDK && app.casheir.loginSDK.webViewSync();
    }
  },
  onMessage(e) {
    this.getMessage = true;
    const data = e.detail.data[0] || e.detail.data;
    app.cashier.loginSDK && app.cashier.loginSDK.webViewSync(data);
  }
});
