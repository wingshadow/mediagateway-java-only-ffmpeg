## HTTP-FLV 推流实现原理

HTTP-FLV 使用 Spring Boot 的 `StreamingResponseBody` 实现实时流式输出。

客户端请求：

```text
GET /api/flv/{streamId}.flv
```

服务器不会先生成一个完整的 FLV 文件，而是：

1. 启动 FFmpeg 拉取 RTSP。
2. FFmpeg 将 FLV 数据输出到 stdout。
3. Java 读取 FFmpeg stdout。
4. 将读取到的 FLV 二进制数据写入 HTTP Response。
5. 浏览器通过 flv.js 持续接收并播放。
6. 客户端断开后停止 FFmpeg，释放资源。

整体数据流：

```text
摄像头 RTSP
     │
     ▼
┌─────────────┐
│   FFmpeg    │
│             │
│ RTSP → FLV  │
└──────┬──────┘
       │
       │ stdout
       │ FLV 二进制数据
       ▼
┌─────────────────────┐
│ process.getInputStream()
└──────────┬──────────┘
           │
           │ Java 读取
           ▼
      byte[] buffer
           │
           ▼
┌─────────────────────┐
│ outputStream.write()
└──────────┬──────────┘
           │
           │ HTTP Response
           ▼
        浏览器
           │
           ▼
         flv.js
           │
           ▼
       MSE 播放
```

### 1. StreamingResponseBody

代码入口：

```java
StreamingResponseBody body = outputStream -> {
```

`outputStream` 不需要手动创建。

`StreamingResponseBody` 是 Spring MVC 提供的接口：

```java
@FunctionalInterface
public interface StreamingResponseBody {

    void writeTo(OutputStream outputStream) throws IOException;

}
```

Spring 在执行 HTTP 请求时，会自动将当前 HTTP Response 的 `OutputStream` 传入。

因此：

```java
outputStream.write(buffer, 0, bytesRead);
outputStream.flush();
```

实际上是在直接向 HTTP 客户端发送数据。

可以理解为：

```text
outputStream
     │
     └── Spring MVC HTTP Response 输出流
                 │
                 ▼
              浏览器
```

---

### 2. FFmpeg stdout 和 stderr 必须分开

启动 FFmpeg 时：

```java
ProcessBuilder pb = new ProcessBuilder(cmd);

// 不合并 stderr 到 stdout
pb.redirectErrorStream(false);

process = pb.start();
```

FFmpeg 通常有两个输出流：

```text
FFmpeg
  │
  ├── stdout
  │      │
  │      └── FLV 二进制数据
  │
  └── stderr
         │
         └── FFmpeg 日志
```

因此不能使用：

```java
pb.redirectErrorStream(true);
```

否则 stdout 和 stderr 会混合：

```text
FLV二进制数据 + FFmpeg日志
             │
             ▼
        HTTP Response
             │
             ▼
        FLV数据损坏
```

HTTP-FLV 必须保证：

```text
stdout = FLV 数据
stderr = 日志
```

---

### 3. 单独线程读取 FFmpeg stderr

FFmpeg 的日志从 stderr 输出，因此需要单独线程持续读取：

```java
final Process finalProcess = process;

Thread stderrThread = new Thread(() -> {

    try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(
                    finalProcess.getErrorStream(),
                    StandardCharsets.UTF_8))) {

        String line;

        while ((line = reader.readLine()) != null) {
            log.info("[FFmpeg-FLV:{}] {}", streamId, line);
        }

    } catch (IOException e) {
        // FFmpeg 进程结束后，stderr 流关闭，正常忽略
    }

}, "ffmpeg-flv-log-" + streamId);

stderrThread.setDaemon(true);
stderrThread.start();
```

这样形成：

```text
FFmpeg stderr
      │
      ▼
stderrThread
      │
      ▼
BufferedReader
      │
      ▼
log.info()
```

为什么必须读取？

如果 Java 长时间不读取 FFmpeg stderr，操作系统管道缓冲区可能被写满：

```text
FFmpeg
  │
  │ 不断输出日志
  ▼
stderr buffer
  │
  │ 缓冲区满
  ▼
FFmpeg write 阻塞
  │
  ▼
FFmpeg处理停止
  │
  ▼
视频流卡死
```

