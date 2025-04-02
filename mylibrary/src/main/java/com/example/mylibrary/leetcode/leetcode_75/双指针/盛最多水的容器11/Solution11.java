package com.example.mylibrary.leetcode.leetcode_75.双指针.盛最多水的容器11;

/**
 * Time: 2025/4/2
 * Author: wgt
 * Description:
 */
public class Solution11 {
    //    public int maxArea(int[] height) {
//        int len = height.length;
//        int area =0;
//        int l,r;
//        for(int j=0;j<len;j++){
//            l =j;
//            r = j+1;
//            for (int i = j+1; i < len; i++) {
//                int newArea = Math.min(height[j],height[i])*(i-j);
//                if(newArea > area) {
//                    area = newArea;
//                }
//            }
//        }
//        return area;
//    }
    public int maxArea(int[] height) {
        int l = 0, r = height.length - 1;
        int maxWater = 0;
        while (l < r) {
            int currentWater = Math.min(height[l], height[r]) * (r - l);
            maxWater = Math.max(currentWater, maxWater);
            if (height[l] < height[r]) {
                l++;
            } else {
                r--;
            }
        }
        return maxWater;
    }
}
