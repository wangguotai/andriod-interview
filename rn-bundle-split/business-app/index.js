/**
 * 业务工程入口。
 *
 * 注意：这里注册的组件名（SPEC）必须与宿主 App 里 require 业务模块时用的名字一致。
 * 宿主在加载业务 bundle 后会 require('BusinessApp') 拿到它的默认导出并挂载。
 *
 * @format
 */
import {AppRegistry} from 'react-native';
import App from './App';

export const BUSINESS_APP_NAME = 'BusinessApp';

AppRegistry.registerComponent(BUSINESS_APP_NAME, () => App);

// 供宿主侧 require 业务包后直接取用（不依赖 AppRegistry 的注册表）
export default App;