因此，即使项目不需要 FFmpeg 日志，也建议持续消费 stderr。

---

### 4. FFmpeg stdout 是真正的视频数据

获取 FFmpeg stdout：

```java
InputStream stdout = process.getInputStream();
```

这里的 `stdout` 不是普通文本，而是：

```text
FFmpeg 输出的 FLV 二进制数据
```

然后循环读取：

```java
byte[] buffer = new byte[4096];
int bytesRead;

while ((bytesRead = stdout.read(buffer)) != -1) {

    if (clientDisconnected.get()) {
        break;
    }

    outputStream.write(buffer, 0, bytesRead);
    outputStream.flush();
}
```

数据流向：

```text
FFmpeg stdout
     │
     ▼
stdout.read(buffer)
     │
     ▼
Java buffer
     │
     ▼
outputStream.write()
     │
     ▼
HTTP Response
     │
     ▼
浏览器
```

这里没有生成中间 FLV 文件。

属于：

```text
实时读取
    ↓
实时发送
```

因此特别适合实时视频播放。

---

### 5. 为什么使用 4096 字节 buffer

```java
byte[] buffer = new byte[4096];
```

每次从 FFmpeg stdout 读取最多 4096 字节。

这只是数据搬运缓冲区，并不代表：

```text
一个 FLV 数据包 = 4096 字节
```

实际读取长度由：

```java
bytesRead
```

决定。

因此必须使用：

```java
outputStream.write(buffer, 0, bytesRead);
```

而不是：

```java
outputStream.write(buffer);
```

例如：

```text
buffer大小 = 4096
实际读取 = 1500
```

那么只能发送：

```text
前1500字节
```

所以：

```java
outputStream.write(buffer, 0, bytesRead);
```

是正确方式。

---

### 6. `flush()` 的作用

每次写入 HTTP Response 后：

```java
outputStream.flush();
```

用于尽量让数据及时发送给客户端。

整体过程：

```text
FFmpeg产生FLV数据
       ↓
Java读取
       ↓
outputStream.write()
       ↓
outputStream.flush()
       ↓
HTTP客户端
```

这样可以减少数据长时间积累造成的播放延迟。

不过需要注意：

> `flush()` 并不是严格意义上的 TCP 客户端断开检测机制。

真正可靠的断开异常通常更容易在：

```java
outputStream.write(...)
```

时通过：

```text
IOException
```

体现。

因此代码中的 `disconnectWatcher` 属于辅助检测机制。

---

### 7. 客户端断开状态

代码使用：

```java
AtomicBoolean clientDisconnected = new AtomicBoolean(false);
```

保存客户端连接状态。

因为这个状态会被多个线程访问：

```text
StreamingResponseBody线程
          │
          ├── 读取 FFmpeg stdout
          │
          └── 写 HTTP Response

disconnectWatcher线程
          │
          └── 检测客户端连接
```

所以使用：

```java
AtomicBoolean
```

而不是普通：

```java
boolean
```

状态变化：

```text
false
  │
  │ 客户端正常播放
  ▼
false
  │
  │ 客户端断开
  ▼
true
```

---

### 8. 客户端断开检测线程

代码：

```java
final OutputStream finalOutputStream = outputStream;

Thread disconnectWatcher = new Thread(() -> {

    while (!clientDisconnected.get() && finalProcess.isAlive()) {

        try {

            Thread.sleep(2000);

            if (clientDisconnected.get()) {
                break;
            }

            finalOutputStream.flush();

        } catch (InterruptedException e) {
            break;

        } catch (IOException e) {

            clientDisconnected.set(true);

            log.info("检测到客户端断开连接: streamId={}", streamId);

            finalProcess.destroy();

            break;
        }
    }

}, "flv-disconnect-watcher-" + streamId);

disconnectWatcher.setDaemon(true);
disconnectWatcher.start();
```

作用：

```text
每2秒
   ↓
尝试 flush HTTP Response
   ↓
正常？
 ┌──────┴──────┐
 是            否
 ↓              ↓
继续           IOException
                ↓
        clientDisconnected=true
                ↓
          FFmpeg.destroy()
```

