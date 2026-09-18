package com.monitor.sdk.agent;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 业务方法参数属性提取器。
 *
 * <p>支持从 POJO、JavaBean、Java Record、Map、List、数组等各类业务方法入参中按路径提取属性值，
 * 例如 {@code orderId}、{@code 0.orderId}、{@code 0.order.id}、{@code params.order_id}、
 * {@code args[0].orderId}、{@code [0].channel}、{@code 0['order_id']} 等。</p>
 */
final class PropertyExtractor {

    private static final Map<String, Method> METHOD_CACHE = new ConcurrentHashMap<String, Method>();
    private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<String, Field>();

    private PropertyExtractor() {
    }

    /**
     * 根据属性路径表达式从方法入参数组中提取对应的值。
     *
     * @param arguments  方法入参数组
     * @param method     被拦截的业务方法
     * @param expression 属性路径表达式
     * @return 提取到的属性值，未找到或提取异常时返回 null
     */
    public static Object extract(Object[] arguments, Method method, String expression) {
        if (arguments == null || arguments.length == 0 || expression == null) {
            return null;
        }
        String expr = expression.trim();
        if (expr.isEmpty()) {
            return null;
        }

        List<String> tokens = parsePathTokens(expr);
        if (tokens.isEmpty()) {
            return null;
        }

        int tokenStartIdx = 0;
        Object root = null;
        String firstToken = tokens.get(0);

        // 1. 若第一个 token 是纯数字索引（例如 "0", "1"）
        if (isInteger(firstToken)) {
            int argIndex = Integer.parseInt(firstToken);
            if (argIndex >= 0 && argIndex < arguments.length) {
                root = arguments[argIndex];
                tokenStartIdx = 1;
                // 若仅有一个数字 token，直接返回该参数本身
                if (tokens.size() == 1) {
                    return root;
                }
            } else {
                return null;
            }
        }
        // 2. 若第一个 token 匹配方法形参名称（需编译保留参数名）
        else if (method != null && hasMatchingParamName(method, firstToken)) {
            int paramIdx = getParamIndexByName(method, firstToken);
            if (paramIdx >= 0 && paramIdx < arguments.length) {
                root = arguments[paramIdx];
                tokenStartIdx = 1;
                if (tokens.size() == 1) {
                    return root;
                }
            }
        }
        // 3. 单参数方法，默认以第 0 个参数为根对象
        else if (arguments.length == 1) {
            root = arguments[0];
            tokenStartIdx = 0;
        }
        // 4. 多参数方法且未显式指定参数下标，优先在第 0 个参数查找；若未找到且有其他参数则逐个探测
        else {
            root = arguments[0];
            tokenStartIdx = 0;
            Object val = evaluateTokens(root, tokens, tokenStartIdx);
            if (val != null) {
                return val;
            }
            for (int i = 1; i < arguments.length; i++) {
                Object alternativeVal = evaluateTokens(arguments[i], tokens, 0);
                if (alternativeVal != null) {
                    return alternativeVal;
                }
            }
            return null;
        }

        return evaluateTokens(root, tokens, tokenStartIdx);
    }

    private static Object evaluateTokens(Object current, List<String> tokens, int startIndex) {
        Object value = current;
        for (int i = startIndex; i < tokens.size(); i++) {
            if (value == null) {
                return null;
            }
            String token = tokens.get(i);
            value = getProperty(value, token);
        }
        return value;
    }

