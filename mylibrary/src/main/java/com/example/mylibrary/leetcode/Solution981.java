package com.example.mylibrary.leetcode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Solution981 {
    class TimeMap {

        private  class Node {
            int timestamp;
            String value;

            Node(int timestamp, String value) {
                this.timestamp = timestamp;
                this.value = value;
            }
        }

        Map<String, List<Node>> map;

        public TimeMap() {
            map = new HashMap<>();
        }

        public void set(String key, String value, int timestamp) {
            map.computeIfAbsent(key, k -> {
                return new ArrayList<Node>();
            }).add(new Node(timestamp, value));
        }

        public String get(String key, int timestamp) {
            if (map.containsKey(key)) {
                List<Node> nodes = map.get(key);
                int left = 0, right = nodes.size() - 1;
                while (left <= right) {
                    int mid = left + (right - left) / 2;
                    if (nodes.get(mid).timestamp <= timestamp) {
                        left = mid + 1;
                    } else {
                        right = mid - 1;
                    }
                }
                if(right < 0) {
                    return "";
                }
                return nodes.get(right).value;
            } else {
                return "";
            }
        }


    }

/**
 * Your TimeMap object will be instantiated and called as such:
 * TimeMap obj = new TimeMap();
 * obj.set(key,value,timestamp);
 * String param_2 = obj.get(key,timestamp);
 */
}
