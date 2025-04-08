
class ObserverFactory {
    static instance = null;

    static getInstance() {
        if (!ObserverFactory.instance) {
            ObserverFactory.instance = new ObserverFactory();
        }
        return ObserverFactory.instance;
    }

    /**
     * 创建相交观察者
     * @param {Object} component 组件/页面
     * @returns 相交观察者
     */
    createIntersectionObserver(component) {
        return component.createIntersectionObserver( {observeAll: this.observeAll});
    }
}

/**
     * 连接相交观察者
     * @param {Object} component 组件/页面
     * @param {String} target 被观察的目标
     * @param {Object} options 选项
     * @param {Function} callback 回调
     */
export function observeIntersector(component, target, options, callback) {
    const factory = ObserverFactory.getInstance();
    const observer = factory.createIntersectionObserver(component);
    const _option = Object.assign({
        top: 0,
        bottom: 0,
    }, options);
    observer.relativeToViewport(_option);
    observer.observe(target, res => {
        // 当相交区域为0, 说明selector已经离开了屏幕位置
        const disappear = res.intersectionRatio <= 0;
        callback && callback({disappear});
    });
}
