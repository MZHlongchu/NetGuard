# NetGuard Virtual Hosts 模块 — 代码修改备忘录

## 一、模块概述

### 目标
在 NetGuard 中新增 **Virtual Hosts** 模块，允许用户在 **无 root** 的 Android 设备上自定义 hosts 文件，将指定域名的 DNS 解析重定向到自定义 IP 地址。主要用途是帮助国内用户正常访问 GitHub。

### 核心原理
```
DNS 请求 → VPN 拦截 → C 层 dns.c 解析 DNS 响应
                                ↓
                    检查 qname 是否在 mapHostsRedirect 中
                                ↓
                    匹配 → 将 A 记录的 IP 直接替换为自定义 IP
                    不匹配 → 原样放行
```

NetGuard 本身通过 VPN 拦截所有网络流量，DNS 响应在 C 层 `dns.c` 的 `parse_dns_response()` 中被解析。我们在解析到 A 记录（IPv4）时，通过 JNI 回调 Java 层的 `getHostRedirect()` 方法查询映射表，如果命中则直接用 `memcpy` 替换响应包中的 IP 地址。整个过程在内存中完成，性能开销极小。

### 数据来源
- **手动添加**：用户在界面中逐条输入域名和 IP
- **GitHub520 自动更新**：从 `https://raw.hellogithub.com/hosts` 拉取最新的 GitHub 相关 hosts 记录，一键导入

---

## 二、文件修改清单

| 类型 | 文件路径 | 改动说明 |
|------|---------|---------|
| 修改 | `app/src/main/jni/netguard/netguard.h` | 添加 `get_host_redirect` 函数声明 |
| 修改 | `app/src/main/jni/netguard/netguard.c` | 添加 `get_host_redirect` JNI 回调实现 |
| 修改 | `app/src/main/jni/netguard/dns.c` | 在 DNS A 记录解析处注入 IP 重写逻辑 |
| 修改 | `app/src/main/java/.../DatabaseHelper.java` | 新增 `virtual_hosts` 表 + CRUD 方法，DB 版本 22→23 |
| 修改 | `app/src/main/java/.../ServiceSinkhole.java` | 新增 `mapHostsRedirect`、`prepareVirtualHosts()`、`getHostRedirect()` |
| 修改 | `app/src/main/java/.../ActivityMain.java` | 添加菜单项跳转到 `ActivityVirtualHosts` |
| 修改 | `app/src/main/AndroidManifest.xml` | 注册 `ActivityVirtualHosts` |
| 修改 | `app/src/main/res/menu/main.xml` | 添加 `menu_virtual_hosts` 菜单项 |
| 修改 | `app/src/main/res/values/strings.xml` | 添加英文字符串资源 |
| 修改 | `app/src/main/res/values-zh-rCN/strings.xml` | 添加中文字符串资源 |
| **新增** | `app/src/main/java/.../ActivityVirtualHosts.java` | Virtual Hosts 管理界面 Activity |
| **新增** | `app/src/main/java/.../AdapterVirtualHosts.java` | 列表适配器（CursorAdapter） |
| **新增** | `app/src/main/res/layout/virtual_hosts.xml` | 主界面布局（总开关 + 列表） |
| **新增** | `app/src/main/res/layout/virtual_host_item.xml` | 列表项布局 |
| **新增** | `app/src/main/res/layout/virtual_host_add.xml` | 添加条目对话框布局 |
| **新增** | `app/src/main/res/menu/virtual_hosts.xml` | Activity 菜单（添加/更新/清除） |

---

## 三、各文件详细改动

### 3.1 C 层 — `netguard.h`

**位置**：第 528 行（`is_domain_blocked` 声明之后）

```c
// 新增声明
void get_host_redirect(const struct arguments *args, const char *name,
                       char *redirect, size_t redirect_len);
```

---

### 3.2 C 层 — `netguard.c`

**位置**：第 693 行（`is_domain_blocked` 函数之后，`midGetUidQ` 之前）

