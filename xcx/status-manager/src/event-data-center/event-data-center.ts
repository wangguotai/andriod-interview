import CustomSubscription from "./custom-subscription";
import Model from "./Model";
import { MObject } from "./type";

let eventDataCenter: EventDataCenter | null = null;

class EventDataCenter {
  // 页面索引
  pageIndex = 0;
  // 数据
  model: Model | null = null;
  // 监听器
  subscriptions = new Map<string, CustomSubscription>();
  // 当前激活的subscription 对应的 pageId
  activePageId: string | null = null;
  // 当前激活的订阅器
  currentSubscription: CustomSubscription | null = null;

  constructor() {
    // 创建数据中心
    this.model = new Model();
  }

  initSubscription(originPageId: string) {
    // 构建唯一的页面 id
    this.pageIndex++;
    const currentPageId = `${originPageId}-${this.pageIndex}`;
    // 创建订阅器
    const subscription = new CustomSubscription(originPageId);
    this.subscriptions.set(currentPageId, subscription);
    // 设置当前激活的订阅器
    this.changeActivePageId(originPageId);
    // 设置当前激活的 pageId
    this.activePageId = originPageId;
    return currentPageId;
  }

  /**
   * 设置激活的page Id
   * @param pageId 页面 id
   */
  changeActivePageId(pageId: string | null) {
    // 设置当前激活的 pageId
    this.activePageId = pageId;
    if (pageId) {
      this.currentSubscription = this.subscriptions.get(pageId) as CustomSubscription;
    } else {
      this.currentSubscription = null;
    }
  }
  /* 改变所有订阅器的状态 */
  changeSubscriptionStatus() {
    this.subscriptions.forEach((subscription: CustomSubscription) => {
      subscription.emitChange(true);
    });
  }
  /* 执行更新 */
  notifyActiveSubscription(...arg: Array<MObject>) {
    const { subscriptions, activePageId } = this;
    const currentSubscription = subscriptions.get(activePageId as string) as CustomSubscription;
    /* 如果订阅器存在，并且触发过更新 */
    if (currentSubscription && currentSubscription.hasChange) {
      currentSubscription.publish(...arg);
      /* 更新状态 */
      currentSubscription.emitChange(false);
    }
  }
  /* 暴露的更新方法 */
  dispatchAction(payload: MObject, ...arg: Array<any>) {
    this.model?.setModelData(payload);
    /* 先改变订阅器的状态 */
    this.changeSubscriptionStatus();
    this.notifyActiveSubscription(...arg);
  }
}


/* 销毁当前的订阅器 */
export function destroySubscription(pageID: string){
    if(eventDataCenter){
        eventDataCenter.subscriptions.delete(pageID)
    }
}

export function unActiveSubscription(){
    eventDataCenter && eventDataCenter.changeActivePageId(null);    
}

/**
 * 启动当前订阅器
 * @param pageId 
 */
export function activeSubscription(activePageId: string){
    if(eventDataCenter){
        eventDataCenter.changeActivePageId(activePageId);
        /* 如果有没有更新的任务，那么会触发更新 */
        eventDataCenter.notifyActiveSubscription();
    }
}