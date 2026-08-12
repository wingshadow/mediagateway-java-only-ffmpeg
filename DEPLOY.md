# MediaGateway 部署文档

## 一、安装 JDK 8

1. 下载并安装 Oracle JDK 8u202，默认路径：

```text
C:\Program Files\Java\jdk1.8.0_202
```

2. 配置环境变量（可选）：

```text
JAVA_HOME = C:\Program Files\Java\jdk1.8.0_202
Path 追加 %JAVA_HOME%\bin
```

3. 验证：

```cmd
java -version
```

## 二、NSSM 注册为 Windows 服务

> 目标系统为 Windows 7 时，必须使用 **NSSM 2.24**（2.25+ 不再支持 Win7）。  
> 下载地址：https://nssm.cc/release/nssm-2.24.zip

1. 将 `nssm.exe`（2.24 版本）与 `MediaGateway.jar` 放在同一目录。
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

> 必须以管理员身份运行。

## 三、在浏览器中添加摄像头流

不需要 curl，直接在浏览器地址栏输入以下地址并回车：

```text
http://localhost:9080/api/stream/add?name=camera01&rtsp=rtsp://admin:rykj2808@192.168.8.88:554/stream2&channel=1
```

> 如果 RTSP 地址里包含 `&` 符号，需要把 `&` 改成 `%26`，否则浏览器会把它当成另一个参数。

浏览器会显示一段 JSON，找到其中的 `hls` 字段，例如：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "name": "camera01",
      "hls": "http://127.0.0.1:9080/hls/camera01/index.m3u8"
    }
  ]
}
```

## 四、播放视频

1. 双击打开项目根目录下的 `player.html`。
2. 把上一步复制的 `hls` 完整地址粘贴到地址栏。
3. 点击 **播放**。
