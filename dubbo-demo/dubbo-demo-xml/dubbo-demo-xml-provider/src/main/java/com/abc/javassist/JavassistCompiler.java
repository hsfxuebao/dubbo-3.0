package com.abc.javassist;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Modifier;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtField;
import javassist.CtMethod;
import javassist.CtNewMethod;

public class JavassistCompiler {

    public static void main(String[] args) throws Exception {
        // 获取CtClass实例的工具类
        ClassPool pool = ClassPool.getDefault();
        // CtClass，Class Type Class，字节码类型的class类
        CtClass ctClass = genericClass(pool);
        // 生成相应实例，并调用其业务方法
        invokeInstance(ctClass);
    }

    private static CtClass genericClass(ClassPool pool) throws Exception {
        // 通过ClassPool生成一个public的com.abc.Person类的ctClass
        CtClass ctClass = pool.makeClass("com.abc.Person");

        // 下面都是对这个ctClass的初始化

        // 添加private String name;属性
        CtField nameField = new CtField(pool.getCtClass("java.lang.String"), "name", ctClass);
        nameField.setModifiers(Modifier.PRIVATE);
        ctClass.addField(nameField);

        // 添加private int age;属性
        CtField ageField = new CtField(pool.getCtClass("int"), "age", ctClass);
        ageField.setModifiers(Modifier.PRIVATE);
        ctClass.addField(ageField);

        // 添加getter and setter
        ctClass.addMethod(CtNewMethod.getter("getName",nameField));
        ctClass.addMethod(CtNewMethod.setter("setName",nameField));
        ctClass.addMethod(CtNewMethod.getter("getAge",ageField));
        ctClass.addMethod(CtNewMethod.setter("setAge",ageField));

        // 添加无参构造器
        CtConstructor ctConstructor = new CtConstructor(new CtClass[]{}, ctClass);
        String body = "{\nname=\"zhangsan\";\nage=23;\n}";
        ctConstructor.setBody(body);
        ctClass.addConstructor(ctConstructor);

        // 添加业务方法
        CtMethod ctMethod = new CtMethod(CtClass.voidType, "personInfo", new CtClass[]{}, ctClass);
        ctMethod.setModifiers(Modifier.PUBLIC);
        StringBuffer buffer = new StringBuffer();
        buffer.append("{\nSystem.out.println(\"name=\"+name);\n");
        buffer.append("System.out.println(\"age=\"+age);\n}");
        ctMethod.setBody(buffer.toString());
        ctClass.addMethod(ctMethod);

        // 把生成的字节码文件写入的位置
        byte[] bytes = ctClass.toBytecode();
        File file = new File("D:\\Person.class");
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(bytes);
        fos.close();

        return ctClass;
    }

    private static void invokeInstance(CtClass ctClass) throws Exception {
        // 完成真正的编译，形成字节码
        Class<?> clazz = ctClass.toClass();
        // 创建实例
        Object instance = clazz.newInstance();
        // 调用personInfo()业务方法
        instance.getClass()
                .getMethod("personInfo", new Class[]{})
                .invoke(instance, new Object[]{});
    }
}
