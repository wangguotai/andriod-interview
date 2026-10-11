package com.example.mylibrary.leetcode.leetcode_75.动态规划.多维

fun uniquePaths(m: Int, n: Int): Int {
    val dp = Array(m){IntArray(n)}
    for (i in 1..<m){
        dp[i][0] = 1
    }
    for (i in 1 ..< n) {
        dp[0][i] = 1
    }

    for (i in 1..<m) {
        for(j in 1..<n){
            dp[i][j] = dp[i-1][j] + dp[i][j-1]
        }
    }
    return dp[m-1][n-1]

}

fun main() {
    uniquePaths(1, 2)
}