这样可以避免浏览器关闭后 FFmpeg 继续占用资源。

---

### 9. 客户端断开后的资源释放

当客户端关闭页面：

```text
浏览器关闭
    ↓
HTTP连接断开
    ↓
outputStream.write/flush异常
    ↓
clientDisconnected = true
    ↓
FFmpeg.destroy()
```

最终进入：

```java
finally
```

统一清理：

```java
finally {

    clientDisconnected.set(true);

    if (process != null) {

        process.destroy();

        try {

            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    log.info("FFmpeg FLV 进程已停止: streamId={}", streamId);
}
```

FFmpeg 的停止策略：

```text
process.destroy()
       │
       ▼
等待最多3秒
       │
       ├── FFmpeg正常退出 → 结束
       │
       └── 仍未退出
              │
              ▼
   process.destroyForcibly()
              │
              ▼
          强制结束
```

这样可以避免 FFmpeg 成为僵尸进程。

---

### 10. 完整生命周期

一次 FLV 播放请求的完整生命周期如下：

```text
客户端
  │
  │ GET /api/flv/camera01.flv
  ▼
FlvController
  │
  ▼
StreamingResponseBody
  │
  ▼
启动 FFmpeg
  │
  ├──────────────────────┐
  │                      │
  ▼                      ▼
stdout                 stderr
  │                      │
  │ FLV数据               │ FFmpeg日志
  ▼                      ▼
Java读取                日志线程
  │
  ▼
outputStream.write()
  │
  ▼
HTTP Response
  │
  ▼
flv.js
  │
  ▼
浏览器播放
```

客户端关闭：

```text
浏览器关闭
    │
    ▼
HTTP连接断开
    │
    ├───────────────┐
    ▼               ▼
write/flush异常   watcher检测
    │               │
    └───────┬───────┘
            ▼
clientDisconnected = true
            │
            ▼
     FFmpeg.destroy()
            │
            ▼
       等待3秒退出
            │
            ├── 正常退出
            │
            └── 未退出
                  │
                  ▼
         destroyForcibly()
```

---

### 11. HLS 和 HTTP-FLV 的区别

当前 MediaGateway 同时支持两种视频输出方式。

#### HLS

```text
RTSP
 ↓
FFmpeg
 ↓
HLS
 ↓
.m3u8 + .ts
 ↓
Spring Boot 静态资源
 ↓
多个客户端
```

特点：

- 添加流后立即启动 FFmpeg。
- 持续生成 HLS 文件。
- 多个客户端可以共享同一套 HLS 文件。
- 适合多个用户同时观看。
- 会产生磁盘 I/O。
- 延迟通常高于 HTTP-FLV。

#### HTTP-FLV

```text
客户端请求
    ↓
启动 FFmpeg
    ↓
RTSP → FLV
    ↓
stdout
    ↓
HTTP Response
    ↓
客户端
```

特点：

- 客户端请求时才启动 FFmpeg。
- 不生成 FLV 文件。
- 客户端断开后停止 FFmpeg。
- 单个客户端对应一个 FFmpeg 推流进程。
- 节省长期运行时的资源。
- 延迟通常低于 HLS。
- 多客户端同时观看时会产生多个 FFmpeg 进程。

因此当前设计为：

```text
HLS
  = 持续转码 + 文件共享

HTTP-FLV
  = 按需启动 + 实时 HTTP 流
```

---

### 12. 当前设计的核心原则

MediaGateway 的视频流处理遵循以下原则：

```text
HLS：
    添加流
       ↓
    启动 FFmpeg
       ↓
    持续生成 HLS
       ↓
    多客户端共享

HTTP-FLV：
    客户端请求
       ↓
    启动 FFmpeg
       ↓
    stdout → HTTP
       ↓
    客户端断开
       ↓
    停止 FFmpeg
```

核心目标是：

> **HLS 用于共享和持续播放，HTTP-FLV 用于低延迟按需播放。**

同时：

> **FFmpeg 进程的生命周期必须与视频流生命周期绑定，避免客户端退出后 FFmpeg 继续占用 CPU、网络和摄像头连接。**