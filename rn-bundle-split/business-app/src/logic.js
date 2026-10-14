/**
 * 业务自有纯逻辑模块：用于验证「业务包里的新增模块」能否正确注册与互相 require。
 * 刻意不依赖 react-native —— 便于在 node 里做拼接执行校验。
 */

export const LEVEL_ORDER = ['bronze', 'silver', 'gold', 'platinum'];

const LEVEL_SCORE = {
  bronze: 1,
  silver: 2,
  gold: 3,
  platinum: 4,
};

/** 命中/未命中加权算分；命中权重高于未命中，且封顶 100。 */
export function summarizeResult({hits = 0, misses = 0, level = 'bronze'} = {}) {
  const weighted = hits * 3 - misses * 2;
  const bonus = LEVEL_SCORE[level] || 0;
  const score = Math.max(0, Math.min(100, weighted + bonus * 5));
  return {
    hits,
    misses,
    level,
    score,
    bonus,
  };
}

export function formatScore(score) {
  if (score >= 90) {
    return `SS · ${score}`;
  }
  if (score >= 70) {
    return `S · ${score}`;
  }
  if (score >= 50) {
    return `A · ${score}`;
  }
  return `B · ${score}`;
}
