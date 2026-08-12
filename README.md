# MediaGateway (Java/SpringBoot版)

视频流网关服务 - 管理 RTSP 摄像头流，使用 FFmpeg 转码输出 **HLS** 和 **HTTP-FLV** 两种格式，通过 Spring Boot 静态资源和 StreamingResponseBody 提供播放地址。

## 架构

```
                              ┌──→ FFmpeg 转码 ──→ 本地 HLS 切片(hls/<streamId>/)
                              │                        │
摄像头(RTSP) ──→ MediaGateway ─┤                        └── Spring Boot /hls/<streamId>/index.m3u8
                              │
                              └──→ FFmpeg 按需推流 ──→ HTTP-FLV 流(pipe:1)
                                                       │
                                                       └── Spring Boot /flv/<streamId>.flv
```

- **HLS**：添加流后立即启动 FFmpeg 转码，持续生成 `.m3u8` + `.ts` 切片文件，适合多客户端共享。
- **HTTP-FLV**：按需推流，客户端请求 `/flv/{streamId}.flv` 时才启动 FFmpeg，客户端断开自动停止 FFmpeg，节省资源。

> WebRTC 输出当前不再支持，对应字段返回 `null`。

## 技术栈

- JDK 8
- Spring Boot 2.7.18
- Maven 3.6+
- FFmpeg
- Apache Commons Exec 1.4.0（进程管理）

## FFmpeg 进程管理（Apache Commons Exec）