```c
static jmethodID midGetHostRedirect = NULL;

void get_host_redirect(const struct arguments *args, const char *name,
                       char *redirect, size_t redirect_len) {
    *redirect = 0;

    jclass clsService = (*args->env)->GetObjectClass(args->env, args->instance);
    ng_add_alloc(clsService, "clsService");

    const char *signature = "(Ljava/lang/String;)Ljava/lang/String;";
    if (midGetHostRedirect == NULL)
        midGetHostRedirect = jniGetMethodID(args->env, clsService,
                                            "getHostRedirect", signature);

    jstring jname = (*args->env)->NewStringUTF(args->env, name);
    ng_add_alloc(jname, "jname");

    jstring jredirect = (*args->env)->CallObjectMethod(
            args->env, args->instance, midGetHostRedirect, jname);
    jniCheckException(args->env);

    if (jredirect != NULL) {
        const char *rr = (*args->env)->GetStringUTFChars(args->env, jredirect, NULL);
        if (rr != NULL) {
            strncpy(redirect, rr, redirect_len - 1);
            redirect[redirect_len - 1] = 0;
            (*args->env)->ReleaseStringUTFChars(args->env, jredirect, rr);
        }
        (*args->env)->DeleteLocalRef(args->env, jredirect);
    }

    (*args->env)->DeleteLocalRef(args->env, jname);
    (*args->env)->DeleteLocalRef(args->env, clsService);
    ng_delete_alloc(jname, __FILE__, __LINE__);
    ng_delete_alloc(clsService, __FILE__, __LINE__);

    if (*redirect)
        log_android(ANDROID_LOG_INFO, "Host redirect %s -> %s", name, redirect);
}
```

**工作流程**：
1. 通过 JNI 调用 Java 层 `ServiceSinkhole.getHostRedirect(String name)`
2. 返回 `null` 表示不重定向，返回 IP 字符串表示需要重定向
3. 将结果写入 `redirect` 缓冲区

---

### 3.3 C 层 — `dns.c`

**位置**：`parse_dns_response()` 函数中，第 151-162 行（在 `dns_resolved()` 调用之前）

```c
// Virtual Hosts: check if domain should be redirected
char redirect[INET6_ADDRSTRLEN + 1];
get_host_redirect(args, qname, redirect, sizeof(redirect));
if (*redirect && qtype == DNS_QTYPE_A) {
    struct in_addr raddr;
    if (inet_pton(AF_INET, redirect, &raddr) == 1) {
        memcpy((void *) (data + off), &raddr, sizeof(raddr));
        log_android(ANDROID_LOG_WARN,
                    "DNS redirect %s -> %s (was %s)",
                    qname, redirect, rd);
        inet_ntop(AF_INET, data + off, rd, sizeof(rd));
    }
}
```

**关键细节**：
- 仅对 `DNS_QTYPE_A`（IPv4）记录进行重写
- 使用 `inet_pton` 将 IP 字符串转为二进制，`memcpy` 直接覆盖响应包中的 rdata
- 替换后重新 `inet_ntop` 更新 `rd` 变量，确保后续 `dns_resolved` 记录的是替换后的 IP

---

### 3.4 Java 层 — `DatabaseHelper.java`

#### 3.4.1 DB 版本升级
```java
// 22 → 23
private static final int DB_VERSION = 23;
```

#### 3.4.2 新增表创建方法
```java
private void createTableVirtualHosts(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE virtual_hosts (" +
            " ID INTEGER PRIMARY KEY AUTOINCREMENT" +
            ", hostname TEXT NOT NULL" +      // 域名
            ", ip TEXT NOT NULL" +             // 目标 IP
            ", enabled INTEGER NOT NULL DEFAULT 1" +  // 是否启用
            ", source TEXT DEFAULT 'manual'" + // 来源：manual / github520
            ", time INTEGER NOT NULL" +        // 创建/更新时间
            ");");
    db.execSQL("CREATE UNIQUE INDEX idx_virtual_hosts ON virtual_hosts(hostname)");
}
```

#### 3.4.3 onCreate 中调用
```java
createTableApp(db);
createTableVirtualHosts(db);  // ← 新增
```

#### 3.4.4 onUpgrade 中添加迁移
```java
if (oldVersion < 23) {
    createTableVirtualHosts(db);
    oldVersion = 23;
}
```

#### 3.4.5 新增 CRUD 方法

