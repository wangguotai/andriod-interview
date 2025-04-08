import customPage from '../../status-manager/src/event-data-center/Container'

customPage({
    onLoad(){
        console.log('onLoad 执行=====>')
    },
    /* 跳转到页面 A */
    routerGoPageA(){
        wx.navigateTo({
            url:'/pages/pageA/index'
        })
    },
},'page-B')