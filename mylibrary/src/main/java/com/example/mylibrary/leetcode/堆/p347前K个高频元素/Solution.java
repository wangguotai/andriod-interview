package com.example.mylibrary.leetcode.堆.p347前K个高频元素;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                Arrays.toString(new Solution().topKFrequent(
                        new int[]{1, 2}, 2
                ))
        );
    }

    public int[] topKFrequent1(int[] nums, int k) {
        int[] ans = new int[k];
        Map<Integer, Integer> map1 = new HashMap<>();
        Map<Integer, List<Integer>> map2 = new HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            map1.put(nums[i], map1.getOrDefault(nums[i], 0) + 1);
        }
        int[] sums = new int[map1.size()];
        int i = 0;
        for (Map.Entry<Integer, Integer> entry : map1.entrySet()) {
            int item = entry.getKey();
            int sum = entry.getValue();
            if (map2.get(sum) == null) {
                List<Integer> list = new LinkedList<>();
                list.add(item);
                map2.put(sum, list);
            } else {
                List<Integer> list = map2.get(sum);
                list.add(item);
                map2.put(sum, list);

            }
            sums[i++] = sum;
        }
        // 构建大顶堆
        int mid = sums.length >> 1;
        for (int j = mid; j >= 0; j--) {
            heapify(sums, j, sums.length);
        }
        for (int j = 0; j < k; j++) {
            List<Integer> res = map2.get(sums[0]);
            ans[j] = res.remove(0);
            swap(sums, 0, sums.length - j - 1);
            heapify(sums, 0, sums.length - j - 1);
        }
        return ans;
    }

    private void heapify(int[] nums, int root, int len) {
        int maxIndex = root;
        int left = 2 * root + 1;
        int right = 2 * root + 2;
        if (left < len && nums[maxIndex] < nums[left]) {
            maxIndex = left;
        }
        if (right < len && nums[maxIndex] < nums[right]) {
            maxIndex = right;
        }
        if (maxIndex != root) {
            swap(nums, root, maxIndex);
            heapify(nums, maxIndex, len);
        }
    }

    private void swap(int[] nums, int i, int j) {
        int temp = nums[i];
        nums[i] = nums[j];
        nums[j] = temp;
    }

    public int[] topKFrequent(int[] nums, int k) {
        // 使用字典，统计每个元素出现的次数，元素为键，元素出现的次数为值
        Map<Integer, Integer> map = new HashMap<>();
        for (int num : nums) {
            map.put(num, map.getOrDefault(num, 0) + 1);
        }
        // 遍历map，用最小堆保存频率最大的k个元素
        PriorityQueue<Integer> pq = new PriorityQueue<>(
                new Comparator<Integer>() {
                    @Override
                    public int compare(Integer o1, Integer o2) {
                        return map.get(o1) - map.get(o2);
                    }
                }
        );
        for (Integer key : map.keySet()) {
            if (pq.size() < k) {
                pq.add(key);
            } else if (map.get(key) > map.get(pq.peek())) {
                pq.remove();
                pq.add(key);
            }
        }
        int[] ans = new int[k];
        while (!pq.isEmpty()) {
            ans[--k] = pq.remove();
        }
        return ans;
    }
}