项目使用 [Apache Commons Exec](https://commons.apache.org/proper/commons-exec/) 替代原生 `ProcessBuilder` 管理 FFmpeg 子进程，核心优势：

### 1. 进程管理更可靠

| 特性 | ProcessBuilder | Commons Exec |
|---|---|---|
| 强制杀死进程 | `process.destroy()` 只发 SIGTERM，子进程可能残留 | `watchdog.destroyProcess()` 确保进程终止 |
| 超时控制 | 需自己写计时线程 | `ExecuteWatchdog` 内置超时杀进程 |
| 退出码检查 | 需手动 `waitFor()` + 判断 | `setExitValues()` 声明可接受退出码，不匹配自动抛异常 |
| 进程存活检测 | `process.isAlive()` 某些 JVM 实现不可靠 | `watchdog.isWatching()` 稳定可靠 |

### 2. 流处理开箱即用

```java
// ProcessBuilder：手动开线程读 stdout/stderr，否则进程会阻塞挂死
new Thread(() -> { while((line = reader.readLine()) != null) {...} }).start();

// Commons Exec：PumpStreamHandler 自动泵流，LogOutputStream 逐行回调
PumpStreamHandler sh = new PumpStreamHandler(logOut, logErr);
executor.setStreamHandler(sh);
```

不用自己管理线程，不会因为缓冲区满导致 FFmpeg 卡死。

### 3. 异步执行 + 结果回调

```java
DefaultExecuteResultHandler handler = new DefaultExecuteResultHandler();
executor.execute(cmdLine, handler);  // 立即返回，不阻塞
// 之后随时检查
handler.hasResult();      // 是否已结束
handler.getExitValue();   // 退出码
handler.getException();   // 异常
```

ProcessBuilder 的 `execute()` 是阻塞的，异步需自己 `new Thread`。

### 4. 命令行构建更安全

```java
// ProcessBuilder：空格、引号需自己处理，容易出错
processBuilder.command("ffmpeg", "-i", "rtsp://...");

// Commons Exec：CommandLine 自动处理参数转义
CommandLine cmd = new CommandLine("ffmpeg");
cmd.addArgument("-i", false);
cmd.addArgument("rtsp://admin:p@ss word@ip", false);  // 自动处理特殊字符
```

### 5. 对本项目的实际收益

- **FLV 长连接**：客户端断连后 `watchdog.destroyProcess()` 立即杀 FFmpeg，不会残留进程占带宽
- **stderr 日志**：FFmpeg 大量输出到 stderr，`LogOutputStream` 逐行写日志，不用手动开线程
- **stdout 直接写 HTTP 响应**：`PumpStreamHandler` 自动泵流到 `outputStream`，配合 `FilterOutputStream` 防止关闭
- **退出码容错**：`setExitValues(null)` 不检查退出码，FFmpeg 被 kill 时不会抛异常中断流

### 6. 代码示例

```java
// 构建 Executor（Builder 模式，1.4.0 推荐写法）
DefaultExecutor executor = DefaultExecutor.builder().get();
executor.setExitValues(null);  // 不检查退出码

// 创建 Watchdog（无限超时 = 手动控制生命周期）
ExecuteWatchdog watchdog = ExecuteWatchdog.builder()
        .setTimeout(Duration.ofMillis(ExecuteWatchdog.INFINITE_TIMEOUT))
        .get();
executor.setWatchdog(watchdog);

// stdout → HTTP 响应流，stderr → 日志
PumpStreamHandler streamHandler = new PumpStreamHandler(
        nonClosingOut,  // FilterOutputStream 包装，防止关闭 HTTP 流
        new LogOutputStream() {
            @Override
            protected void processLine(String line, int logLevel) {
                log.info("[FFmpeg-FLV:{}] {}", streamId, line);
            }
        });
executor.setStreamHandler(streamHandler);

// 异步执行
DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
executor.execute(cmdLine, resultHandler);

// 客户端断连时杀进程
watchdog.destroyProcess();
```

## 项目结构

```
mediagateway-java/
├── pom.xml                                    # Maven 配置
├── ffmpeg/                                    # FFmpeg 可执行文件（工程内置）
│   ├── ffmpeg.exe
│   ├── ffplay.exe
│   └── ffprobe.exe
├── hls/                                       # HLS 输出目录（运行时自动生成）
├── install-service.bat                        # NSSM 安装为 Windows 服务脚本
├── uninstall-service.bat                      # NSSM 卸载 Windows 服务脚本
├── test-ffmpeg.bat                            # 直接测试 FFmpeg 拉流脚本
├── test-api.bat                               # 测试 MediaGateway API 脚本
├── test-flv-add.bat                           # 测试 FLV 添加摄像头脚本
├── test-api.html                              # 独立 HTML 测试页面
├── player.html                                # 独立播放页面（HLS + FLV）
├── src/main/java/com/mediagateway/
│   ├── MediaGatewayApplication.java           # 启动类
│   ├── config/
│   │   ├── GatewayProperties.java             # 配置属性
│   │   ├── FFmpegConfig.java                  # FFmpeg 配置
│   │   └── WebConfig.java                     # 静态资源映射 + CORS
│   ├── model/
│   │   ├── ApiResponse.java                   # 统一响应
│   │   ├── AddStreamRequest.java              # 添加流请求
│   │   ├── StreamItem.java                    # 流项
│   │   ├── StreamResult.java                  # 流结果
│   │   ├── StreamStatus.java                  # 流状态
│   │   ├── FFmpegParams.java                  # FFmpeg 参数
│   │   └── TranscodeRequest.java              # 转码请求
│   ├── ffmpeg/
│   │   └── FFmpegManager.java                 # FFmpeg 转码进程管理
│   ├── service/
│   │   └── StreamService.java                 # 业务逻辑
│   └── controller/
│       ├── StreamController.java              # HLS API 控制器
│       ├── FlvController.java                 # FLV 推流控制器
│       └── GlobalExceptionHandler.java        # 全局异常处理
└── src/main/resources/
    ├── application.yml                        # 配置文件
    └── static/
        └── player.html                        # 内置播放页面
```

## 前置条件

- JDK 8
- Maven 3.6+
- FFmpeg 可执行文件已内置在工程 `ffmpeg/` 目录中，无需额外安装；也可在 `application.yml` 中修改 `mediagateway.ffmpeg.bin-path` 指向自定义路径

### 安装 JDK 8

项目推荐安装 **Oracle JDK 8u202**（与 NSSM 服务编辑器中填写的 Path 一致）。

1. 下载 JDK 8u202：

```text
https://www.oracle.com/java/technologies/javase/javase8-archive-downloads.html
```

2. 运行安装程序，按提示完成安装，默认路径为：

```text
C:\Program Files\Java\jdk1.8.0_202
```

3. 配置环境变量（可选，推荐配置）：

- 新建系统变量 `JAVA_HOME`，值为：

```text
C:\Program Files\Java\jdk1.8.0_202
```

- 编辑系统变量 `Path`，追加：

```text
%JAVA_HOME%\bin
```

4. 验证安装：

```cmd
java -version
```

看到如下输出即表示成功：

```text
java version "1.8.0_202"
Java(TM) SE Runtime Environment (build 1.8.0_202-b08)
Java HotSpot(TM) 64-Bit Server VM (build 25.202-b08, mixed mode)
```

## 快速开始

```bash
# 编译打包（生成可执行 fat-jar）
mvn clean package -DskipTests

# 运行（开发模式）
java -jar target/MediaGateway.jar

# 或 Maven 开发模式
mvn spring-boot:run
```

## 部署

### 部署目录结构

```
deploy/
├── MediaGateway.jar
├── ffmpeg/
│   ├── ffmpeg.exe
│   ├── ffplay.exe
│   └── ffprobe.exe
├── install-service.bat
├── uninstall-service.bat
└── hls/                  # 运行时自动生成
```

> `ffmpeg/` 目录需与 jar 包在同一目录下，程序通过相对路径查找可执行文件。

### 启动命令

```bash
# 在 jar 所在目录执行
java -jar MediaGateway.jar

# 指定 JVM 内存参数
java -Xms256m -Xmx512m -jar MediaGateway.jar

# 不在 jar 所在目录启动时，需指定工作目录
java -Duser.dir=/path/to/deploy -jar /path/to/deploy/MediaGateway.jar
```

### Windows 服务安装（Windows 7）

项目使用 NSSM 注册为 Windows 服务，目标系统为 Windows 7，需使用 **NSSM 2.24**（新版 NSSM 已放弃 Windows 7 支持）。

1. 将 `nssm.exe`（2.24）放置到系统 PATH 或部署目录。
2. 以**管理员身份**打开 CMD，进入部署目录并执行：

```cmd
nssm install MediaGateway
```

3. 在弹出的 **NSSM service editor** 中填写：

| 字段 | 示例值 |
|------|--------|
| **Path** | `C:\Program Files\Java\jdk1.8.0_202\bin\java.exe` |
| **Startup directory** | `C:\Program Files\Java\jdk1.8.0_202\bin` |
| **Arguments** | `-jar "D:\MediaGateway\MediaGateway.jar"` |
| **Service name** | `MediaGateway` |

> Path 和 Startup directory 按本机 JDK 实际路径填写；Arguments 中的 jar 路径按实际部署目录填写。

4. 点击 **Install service** 完成安装。
5. 启动服务：

```cmd
net start MediaGateway
```

卸载服务：

```cmd
uninstall-service.bat
```

## 配置说明

配置文件：`src/main/resources/application.yml`

```yaml
server:
  port: 9080

mediagateway:
  stream:
    on-demand: true                       # 是否按需拉流
    adaptive:
      enabled: true                       # 是否启用自适应
      check-interval: 10                  # 检查间隔（秒）
      fail-threshold: 3                   # 失败阈值
      success-threshold: 5                # 成功阈值
  ffmpeg:
    enabled: true                         # 是否默认启用 FFmpeg 转码
    bin-path: ffmpeg/ffmpeg.exe           # FFmpeg 可执行文件路径
    video-codec: libx264                  # 视频编码器
    resolution: 640x480                   # 目标分辨率
    bitrate: 500k                         # 目标码率
    preset: ultrafast                     # 编码预设
    audio-codec: aac                      # 音频编码器
    audio-bitrate: 64k                    # 音频码率
  hls:
    output-dir: hls                       # HLS 输出目录
    url-prefix: /hls                      # HLS URL 前缀
    base-url: http://192.168.8.116:9080   # HLS/FLV 对外访问基础 URL
    time: 2                               # 单个切片时长（秒）
    list-size: 5                          # m3u8 播放列表保留切片数
  flv:
    enabled: true                         # 是否启用 HTTP-FLV 推流
```

## API 接口

### 添加流

```
POST /api/stream/add
```

请求体：

```json
{
  "streams": [
    {
      "name": "电梯001",
      "rtsp": "rtsp://admin:123456@192.168.1.100:554/stream1",
      "rtsp_sub": "rtsp://admin:123456@192.168.1.100:554/stream2",
      "ffmpeg": {
        "enabled": true,
        "resolution": "640x480",
        "bitrate": "500k"
      }
    },
    {
      "name": "电梯002",
      "rtsp": "rtsp://admin:123456@192.168.1.101:554/stream1",
      "rtsp_sub": "rtsp://admin:123456@192.168.1.101:554/stream2"
    }
  ]
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "streamId": "电梯001",
      "name": "电梯001",
      "rtsp": "rtsp://admin:123456@192.168.1.100:554/stream1",
      "rtsp_sub": "rtsp://admin:123456@192.168.1.100:554/stream2",
      "hls": "http://127.0.0.1:9080/hls/电梯001/index.m3u8",
      "webrtc": null,
      "http_flv": null,
      "transcoding": true
    }
  ]
}
```

### 删除流

```
DELETE /api/stream/{streamId}
```

### 获取流状态

```
GET /api/stream/{streamId}
```

### 列出所有流

```
GET /api/stream/list
```

### 切换码流

```
POST /api/stream/switch/{streamId}
```

请求体：

```json
{ "target": "sub" }
```

### 动态开启/关闭转码

```
POST /api/stream/transcode/{streamId}
```

请求体：

```json
{
  "enabled": true,
  "resolution": "640x480",
  "bitrate": "500k"
}
```

## FLV 接口

### 添加 FLV 流（POST）

```
POST /api/flv/add
```

注册流信息供 FLV 按需推流，**不启动 HLS 转码**，客户端请求 `.flv` 地址时才启动 FFmpeg。

请求体：

```json
{
  "name": "camera1",
  "rtsp": "rtsp://admin:123456@192.168.1.100:554/stream1",
  "rtsp_sub": "rtsp://admin:123456@192.168.1.100:554/stream2"
}
```

响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "streamId": "camera1",
    "name": "camera1",
    "rtsp": "rtsp://admin:123456@192.168.1.100:554/stream1",
    "rtsp_sub": "rtsp://admin:123456@192.168.1.100:554/stream2",
    "hls": null,
    "webrtc": null,
    "http_flv": "http://192.168.8.116:9080/flv/api/camera1.flv",
    "transcoding": false
  }
}
```

### 添加 FLV 流（GET）

```
GET /flv/add?name=camera1&rtsp=rtsp://...&rtsp_sub=rtsp://...
```

> 如果 RTSP 地址里包含 `&` 符号，需要把 `&` 改成 `%26`。

### 拉取 FLV 流

```
GET /api/flv/{streamId}.flv
```

客户端请求此地址时，后端按需启动 FFmpeg 进程，将 RTSP 转为 FLV 流通过 HTTP 响应体推送。客户端断开连接时自动终止 FFmpeg 进程。

支持的播放方式：

- **浏览器**：使用 [flv.js](https://github.com/bilibili/flv.js) 播放（MSE），内置 `player.html` 已集成
- **ffplay**：`ffplay http://127.0.0.1:9080/api/flv/camera1.flv`
- **VLC**：打开网络串流，输入 FLV 地址

