package com.example.mylibrary.java基础.接口和继承4;

import com.example.mylibrary.Solution;

/**
 * Time: 2025/4/2
 * Author: wgt
 * Description:
 */
interface AA {
}

interface BB {
}

interface CC extends AA, BB {
}

public class Main extends Solution implements AA, BB {
    public static void main(String[] args) {
        Thread myThread = new Thread();
    }
}
