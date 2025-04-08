import Subscription from "./Subscription";
/**
 * 记录当前订阅器对应的 pageId，当页面发生更新的时候，
 * 会调用 emitChange 设置更新状态，接下来页面是否需要更新根据这个状态判定。
 */
class CustomSubscription extends Subscription {
  pageId: string | null = null;
  // 更新标志，证明此页面发生过更新
  hasChange = false;
  /**
   * 自定义的订阅器，建立起page纬度
   * @param pageId 页面id
   */
  constructor(pageId: string | null) {
    super();
    this.pageId = pageId;
  }

  /**
   *
   * @param status 状态
   * @description: 触发更新
   */
  emitChange(status: boolean) {
    this.hasChange = status;
  }
}

export default CustomSubscription;
