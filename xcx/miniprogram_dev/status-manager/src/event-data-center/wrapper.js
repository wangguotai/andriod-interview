/**
 * 自定义小程序页面构造器
 * @param options  页面配置项 
 * @param pageID 小程序页面 ID 
 * @param originPage 其他自定义页面构造器或者原始的构造器
 */
function customPage(options, pageID, originPage = Page) {
    Object.assign(options.data,{ author:'alien' })
    
    return originPage({
        ...options,
        onLoad(...arg){
            /* 拦截生命周期 onload，做一些初始化动作  */
            //...
            /* 执行生命周期 onload */
            options.onLoad?.apply(this,arg)
        },
        /* 拦截 onUnload,onHide,onShow  */
        onUnload(){},
        onHide(){},
        onShow(){}
    })
}

/**
 * 自定义组件构造器
 * @param {*} options  组件配置项
 * @param {*} originComponent 其他自定义组件构造器或者原始组件构造器
 */
function customComponent(options,originComponent = Component){
    const { behaviors, methods } = options
    /* 加入自定义 behaviors  */
    Array.isArray(behaviors) && behaviors.push(customBehaviors)
    /* 加入自定义方法 */
    methods._customFunction = function(){}
    return originComponent(options)
}