### FLV 断开检测机制

| 场景 | 检测方式 |
|------|----------|
| 前端调用 `stop()` / `flvPlayer.unload()` | HTTP 连接关闭 → 后端 `flush()` 抛 `IOException` → 终止 FFmpeg |
| 浏览器关闭/刷新标签页 | `beforeunload` 事件触发 `stop()` → 同上 |
| 网络中断 | 后端 disconnect-watcher 线程每 2 秒 `flush()` 探测 → 失败则终止 FFmpeg |
| FFmpeg 进程自行退出 | `stdout.read()` 返回 `-1` → 主循环退出 → `finally` 清理 |

## FFmpeg 转码

### 使用方式

**方式一**：添加流时传入 `ffmpeg` 参数（推荐）

```json
{
  "streams": [{
    "name": "摄像头001",
    "rtsp": "rtsp://admin:123456@192.168.1.100:554/stream1",
    "ffmpeg": { "enabled": true, "resolution": "640x480", "bitrate": "500k" }
  }]
}
```

**方式二**：API 动态切换

```bash
# 开启转码
curl -X POST http://localhost:9080/api/stream/transcode/摄像头001 \
  -H "Content-Type: application/json" \
  -d '{"enabled": true, "resolution": "640x480", "bitrate": "500k"}'

# 关闭转码
curl -X POST http://localhost:9080/api/stream/transcode/摄像头001 \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'
```

