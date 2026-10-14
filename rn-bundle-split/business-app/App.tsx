/**
 * 业务 App 根组件。
 *
 * 刻意只依赖 react-native 与 react —— 它们全部由 base bundle 提供。
 * 因此本文件所属的 bundle 里，不应出现任何 react/react-native 的模块定义，
 * 只应有下面这几个业务自有模块（App.tsx / 本包内部模块）。
 *
 * @format
 */
import React, {useState} from 'react';
import {
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';

import {summarizeResult, formatScore, LEVEL_ORDER} from './src/logic';
import ScoreBoard from './src/ScoreBoard';

function App(): React.JSX.Element {
  const [count, setCount] = useState(0);
  const stats = summarizeResult({hits: 7 + count, misses: 3, level: 'gold'});

  return (
    <SafeAreaView style={styles.root} testID="business-app-root">
      <StatusBar barStyle="dark-content" />
      <ScrollView contentContainerStyle={styles.content}>
        <Text style={styles.title}>业务包 (Business Bundle)</Text>
        <Text style={styles.subtitle}>
          这个界面来自独立的业务 bundle，基础库来自 base bundle
        </Text>

        <ScoreBoard
          score={stats.score}
          level={stats.level}
          label={formatScore(stats.score)}
        />

        <View style={styles.row}>
          <TouchableOpacity
            style={styles.button}
            onPress={() => setCount(c => c + 1)}>
            <Text style={styles.buttonText}>命中 +1（{count}）</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.meta}>等级顺序：{LEVEL_ORDER.join(' < ')}</Text>
        <Text style={styles.meta}>
          统计：命中 {stats.hits} / 未命中 {stats.misses}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: {flex: 1, backgroundColor: '#f5f7fb'},
  content: {padding: 20, gap: 12},
  title: {fontSize: 22, fontWeight: '700', color: '#1c2440'},
  subtitle: {fontSize: 13, color: '#5b6584', marginBottom: 8},
  row: {flexDirection: 'row', gap: 12},
  button: {
    backgroundColor: '#3f5efb',
    paddingHorizontal: 18,
    paddingVertical: 10,
    borderRadius: 10,
  },
  buttonText: {color: '#fff', fontWeight: '600'},
  meta: {fontSize: 12, color: '#7a839e'},
});

export default App;
