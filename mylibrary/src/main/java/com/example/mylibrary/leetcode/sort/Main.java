package com.example.mylibrary.leetcode.sort;

import java.util.Arrays;

public class Main {
    //  1. 冒泡排序
    public static int[] bubbleSort(int[] arrs) {
        for (int i = arrs.length - 1; i > 0; i--) {
            boolean flag = false;
            for (int j = 1; j <= i; j++) {

                if (arrs[j] < arrs[j - 1]) {
                    flag = true;
                    int temp = arrs[j - 1];
                    arrs[j - 1] = arrs[j];
                    arrs[j] = temp;
                }
            }
            if (!flag) { // 如果遍历一遍，没有位置改动，说明
                break;
            }
        }
        return arrs;
    }

    //    2. 快速排序
//    快速排序使用分治法（Divide and conquer）策略来把一个串行分为两个子串行

    /**
     * 算法步骤
     * 从数列中挑出一个元素，称为 "基准"（pivot）;
     * <p>
     * 重新排序数列，所有元素比基准值小的摆放在基准前面，所有元素比基准值大的摆在基准的后面（相同的数可以到任一边）。在这个分区退出之后，该基准就处于数列的中间位置。这个称为分区（partition）操作；
     * <p>
     * 递归地（recursive）把小于基准值元素的子数列和大于基准值元素的子数列排序；
     *
     * @param arr
     * @param left
     * @param right
     * @return
     */
    public static int[] quickSort(int[] arr, int left, int right) {
        if (left < right) {
            int partitionIndex = partition(arr, left, right);
            quickSort(arr, left, partitionIndex - 1);
            quickSort(arr, partitionIndex + 1, right);
        }
        return arr;
    }

    public static int[] quickSort(int[] arr) {

        return quickSort(arr, 0, arr.length - 1);
    }

    private static int partition(int[] arr, int left, int right) {
        // 设定基准值 (pivot)
        int pivot = left;
        int index = pivot + 1;
        for (int i = index; i <= right; i++) {
            if (arr[i] <= arr[pivot]) {
                swap(arr, i, index);
                index++;
            }
        }
        swap(arr, pivot, index - 1);
        return index - 1;
    }

    public static void swap(int[] arr, int i, int j) {
        int temp = arr[i];
        arr[i] = arr[j];
        arr[j] = temp;
    }

    /**
     * 3. 堆排序
     * 算法步骤：
     * 1. 创建一个堆 H[0...n-1]；
     * 2. 把堆首（最大值）和堆尾互换；
     * 3. 把堆的尺寸缩小1，并把新的数组顶端数据调整到相应为止；
     * 4. 重复步骤2.直至堆的尺寸为1.
     *
     * @param sourceArr
     */
    public static int[] heapSort(int[] sourceArr) {
        int[] arr = Arrays.copyOf(sourceArr, sourceArr.length);
        int len = arr.length;
        buildMaxHeap(arr, len);
        for (int i = len - 1; i > 0; i--) {
            swap(arr, i, 0);
            len--;
            heapify(arr, 0, len);
        }
        return arr;
    }

    private static void buildMaxHeap(int[] arr, int len) {
        for (int i = len / 2; i >= 0; i--) {
            heapify(arr, i, len);
        }
    }

    private static void heapify(int[] arr, int i, int len) {
        int left = 2 * i + 1;
        int right = 2 * i + 2;
        int largest = i;
        if (left < len && arr[left] > arr[largest]) {
            largest = left;
        }
        if (right < len && arr[right] > arr[largest]) {
            largest = right;
        }
        if (largest != i) {
            swap(arr, largest, i);
            heapify(arr, largest, len);
        }
    }

    /**
     * 4. 归并排序(Merge Sort)是建立在归并操作上的一种有效的排序算法。该算法是采用分治法的一个非常典型的应用
     *
     * @return
     */
    public static int[] mergeSort(int[] arr) {

        return partitionForMerge(arr, 0, arr.length - 1);

    }

    private static int[] partitionForMerge(int[] arr, int low, int high) {
        if (low < high) {
            int mid = (low + high) / 2;
            partitionForMerge(arr, low, mid);
            partitionForMerge(arr, mid + 1, high);
            merge(arr, low, mid, high);
        }
        return arr;
    }

    private static int[] merge(int[] arr, int low, int mid, int high) {
        int[] temp = new int[high - low + 1];
        int i = low, j = mid + 1, k = 0;
        while (i <= mid && j <= high) {
            if (arr[i] <= arr[j]) {
                temp[k++] = arr[i++];
            } else {
                temp[k++] = arr[j++];
            }
        }
        while (i <= mid) {
            temp[k++] = arr[i++];
        }
        while (j <= high) {
            temp[k++] = arr[j++];
        }
        k = 0;
        for (int p = low; p <= high; p++) {
            arr[p] = temp[k++];
        }
        return arr;
    }

    public static void main(String[] args) {
        System.out.println(Arrays.toString(bubbleSort(
                new int[]{
                        2, 1, 5, 7, 9, 1, 4, 2, 3
                }
        )));
        System.out.println(Arrays.toString(quickSort(
                new int[]{
                        2, 1, 5, 7, 9, 1, 4, 2, 3
                }
        )));
        System.out.println(Arrays.toString(heapSort(
                new int[]{
                        2, 1, 5, 7, 9, 1, 4, 2, 3
                }
        )));
        System.out.println(Arrays.toString(mergeSort(
                new int[]{
                        2, 1, 5, 7, 9, 1, 4, 2, 3
                }
        )));
    }
}
