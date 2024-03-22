package com.example.mylibrary.leetcode.动态规划.p118杨辉三角;

import java.util.ArrayList;
import java.util.List;

/**
 * Time: 2024/3/22
 * Author: wgt
 * Description:
 */
public class Solution {
    public List<List<Integer>> generate(int numRows) {
        List<List<Integer>> ans = new ArrayList<>(numRows);
        List<Integer> pre = new ArrayList<>(1);
        pre.add(1);
        ans.add(pre);
        for (int i = 2; i <= numRows; i++) {
            List<Integer> rowList = new ArrayList<>(i);
            rowList.add(1);
            for (int j = 1; j < i - 1; j++) {
                rowList.add(pre.get(j - 1) + pre.get(j));
            }
            rowList.add(1);
            pre = rowList;
        }
        return ans;
    }
}
