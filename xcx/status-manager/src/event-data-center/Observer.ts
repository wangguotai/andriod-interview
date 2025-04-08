/**
 * @description: 观察者类
 * 
 */

export default class Observer {

    // 更新函数
    callback: Function | null;
    
    // 配置项
    selector: Function | null = null;

    constructor(cb: Function, selector: any) {
        this.callback = cb;
        this.selector = selector;
    }

    next(...args: any) {
        let update = true;
        if(typeof this.selector === 'function') {
            // 决定是否发生更新
            update = this.selector(...args);
        }
        this.callback  && this.callback(...args);
    }
}