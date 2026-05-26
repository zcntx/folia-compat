package com.foliacompat.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionUtil {

    private ReflectionUtil() {}

    // 反射缓存，避免每次调用都遍历类层次
    private static final ConcurrentHashMap<String, Field> fieldCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> methodCache = new ConcurrentHashMap<>();

    public static Object getField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    public static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    public static void setStaticField(Class<?> clazz, String fieldName, Object value) throws Exception {
        Field field = findField(clazz, fieldName);
        field.setAccessible(true);
        field.set(null, value);
    }

    public static Object invokeMethod(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = findMethod(target.getClass(), methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    public static Object invokeStaticMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = findMethod(clazz, methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        String key = clazz.getName() + "#" + fieldName;
        Field cached = fieldCache.get(key);
        if (cached != null) return cached;

        Class<?> current = clazz;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                fieldCache.put(key, field);
                return field;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found in " + clazz.getName());
    }

    private static Method findMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes) throws NoSuchMethodException {
        String key = clazz.getName() + "#" + methodName + paramTypesKey(paramTypes);
        Method cached = methodCache.get(key);
        if (cached != null) return cached;

        Class<?> current = clazz;
        while (current != null) {
            try {
                Method method = current.getDeclaredMethod(methodName, paramTypes);
                methodCache.put(key, method);
                return method;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException("Method " + methodName + " not found in " + clazz.getName());
    }

    private static String paramTypesKey(Class<?>[] paramTypes) {
        if (paramTypes == null || paramTypes.length == 0) return "()";
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> pt : paramTypes) {
            sb.append(pt.getName()).append(",");
        }
        sb.append(")");
        return sb.toString();
    }
}
