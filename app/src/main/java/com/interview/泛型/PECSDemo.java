package com.interview.泛型;

import java.util.ArrayList;
import java.util.List;

class Fruit{}
class Apple extends Fruit{}
class Banana extends Fruit{}
public class PECSDemo {

    public static void main(String[] args) {
        // 逆变 为上界通配符，限制类型只能是T或者T的派生类，
        List<? extends Fruit> plates = new ArrayList<>();
//        plates.add(new Apple());
        Fruit fruit = plates.get(0);
        // 协变 通配符下界，限制类只能是T或者T的超类
        List<? super Fruit> fruits = new ArrayList<>();
        fruits.add(new Apple());
        Object fruit1 = fruits.get(0);
    }
}
