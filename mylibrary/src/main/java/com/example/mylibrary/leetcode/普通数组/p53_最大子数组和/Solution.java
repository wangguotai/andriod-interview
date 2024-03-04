package com.example.mylibrary.leetcode.普通数组.p53_最大子数组和;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().maxSubArray(
                        new int[]{
                                -2, 1, -3, 4, -1, 2, 1, -5, 4
                        }
                )
        );
    }

    /**
     * 竟然是动态规划的思路
     * 1. 确定状态
     * 用f(i)代表以第i个数结尾的【连续子数组的最大和】，我们的目标就是从0到n-1中，最大的f(i)
     * 2. 状态转移方程
     * f(i) = max(nums[i], f(i-1)+nums[i])
     * 3. 确定初始值
     * f(0)=nums[0]
     * 4. 确定返回值
     * 由于状态转移方程仅涉及两个变量，可以用变量代替fi数组，最大值用一个状态存储，最后返回最大值
     *
     * @param nums
     * @return
     */
    public int maxSubArray1(int[] nums) {
        int maxV = nums[0];
        int pre = nums[0];
        int cur;
        for (int i = 1; i < nums.length; i++) {
            cur = Math.max(nums[i], pre + nums[i]);
            if (maxV < cur) {
                maxV = cur;
            }
            pre = cur;
        }
        return maxV;
    }

    public Status getInfo(int[] a, int l, int r) {
        if (l == r) {
            return new Status(a[l], a[l], a[l], a[l]);
        }
        int m = (l + r) >> 1;
        Status lSub = getInfo(a, l, m);
        Status rSub = getInfo(a, m + 1, r);
        return pushUp(lSub, rSub);
    }

    private Status pushUp(Status l, Status r) {
        int iSum = l.iSum + r.iSum;
        int lSum = Math.max(l.lSum, l.iSum + r.lSum);
        int rSum = Math.max(r.rSum, r.iSum + l.rSum);
        int mSum = Math.max(Math.max(l.mSum, r.mSum), l.rSum + r.lSum);
        return new Status(lSum, rSum, mSum, iSum);
    }

    /**
     * 分治策略
     * 分治方法类似于【线段树求解最长公共上升子序列问题】的pushUp操作。
     * 线段树区间合并法解决多次询问的「区间最长连续上升序列问题」和「区间最大子段和问题」
     *
     * @param nums
     * @return
     */

    public int maxSubArray(int[] nums) {
        return getInfo(nums, 0, nums.length - 1).mSum;
    }

    /**
     * 对于一个区间 [l,r][l,r][l,r]，我们可以维护四个量：
     * <p>
     * lSum 表示 [l,r] 内以 l 为左端点的最大子段和
     * rSum 表示 [l,r] 内以 r 为右端点的最大子段和
     * mSum 表示 [l,r] 内的最大子段和
     * iSum 表示 [l,r] 的区间和
     * <p>
     * 著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。
     */
    public class Status {
        public int lSum, rSum, mSum, iSum;

        public Status(int lSum, int rSum, int mSum, int iSum) {
            this.lSum = lSum;
            this.rSum = rSum;
            this.mSum = mSum;
            this.iSum = iSum;
        }
    }
}
