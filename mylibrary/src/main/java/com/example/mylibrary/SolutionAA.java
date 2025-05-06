package com.example.mylibrary;

public class SolutionAA {
    public static int getResultNum(int num) {
        int b;
        int original = num;
        int sum = 0;
        do {
            b = num % 10;
            num = num / 10;
            if(original % b == 0) {
                sum++;
            }
        }while(num != 0);
        return sum;
    }

    public static void main(String[] args) {
        System.out.println(getResultNum(7));
        System.out.println(getResultNum(121));
        System.out.println(getResultNum(1248));
    }
}
