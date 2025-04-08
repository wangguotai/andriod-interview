import Observer from "./Observer";

/**
 * 封装基础订阅器
 */
export default class Subscription {
  // 观察者对象
  observers: Set<Observer> = new Set();

  /**
   * 订阅
   * @param cb 回调函数
   * @param selector
   * @returns 取消订阅函数
   */
  subscribe(cb: Function, selector: any) {
    if (typeof cb !== "function") {
      throw new Error("cb must be a function");
    }
    const observer = new Observer(cb, selector);
    this.observers.add(observer);
    // 返回取消订阅函数
    return () => {
      this.unSubscribe(observer);
    };
  }

  /**
   * 发布通知，更新每一个订阅者
   * @param args
   */
  publish(...args: any) {
    console.log("observers 的数量", this.observers.size);
    this.observers.forEach((observer) => {
      observer.next(...args);
    });
  }

  /**
   * 取消订阅
   * @param observer
   */
  unSubscribe(observer: Observer) {
    this.observers.delete(observer);
  }
}