**方式三**：自适应自动切换

网络不稳定时自动启用转码，网络恢复后自动关闭转码。优先级：FFmpeg 转码 > 双码流切换。

## 自适应策略

| 网络状态 | 当前模式 | 动作 |
|----------|----------|------|
| 不稳定 | 主码流 | 优先启用 FFmpeg 转码，转码不可用则切换子码流 |
| 不稳定 | 子码流 | 启用 FFmpeg 转码 |
| 恢复 | 转码 | 关闭转码，恢复主码流 |
| 恢复 | 子码流 | 切换回主码流 |

## 测试脚本

项目根目录提供了测试脚本和 HTML 页面：

- `test-ffmpeg.bat`：直接测试指定 RTSP 地址是否能被 FFmpeg 正常拉流并输出 HLS。
- `test-api.bat`：调用 MediaGateway API 添加测试摄像头并列出流状态。
- `test-flv-add.bat`：测试 FLV 添加摄像头（POST + GET 两种方式），列出流并打印 FLV 播放地址。
- `test-api.html`：独立 HTML 页面，填写摄像头信息后调用 API 添加流（已开启 CORS，可直接双击打开）。
- `player.html`：内置播放页面，支持 HLS 和 FLV 两种格式播放，也可直接通过 `http://127.0.0.1:9080/player.html` 访问。