| 方法 | 说明 |
|------|------|
| `insertVirtualHost(hostname, ip, enabled, source)` | 插入/替换单条记录 |
| `deleteVirtualHost(id)` | 按 ID 删除 |
| `setVirtualHostEnabled(id, enabled)` | 切换启用状态 |
| `getVirtualHosts()` | 获取全部记录（Cursor，供 Adapter 使用） |
| `getEnabledVirtualHosts()` | 获取已启用记录（Map<hostname, ip>，供 Service 使用） |
| `clearVirtualHosts(source)` | 清除记录（source=null 清除全部） |
| `importVirtualHosts(entries, source)` | 批量导入（事务操作，先删后插） |

---

### 3.5 Java 层 — `ServiceSinkhole.java`

#### 3.5.1 新增字段
```java
private Map<String, String> mapHostsRedirect = new HashMap<>();
```

#### 3.5.2 新增 `prepareVirtualHosts()` 方法
```java
private void prepareVirtualHosts() {
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    boolean use_virtual_hosts = prefs.getBoolean("use_virtual_hosts", false);
    if (!use_virtual_hosts) {
        lock.writeLock().lock();
        mapHostsRedirect.clear();
        lock.writeLock().unlock();
        return;
    }
    lock.writeLock().lock();
    try {
        mapHostsRedirect.clear();
        Map<String, String> hosts = DatabaseHelper.getInstance(this).getEnabledVirtualHosts();
        mapHostsRedirect.putAll(hosts);
        Log.i(TAG, mapHostsRedirect.size() + " virtual hosts loaded");
    } finally {
        lock.writeLock().unlock();
    }
}
```

#### 3.5.3 新增 `getHostRedirect()` — JNI 回调入口
```java
// Called from native code
private String getHostRedirect(String name) {
    lock.readLock().lock();
    try {
        return mapHostsRedirect.get(name);
    } finally {
        lock.readLock().unlock();
    }
}
```

#### 3.5.4 调用点
- **VPN 启动时**（filter 块）：`prepareHostsBlocked()` 之后调用 `prepareVirtualHosts()`
- **reload 时**（Command handler）：同上
- **stop 时**：`mapHostsRedirect.clear()`

---

### 3.6 Java 层 — `ActivityVirtualHosts.java`（新增）

完整的管理界面 Activity，功能包括：

| 功能 | 实现 |
|------|------|
| 总开关 | `SwitchCompat`，写入 `SharedPreferences("use_virtual_hosts")`，切换后 reload VPN |
| 手动添加 | AlertDialog，输入域名+IP，调用 `insertVirtualHost()` |
| GitHub520 更新 | AsyncTask 从 `https://raw.hellogithub.com/hosts` 下载并解析，调用 `importVirtualHosts()` |
| 清除全部 | 确认对话框 → `clearVirtualHosts(null)` |
| 列表展示 | ListView + AdapterVirtualHosts（CursorAdapter） |

---

### 3.7 Java 层 — `AdapterVirtualHosts.java`（新增）

继承 `CursorAdapter`，每行显示：
- 域名（粗体）
- IP 地址 + 来源标签（manual / github520）
- 启用/禁用开关
- 删除按钮

---

### 3.8 布局文件（新增）

| 文件 | 说明 |
|------|------|
| `res/layout/virtual_hosts.xml` | 主界面：顶部开关栏 + 提示文字 + ListView |
| `res/layout/virtual_host_item.xml` | 列表项：域名、IP、来源、开关、删除按钮 |
| `res/layout/virtual_host_add.xml` | 添加对话框：域名输入框 + IP 输入框 |

---

### 3.9 菜单文件

#### `res/menu/virtual_hosts.xml`（新增）
- `menu_add_host` — 添加条目（ActionBar 图标）
- `menu_update_github` — 从 GitHub520 更新
- `menu_clear_hosts` — 清除全部

#### `res/menu/main.xml`（修改）
在 `menu_log` 之前添加：
```xml
<item
    android:id="@+id/menu_virtual_hosts"
    android:title="@string/title_virtual_hosts"/>
```

---

### 3.10 AndroidManifest.xml（修改）

