/**
 * 业务子组件：验证「业务模块之间的 require 链」在依赖 base 的情况下仍然成立。
 *
 * @format
 */
import React from 'react';
import {StyleSheet, Text, View} from 'react-native';

type Props = {
  score: number;
  level: string;
  label: string;
};

function ScoreBoard({score, level, label}: Props): React.JSX.Element {
  const pct = Math.max(0, Math.min(100, score));
  return (
    <View style={styles.card} testID="business-score-board">
      <Text style={styles.label}>{label}</Text>
      <Text style={styles.level}>level: {level}</Text>
      <View style={styles.track}>
        <View style={[styles.fill, {width: `${pct}%`}]} />
      </View>
      <Text style={styles.hint}>score = {score}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: '#fff',
    borderRadius: 14,
    padding: 16,
    gap: 8,
    shadowColor: '#1c2440',
    shadowOpacity: 0.08,
    shadowRadius: 12,
    shadowOffset: {width: 0, height: 4},
    elevation: 2,
  },
  label: {fontSize: 26, fontWeight: '800', color: '#3f5efb'},
  level: {fontSize: 12, color: '#7a839e'},
  track: {
    height: 8,
    borderRadius: 4,
    backgroundColor: '#e6eaf5',
    overflow: 'hidden',
  },
  fill: {height: 8, backgroundColor: '#3f5efb'},
  hint: {fontSize: 12, color: '#7a839e'},
});

export default ScoreBoard;
