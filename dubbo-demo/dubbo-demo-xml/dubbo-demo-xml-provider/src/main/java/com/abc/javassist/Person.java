package com.abc.javassist;

public class Person {
    private String name;
    private int age;

    public Person() {
        name = "zhangsan";
        age = 20;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    // 业务方法
    public void personInfo() {
        System.out.println("name = " + name);
        System.out.println("age = " + age);
    }

}
