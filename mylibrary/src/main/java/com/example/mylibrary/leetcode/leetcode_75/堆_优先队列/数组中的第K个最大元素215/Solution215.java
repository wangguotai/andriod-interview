package com.example.mylibrary.leetcode.leetcode_75.堆_优先队列.数组中的第K个最大元素215;

public class Solution215 {
    public int findKthLargest(int[] nums, int k) {
        int heapSize = nums.length;
        buildMaxHeap(nums, heapSize);
        for (int i = heapSize -1; i >= heapSize - k + 1 ; i--) {
            swap(nums,0, i);
            maxHeapify(nums, 0, i);
        }
    }

    private void buildMaxHeap(int[] nums, int heapSize) {
        for (int i = heapSize / 2 -1; i >=0; i--) {
            maxHeapify(nums, i, heapSize);
        }
    }

    private void maxHeapify(int[] nums, int i, int heapSize) {
        int l = 2*i+1;
        int r = 2*i+2;
        int largest = i;
        if(l < heapSize && nums[l] > nums[largest]) {
            largest = l;
        }
        if(r<heapSize && nums[r] > nums[largest]) {
            largest = r;
        }
        if(largest != i) {
            swap(nums,i, largest);
            // 调整被破坏的子树
            maxHeapify(nums, largest, heapSize);
        }

    }
    private void swap(int[] nums, int i, int largest){
        int temp = nums[i];
        nums[i] = nums[largest];
        nums[largest]=temp;
    }

}
