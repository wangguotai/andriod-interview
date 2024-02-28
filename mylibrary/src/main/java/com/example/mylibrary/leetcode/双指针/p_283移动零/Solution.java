package com.example.mylibrary.leetcode.双指针.p_283移动零;

/**
 * Time: 2024/2/29
 * Author: wgt
 * Description:
 */
// 快慢指针初始位置一至，当条件发生变化后，快指针继续前进，慢指针停留在待调整的位置
class Solution {
    // public void moveZeroes(int[] nums) {
    //     int j=nums.length-1;
    //     for(int i=nums.length-1;i>=0;i--){
    //         if(nums[i] == 0){
    //             for(int k=i+1;k<=j;k++){
    //                 nums[k-1]=nums[k];
    //             }
    //             nums[j--]=0;
    //         }
    //     }
    // }

    // 方法1. 快慢指针，快指针指向非零，慢指针指向0
    public void moveZeroes1(int[] nums) {
        if (nums == null) {
            return;
        }
        // 第一次遍历的时候，j指针记录非0的个数，只要是非0的统统都赋给inums[j]
        int j = 0;
        for (int i = 0; i < nums.length; i++) {
            if (nums[i] != 0) {
                nums[j++] = nums[i];
            }
        }
        // 剩余的位置都应该是0
        for (; j < nums.length; j++) {
            nums[j] = 0;
        }
    }

    // 方法2. 参考快速排序的思想 慢指针的位置指向为0,将所有非零的元素移动到0元素的左侧
    public void moveZeroes(int[] nums) {
        if (nums == null) {
            return;
        }
        int j = 0;
        for (int i = 0; i < nums.length; i++) {
            if (nums[i] != 0) {
                if (i == j) {
                    j++;
                } else {
                    nums[j++] = nums[i];
                    nums[i] = 0;
                }
            }
        }
    }
}