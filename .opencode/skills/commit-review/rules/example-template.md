> ⚠️ 此为格式模板，请勿直接使用。各项目请在 .review/rules/ 目录下创建自己的规则文件。

# 规则文件格式模板

本文件提供规则文件的格式模板，供各项目创建自己的规则文件时参考。

---

## R-01：空 catch 块（忽略异常）

- **规则ID**：R-01
- **标签**：[缺陷]
- **严重级别**：P1
- **检查项描述**：catch 块中未做任何处理，直接吞掉异常，可能导致问题被隐藏，难以排查
- **扣分值**：-10

**正面示例**：
```java
try {
    // 业务逻辑
} catch (IOException e) {
    logger.error("文件读取失败", e);
    return defaultValue;
}
```

**反面示例**：
```java
try {
    // 业务逻辑
} catch (IOException e) {
    // 什么都没做
}
```

---

## R-02：日志记录敏感信息

- **规则ID**：R-02
- **标签**：[安全]
- **严重级别**：P0
- **检查项描述**：在日志中输出 token、密钥、密码等敏感信息，可能导致信息泄露
- **扣分值**：-25

**正面示例**：
```java
logger.info("用户登录成功, userId={}", userId);
// 不记录密码或token
```

**反面示例**：
```java
logger.info("登录成功, token={}", token);
// 敏感信息被记录
```

---

## R-03：异常处理链缺少兜底处理

- **规则ID**：R-03
- **标签**：[缺陷]
- **严重级别**：P1
- **检查项描述**：多层异常捕获中，最外层缺少兜底处理（catch Exception 或 finally），可能导致异常穿透
- **扣分值**：-10

**正面示例**：
```java
try {
    // 业务逻辑
} catch (SpecificException e) {
    // 处理特定异常
} catch (Exception e) {
    // 兜底处理
    logger.error("未知错误", e);
}
```

**反面示例**：
```java
try {
    // 业务逻辑
} catch (SpecificException e) {
    // 只处理特定异常，没有兜底
}
```

---

## R-04：循环内执行 I/O 或数据库操作

- **规则ID**：R-04
- **标签**：[性能]
- **严重级别**：P1
- **检查项描述**：在循环内部执行文件 I/O、网络请求或数据库操作，导致性能问题
- **扣分值**：-10

**正面示例**：
```java
List<String> ids = collectIds();  // 先收集所有ID
batchProcess(ids);                // 批量处理
```

**反面示例**：
```java
for (String item : items) {
    writeToFile(item);  // 循环内频繁写入文件
    db.save(item);      // 或循环内频繁操作数据库
}
```

---
