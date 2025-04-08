
Page({

	/**
	 * 页面的初始数据
	 */
	data: {
		cashierInfo: null,
		isShowCashier: true,
	},

	/**
	 * 生命周期函数--监听页面加载
	 */
	onLoad: function (options) {
		let cashierInfo;
		try {
			const obj = JSON.parse(decodeURIComponent(options.cashierInfo));
			cashierInfo = {
				...obj,
                sceneType: 'fullScreenCashier'
            };
		} catch (e){
			cashierInfo = {
				sceneType: 'fullScreenCashier'
			}
			console.error('全屏收银台解析失败，使用端内默认设置', e);
		}
		this.setData({
			cashierInfo
		});
	},

	/**
	 * 生命周期函数--监听页面初次渲染完成
	 */
	onReady: function () {

	},

	/**
	 * 生命周期函数--监听页面显示
	 */
	onShow: function () {

	},

	/**
	 * 生命周期函数--监听页面隐藏
	 */
	onHide: function () {

	},

	/**
	 * 生命周期函数--监听页面卸载
	 */
	onUnload: function () {

	},

	/**
	 * 页面相关事件处理函数--监听用户下拉动作
	 */
	onPullDownRefresh: function () {

	},

	/**
	 * 页面上拉触底事件的处理函数
	 */
	onReachBottom: function () {

	},

	/**
	 * 用户点击右上角分享
	 */
	onShareAppMessage: function () {

	}
})