    /**
     * 从目标对象获取单个属性值。支持 Map、List、数组、Getter、Record 方法及字段。
     */
    public static Object getProperty(Object target, String propertyName) {
        if (target == null || propertyName == null || propertyName.isEmpty()) {
            return null;
        }

        // 1. Map 支持
        if (target instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) target;
            if (map.containsKey(propertyName)) {
                return map.get(propertyName);
            }
            String snake = toSnakeCase(propertyName);
            if (snake != null && map.containsKey(snake)) {
                return map.get(snake);
            }
            String camel = toCamelCase(propertyName);
            if (camel != null && map.containsKey(camel)) {
                return map.get(camel);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && propertyName.equalsIgnoreCase(entry.getKey().toString())) {
                    return entry.getValue();
                }
            }
            return null;
        }

        // 2. List 支持
        if (target instanceof List && isInteger(propertyName)) {
            int idx = Integer.parseInt(propertyName);
            List<?> list = (List<?>) target;
            return (idx >= 0 && idx < list.size()) ? list.get(idx) : null;
        }

        // 3. 数组支持
        if (target.getClass().isArray() && isInteger(propertyName)) {
            int idx = Integer.parseInt(propertyName);
            int len = Array.getLength(target);
            return (idx >= 0 && idx < len) ? Array.get(target, idx) : null;
        }

        Class<?> clazz = target.getClass();

        // 4. Getter 方法 (getProp, isProp, prop) 及 Record 访问器
        Method method = findGetterMethod(clazz, propertyName);
        if (method != null) {
            try {
                return method.invoke(target);
            } catch (Throwable ignored) {
            }
        }

        // 5. 字段访问（支持 private/protected）
        Field field = findField(clazz, propertyName);
        if (field != null) {
            try {
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static Method findGetterMethod(Class<?> clazz, String prop) {
        String cacheKey = clazz.getName() + "#method#" + prop;
        Method cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String capitalized = capitalize(prop);
        String getGetter = "get" + capitalized;
        String isGetter = "is" + capitalized;
        String exactName = prop;
        String camelName = toCamelCase(prop);
        String getCamelGetter = "get" + capitalize(camelName);

        Method[] methods = clazz.getMethods();
        for (Method m : methods) {
            if (m.getParameterTypes().length != 0 || m.getReturnType().equals(Void.TYPE)) {
                continue;
            }
            String mName = m.getName();
            if (mName.equals(getGetter) || mName.equals(isGetter) || mName.equals(exactName)
                    || mName.equals(getCamelGetter) || mName.equals(camelName)) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                METHOD_CACHE.put(cacheKey, m);
                return m;
            }
        }

        for (Method m : methods) {
            if (m.getParameterTypes().length != 0 || m.getReturnType().equals(Void.TYPE)) {
                continue;
            }
            String mName = m.getName();
            if (mName.equalsIgnoreCase(getGetter) || mName.equalsIgnoreCase(isGetter)
                    || mName.equalsIgnoreCase(exactName)) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
                METHOD_CACHE.put(cacheKey, m);
                return m;
            }
        }

        return null;
    }

    private static Field findField(Class<?> clazz, String prop) {
        String cacheKey = clazz.getName() + "#field#" + prop;
        Field cached = FIELD_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String exactName = prop;
        String camelName = toCamelCase(prop);
        String snakeName = toSnakeCase(prop);

        Class<?> current = clazz;
        while (current != null && !current.equals(Object.class)) {
            Field[] fields = current.getDeclaredFields();
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                String fName = f.getName();
                if (fName.equals(exactName) || fName.equals(camelName) || (snakeName != null && fName.equals(snakeName))
                        || fName.equalsIgnoreCase(exactName)) {
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    FIELD_CACHE.put(cacheKey, f);
                    return f;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static List<String> parsePathTokens(String path) {
        List<String> tokens = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        char quoteChar = 0;

        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);

            if (inQuote) {
                if (c == quoteChar) {
                    inQuote = false;
                } else {
                    sb.append(c);
                }
                continue;
            }

            if (c == '\'' || c == '"') {
                inQuote = true;
                quoteChar = c;
            } else if (c == '.' || c == '[' || c == ']') {
                if (sb.length() > 0) {
                    String token = sb.toString().trim();
                    if (!tokens.isEmpty() || (!"args".equalsIgnoreCase(token)
                            && !"params".equalsIgnoreCase(token)
                            && !"arguments".equalsIgnoreCase(token)
                            && !"#root".equalsIgnoreCase(token))) {
                        tokens.add(token);
                    }
                    sb.setLength(0);
                }
            } else {
                sb.append(c);
            }
        }

        if (sb.length() > 0) {
            String token = sb.toString().trim();
            if (!token.isEmpty()) {
                if (!tokens.isEmpty() || (!"args".equalsIgnoreCase(token)
                        && !"params".equalsIgnoreCase(token)
                        && !"arguments".equalsIgnoreCase(token)
                        && !"#root".equalsIgnoreCase(token))) {
                    tokens.add(token);
                }
            }
        }

        return tokens;
    }

    private static boolean isInteger(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private static String toCamelCase(String str) {
        if (str == null || (str.indexOf('_') < 0 && str.indexOf('-') < 0)) {
            return str;
        }
        StringBuilder sb = new StringBuilder();
        boolean nextUpper = false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '_' || c == '-') {
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String toSnakeCase(String str) {
        if (str == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && str.charAt(i - 1) != '_') {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else if (c == '-') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean hasMatchingParamName(Method method, String name) {
        try {
            Parameter[] parameters = method.getParameters();
            for (Parameter p : parameters) {
                if (p.getName().equalsIgnoreCase(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static int getParamIndexByName(Method method, String name) {
        try {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].getName().equalsIgnoreCase(name)) {
                    return i;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
