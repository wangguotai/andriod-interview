/**************************************************
 * @description: 迁移主客 AutoLoginManager.js
 *  - created time: 2024/6/21
 *  -
 **************************************************/

/**
 * 检查登录状态
 * @param app 小程序app实例
 */
function checkLoginStatus(app) {
    return new Promise((resolve, reject) => {
        try {
            // 获取应用实例中的登录模块
            const loginModule = app?.cashier?.loginSDK;
            if (loginModule) {
                loginModule.checkLogin().then(isLogined => {
                    // 更新组件的数据模型和全局数据中的登录状态
                    resolve({isLogined}); // 将登录状态传递给 Promise 的 resolve
                }).catch(error => {
                    // 错误处理：记录错误信息
                    console.error('Error checking login status:', error.message);
                    reject(error); // 将错误信息传递给 Promise 的 reject
                });
            } else {
                console.error('Error checking login status:', '[Account] -- Login module not found: app.cashier.login');
                reject();
            }
        } catch (error) {
            // 错误处理：记录错误信息
            console.error('Error checking login status:', error.message);
            reject(error); // 将错误信息传递给 Promise 的 reject
        }
    });
}

export {
    checkLoginStatus
};
