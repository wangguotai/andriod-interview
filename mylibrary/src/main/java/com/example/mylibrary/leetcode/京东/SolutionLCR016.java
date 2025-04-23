package com.example.mylibrary.leetcode.京东;

import java.util.HashMap;
import java.util.Map;

public class SolutionLCR016 {
    public int lengthOfLongestSubstring(String s) {
        int n = s.length();
        Map<Character, Integer> map = new HashMap<>();
        int left =0, right = 0;
        int longest = 0;
        while(right < n) {
            char ch = s.charAt(right);
            if(!map.containsKey(ch)){
                map.put(ch, right);
                longest = Math.max(longest,map.size());

            } else {
                int newLeft = map.get(ch);
                while(left < newLeft){
                    map.remove(s.charAt(left++));
                }
                map.put(ch, right);
                left++;
            }
            right++;
        }
        return Math.max(longest, map.size());
    }

    public static void main(String[] args) {
        SolutionLCR016 solutionLCR016 = new SolutionLCR016();
        solutionLCR016.lengthOfLongestSubstring("cdd");
    }
}