在 `ActivityDns` 之前注册：
```xml
<activity
    android:name=".ActivityVirtualHosts"
    android:label="@string/title_virtual_hosts"
    android:parentActivityName=".ActivityMain">
    <meta-data
        android:name="android.support.PARENT_ACTIVITY"
        android:value=".ActivityMain" />
</activity>
```

---

### 3.11 ActivityMain.java（修改）

在 `onOptionsItemSelected` 的 `case R.id.menu_log` 之前添加：
```java
case R.id.menu_virtual_hosts:
    startActivity(new Intent(ActivityMain.this, ActivityVirtualHosts.class));
    return true;
```

---

### 3.12 字符串资源

#### `res/values/strings.xml`（英文）
```xml
<string name="title_virtual_hosts">Virtual Hosts</string>
<string name="vhost_enable">Enable Virtual Hosts</string>
<string name="vhost_hint">Redirect domain DNS resolution to custom IP addresses. Useful for accessing GitHub in China. Tap + to add manually or update from GitHub520.</string>
<string name="vhost_add_title">Add Host Entry</string>
<string name="vhost_hostname_hint">Hostname (e.g. github.com)</string>
<string name="vhost_ip_hint">IP Address (e.g. 140.82.114.4)</string>
<string name="vhost_update_github">Update from GitHub520</string>
<string name="vhost_updating">Updating hosts from GitHub520…</string>
<string name="vhost_updated">Updated %d host entries</string>
```

#### `res/values-zh-rCN/strings.xml`（中文）
```xml
<string name="title_virtual_hosts">虚拟 Hosts</string>
<string name="vhost_enable">启用虚拟 Hosts</string>
<string name="vhost_hint">将域名 DNS 解析重定向到自定义 IP 地址，无需 root。适用于在国内正常访问 GitHub 等网站。点击 + 手动添加，或从 GitHub520 自动更新。</string>
<string name="vhost_add_title">添加 Hosts 条目</string>
<string name="vhost_hostname_hint">域名（如 github.com）</string>
<string name="vhost_ip_hint">IP 地址（如 140.82.114.4）</string>
<string name="vhost_update_github">从 GitHub520 更新</string>
<string name="vhost_updating">正在从 GitHub520 更新 hosts…</string>
<string name="vhost_updated">已更新 %d 条 hosts 记录</string>
```

---

## 四、数据流图

```
用户操作                    Java 层                         C 层 (JNI)
─────────────────────────────────────────────────────────────────────────
                                                    
[开启总开关] ──→ SharedPreferences("use_virtual_hosts")
                        │
[手动添加/GitHub520更新] ──→ DatabaseHelper.insertVirtualHost()
                        │          │
                        │    SQLite virtual_hosts 表
                        │          │
              ServiceSinkhole.reload()
                        │
              prepareVirtualHosts()
                        │
              mapHostsRedirect ← getEnabledVirtualHosts()
                        │
                        │                       DNS 响应到达
                        │                           │
                        │                   parse_dns_response()
                        │                           │
                        │                   get_host_redirect(qname)
                        │                           │
                        ├──── getHostRedirect() ◄───┘
                        │         │
                        │   return mapHostsRedirect.get(name)
                        │         │
                        │         ├── null → 不重定向
                        │         └── "1.2.3.4" → memcpy 替换 A 记录 IP
                        │
                   dns_resolved() → 记录到 DNS 日志
```

---

## 五、使用说明

1. 打开 NetGuard 主界面
2. 点击右上角菜单 → **虚拟 Hosts**（或 **Virtual Hosts**）
3. 开启顶部的 **启用虚拟 Hosts** 开关
4. 点击右上角 **+** 手动添加条目，或点击菜单 **从 GitHub520 更新** 一键导入
5. 确保 NetGuard 的 VPN 处于开启状态
6. 访问 github.com 等网站，DNS 将被自动重定向到可用 IP

### 注意事项
- 需要同时开启 NetGuard 的 VPN 功能（主界面的总开关）
- 仅对 IPv4（A 记录）进行重写，IPv6（AAAA 记录）暂不处理
- GitHub520 的 hosts 数据会定期变化，建议定期点击更新
- 每条记录可单独启用/禁用，不影响其他条目