默认测试摄像头：

```
rtsp://admin:rykj2808@192.168.8.88:554/stream2&channel=1
```

播放地址示例：

```
HLS: http://127.0.0.1:9080/hls/test-camera/index.m3u8
FLV: http://127.0.0.1:9080/flv/test-camera.flv
```

### GET 方式添加流

除 POST 外，也支持通过 URL 参数快速添加流，**无需 curl，直接复制到浏览器地址栏即可**：

```text
http://localhost:9080/api/stream/add?name=camera01&rtsp=rtsp://admin:rykj2808@192.168.8.88:554/stream2&channel=1
```

> 如果 RTSP 地址里包含 `&` 符号，需要把 `&` 改成 `%26`，否则浏览器会把它当成另一个参数。

浏览器会显示 JSON 结果，复制其中的 `hls` 完整地址，粘贴到 `player.html` 中播放。

等效的 curl 命令：

```bash
curl "http://127.0.0.1:9080/api/stream/add?name=camera01&rtsp=rtsp://admin:123456@192.168.1.100:554/stream1&rtsp_sub=rtsp://admin:123456@192.168.1.100:554/stream2"
```

### HTML 播放

通过浏览器访问 `http://127.0.0.1:9080/player.html`，支持：

- **HLS 模式**：输入 m3u8 地址，使用 hls.js 播放
- **FLV 模式**：输入 FLV 地址，使用 flv.js 播放
- **RTSP 添加**：直接在页面输入摄像头名称和 RTSP 地址，点击"添加并播放"
- **API 响应展示**：显示 API 返回的 JSON 结果

也可直接双击项目根目录下的 `player.html` 打开（自动检测 `file://` 协议并使用服务器地址请求 API）。

```angular2html
List<String> cmd = new ArrayList<>();

// FFmpeg可执行文件路径
// 示例：D:/ffmpeg/bin/ffmpeg.exe
cmd.add(binPath);


// 输入RTSP视频流地址
// 示例：rtsp://192.168.1.100:554/live
cmd.add("-i");
cmd.add(sourceRtsp);


// 视频编码方式
// copy      : 直接复制视频流，不转码，CPU占用低（推荐）
// libx264   : 转H264编码，兼容性好，但消耗CPU
cmd.add("-c:v");
cmd.add(config.getVideoCodec());


// 输出视频分辨率
// 示例：1280x720
// 注意：使用copy模式时不要设置，否则会触发重新编码
cmd.add("-s");
cmd.add(res);


// 视频码率
// 示例：1000k
// 控制视频质量和网络带宽
cmd.add("-b:v");
cmd.add(br);


// 视频编码预设
// 主要用于libx264编码
// ultrafast : 编码最快，CPU最低
// veryfast  : 实时监控推荐
// medium    : 默认质量
cmd.add("-preset");
cmd.add(config.getPreset());


// 音频编码方式
// aac  : 浏览器HLS兼容性最好
// copy : 直接复制音频
cmd.add("-c:a");
cmd.add(config.getAudioCodec());


// 音频码率
// 示例：64k
cmd.add("-b:a");
cmd.add(config.getAudioBitrate());


// 输出格式：HLS
// 生成文件：
// xxx.m3u8  播放列表
// xxx.ts    视频切片
cmd.add("-f");
cmd.add("hls");


// HLS切片时长（秒）
// 每个ts文件约2秒
// 越小延迟越低，但是文件数量增加
cmd.add("-hls_time");
cmd.add("2");


// m3u8文件中保留TS切片数量
// 当前保留5个切片
// 播放缓冲约：2秒 × 5 = 10秒
cmd.add("-hls_list_size");
cmd.add("5");


// 自动删除过期TS文件
// 防止长时间运行导致磁盘占满
cmd.add("-hls_flags");
cmd.add("delete_segments");


// 输出HLS播放列表文件
// 示例：D:/hls/camera001.m3u8
// FFmpeg会同时生成对应.ts文件
cmd.add(outputPath);

```