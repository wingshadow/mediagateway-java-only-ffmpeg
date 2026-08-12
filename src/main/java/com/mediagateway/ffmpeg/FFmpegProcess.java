package com.mediagateway.ffmpeg;

import com.mediagateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.LogOutputStream;
import org.apache.commons.exec.PumpStreamHandler;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 FFmpeg 进程管理器。
 *
 * <p>
 * 一个 FFmpegProcess 对象对应一个 FFmpeg 子进程，
 * 负责 FFmpeg 进程的启动、运行状态监控以及停止。
 * </p>
 *
 * <p>
 * 当前主要支持两种输出模式：
 * </p>
 *
 * <ul>
 *     <li>HLS：RTSP → FFmpeg → HLS 文件（m3u8 + ts）</li>
 *     <li>FLV：RTSP → FFmpeg → FLV → HTTP</li>
 * </ul>
 *
 * <p>
 * Apache Commons Exec 用于启动和管理 FFmpeg 子进程。
 * </p>
 */
@Slf4j
public class FFmpegProcess {

    /**
     * FFmpeg 配置。
     *
     * <p>
     * 包含 FFmpeg 可执行文件路径、编码器、分辨率、
     * 码率、音频编码器等配置。
     * </p>
     */
    private final GatewayProperties.FFmpegConfig config;

    /**
     * 流 ID。
     *
     * <p>
     * 用于区分不同摄像头/视频流，同时用于日志输出。
     * </p>
     */
    private final String streamId;

    /**
     * Apache Commons Exec 执行器。
     *
     * <p>
     * 用于启动 FFmpeg 子进程，并管理标准输入、标准输出和错误输出。
     * </p>
     */
    private final DefaultExecutor executor;

    /**
     * FFmpeg 进程看门狗。
     *
     * <p>
     * 当前配置为无限超时，
     * FFmpeg 进程不会因为 Commons Exec 的超时机制自动结束。
     * </p>
     */
    private final ExecuteWatchdog watchdog;

    /**
     * FFmpeg 异步执行结果处理器。
     *
     * <p>
     * 用于判断 FFmpeg 是否已经退出，并等待 FFmpeg 进程结束。
     * </p>
     */
    private final DefaultExecuteResultHandler resultHandler;

    /**
     * FFmpeg 是否处于运行状态。
     */
    private volatile boolean running;

    /**
     * 创建一个 FFmpeg 进程管理对象。
     *
     * @param config   FFmpeg 配置
     * @param streamId 流 ID
     */
    public FFmpegProcess(GatewayProperties.FFmpegConfig config, String streamId) {

        this.config = config;
        this.streamId = streamId;

        /*
         * 创建 Commons Exec 执行器。
         */
        this.executor = DefaultExecutor.builder().get();

        /*
         * 不限制 FFmpeg 的退出码。
         *
         * FFmpeg 正常退出时通常返回 0，
         * 但这里由代码自行通过 resultHandler 判断进程状态。
         */
        this.executor.setExitValues(null);

        /*
         * 创建 FFmpeg Watchdog。
         *
         * INFINITE_TIMEOUT 表示不设置自动超时时间。
         */
        this.watchdog = ExecuteWatchdog.builder()
                .setTimeout(Duration.ofMillis(ExecuteWatchdog.INFINITE_TIMEOUT))
                .get();

        /*
         * 将 Watchdog 设置到 Executor。
         */
        this.executor.setWatchdog(watchdog);

        /*
         * 创建异步执行结果处理器。
         */
        this.resultHandler = new DefaultExecuteResultHandler();
    }

    /**
     * 启动 HLS 流。
     *
     * <p>
     * RTSP 摄像头 → FFmpeg → HLS。
     * </p>
     *
     * <p>
     * FFmpeg 会持续读取 RTSP 流，
     * 并按照指定的时间间隔生成 m3u8 和 TS 分片文件。
     * </p>
     *
     * @param sourceRtsp  RTSP 视频源地址
     * @param outputPath  HLS m3u8 输出路径
     * @param resolution  视频分辨率，例如 1920x1080
     * @param bitrate     视频码率，例如 2M
     * @param hlsTime     每个 HLS 分片时长，单位：秒
     * @param hlsListSize m3u8 中保留的 TS 分片数量
     * @return true 启动成功，false 启动失败
     */
    public synchronized boolean startHls(String sourceRtsp, String outputPath, String resolution, String bitrate,
                                         int hlsTime, int hlsListSize) {

        /*
         * 如果当前 FFmpeg 已经运行，
         * 不重复创建 FFmpeg 进程。
         */
        if (isRunning()) {
            return true;
        }

        /*
         * 构建 FFmpeg HLS 命令。
         */
        List<String> cmd = buildHlsCommand(sourceRtsp, outputPath, resolution, bitrate, hlsTime, hlsListSize);
        try {
            /*
             * 第一个参数为 FFmpeg 可执行文件，
             * 后续参数作为 FFmpeg 命令行参数。
             */
            CommandLine commandLine = new CommandLine(cmd.get(0));
            commandLine.addArguments(
                    cmd.subList(1, cmd.size()).toArray(new String[0]),
                    false);

            /*
             * 配置 FFmpeg 标准输出和错误输出。
             *
             * FFmpeg 的运行日志主要输出到 stderr，
             * 因此这里将 stdout 和 stderr 都交给日志处理器。
             */
            executor.setStreamHandler(new PumpStreamHandler(
                    new LogOutputStream() {
                        @Override
                        protected void processLine(String line, int logLevel) {
                            log.debug("[FFmpeg:{}][stdout] {}", streamId, line);
                        }
                    },
                    new LogOutputStream() {
                        @Override
                        protected void processLine(String line, int logLevel) {
                            log.info("[FFmpeg:{}][stderr] {}", streamId, line);
                        }
                    }
            ));

            /*
             * 异步启动 FFmpeg。
             *
             * 这里不会阻塞当前线程，
             * FFmpeg 会作为独立子进程持续运行。
             */
            executor.execute(commandLine, resultHandler);

            /*
             * 等待 1 秒。
             *
             * 主要用于判断 FFmpeg 是否启动后立即退出。
             */
            Thread.sleep(1000);

            /*
             * 如果已经存在执行结果，
             * 说明 FFmpeg 启动后立即退出。
             */
            if (resultHandler.hasResult()) {
                log.error("FFmpeg HLS 启动后立即退出: streamId={}", streamId);
                return false;
            }

            /*
             * 标记 FFmpeg 正在运行。
             */
            running = true;

            log.info("FFmpeg HLS 启动成功: streamId={}", streamId);

            return true;

        } catch (IOException e) {

            /*
             * FFmpeg 启动失败。
             */
            log.error("FFmpeg HLS 启动失败: streamId={}", streamId, e);
            return false;

        } catch (InterruptedException e) {

            /*
             * 当前线程被中断。
             */
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * FLV 推流。
     *
     * <p>
     * RTSP → FFmpeg → FLV → HTTP。
     * </p>
     *
     * <p>
     * FFmpeg 将 FLV 数据写入标准输出 pipe:1，
     * Java 程序再将 FFmpeg 的标准输出转发给 HTTP 客户端。
     * </p>
     *
     * @param sourceRtsp   RTSP 视频源地址
     * @param outputStream HTTP 响应输出流
     * @throws IOException          IO 异常
     * @throws InterruptedException 当前线程被中断
     */
    public void streamFlv(String sourceRtsp, OutputStream outputStream) throws IOException, InterruptedException {

        /*
         * 构建 FFmpeg FLV 命令。
         */
        List<String> cmd = buildFlvCommand(sourceRtsp);

        CommandLine commandLine = new CommandLine(cmd.get(0));

        commandLine.addArguments(
                cmd.subList(1, cmd.size()).toArray(new String[0]),
                false);

        /*
         * 标记 HTTP 客户端是否已经断开。
         *
         * AtomicBoolean 用于保证监控线程和当前线程之间
         * 对状态修改的线程安全。
         */
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        /*
         * 包装 HTTP OutputStream。
         *
         * Commons Exec 在进程结束时可能会关闭输出流，
         * 这里覆盖 close()，只执行 flush，
         * 避免 Commons Exec 关闭 Spring MVC 的 HTTP 响应流。
         */
        OutputStream nonClosingOut = new FilterOutputStream(outputStream) {

            @Override
            public void close() throws IOException {
                flush();
            }
        };

        /*
         * 配置 FFmpeg 输出处理器。
         *
         * FFmpeg 的 stdout：
         *     → nonClosingOut
         *     → HTTP 客户端
         *
         * FFmpeg 的 stderr：
         *     → 日志。
         */
        executor.setStreamHandler(new PumpStreamHandler(
                nonClosingOut,
                new LogOutputStream() {
                    @Override
                    protected void processLine(String line, int logLevel) {
                        log.info("[FFmpeg-FLV:{}] {}", streamId, line);
                    }
                }));

        /*
         * 异步启动 FFmpeg。
         */
        executor.execute(commandLine, resultHandler);

        /*
         * 标记 FFmpeg 已启动。
         */
        running = true;

        /*
         * 创建客户端断开检测线程。
         *
         * HTTP 长连接情况下，
         * FFmpeg 会持续向 OutputStream 写入 FLV 数据。
         *
         * 如果客户端断开，
         * outputStream.flush() 通常会抛出 IOException，
         * 此时停止 FFmpeg。
         */
        Thread disconnectWatcher = new Thread(() -> {
            while (!clientDisconnected.get()) {
                try {
                    Thread.sleep(2000);

                    if (clientDisconnected.get()) {
                        break;
                    }

                    /*
                     * 尝试刷新 HTTP 输出流。
                     *
                     * 如果客户端已经断开，
                     * 通常会在这里触发 IOException。
                     */
                    outputStream.flush();

                } catch (InterruptedException e) {

                    /*
                     * 停止监控线程。
                     */
                    Thread.currentThread().interrupt();
                    break;

                } catch (IOException e) {

                    /*
                     * 客户端连接已经断开。
                     */
                    if (clientDisconnected.compareAndSet(false, true)) {

                        log.info("检测到 FLV 客户端断开: streamId={}", streamId);

                        /*
                         * 客户端已经断开，
                         * 没有必要继续运行 FFmpeg。
                         */
                        stop();
                    }

                    break;
                }
            }
        }, "flv-disconnect-watcher-" + streamId);

        /*
         * 设置为守护线程。
         *
         * 主线程结束后，
         * JVM 不会因为这个监控线程而阻止退出。
         */
        disconnectWatcher.setDaemon(true);
        /*
         * 启动客户端断开检测线程。
         */
        disconnectWatcher.start();

        try {

            /*
             * 等待 FFmpeg 进程结束。
             * 正常情况下：
             * FFmpeg 持续运行
             *       ↓
             * 客户端断开
             *       ↓
             * disconnectWatcher 检测到 IOException
             *       ↓
             * stop()
             *       ↓
             * FFmpeg 退出
             *       ↓
             * waitFor() 返回
             */
            resultHandler.waitFor();

        } finally {

            /*
             * 标记客户端检测结束。
             */
            clientDisconnected.set(true);

            /*
             * 中断客户端检测线程。
             */
            disconnectWatcher.interrupt();

            /*
             * 最终确保 FFmpeg 被停止。
             */
            stop();
        }
    }

    /**
     * 停止 FFmpeg。
     *
     * <p>
     * 通过 Watchdog 强制销毁 FFmpeg 子进程，
     * 然后最多等待 5 秒确认进程结束。
     * </p>
     */
    public synchronized void stop() {

        /*
         * 如果当前没有运行，
         * 不需要重复执行停止操作。
         */
        if (!isRunning()) {
            return;
        }

        log.info("停止 FFmpeg: streamId={}", streamId);

        /*
         * 先更新 Java 层面的运行状态。
         */
        running = false;

        /*
         * 通过 Watchdog 销毁 FFmpeg 子进程。
         */
        if (watchdog != null) {
            watchdog.destroyProcess();
        }

        try {
            if (resultHandler != null) {
                long deadline = System.currentTimeMillis() + 5000;

                /*
                 * 等待 FFmpeg 真正退出。
                 */
                while (!resultHandler.hasResult()
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {

            /*
             * 恢复线程中断状态。
             */
            Thread.currentThread().interrupt();
        }

        log.info("FFmpeg 已停止: streamId={}", streamId);
    }

    /**
     * 判断 FFmpeg 是否正在运行。
     *
     * <p>
     * running 是 Java 层面的运行标志，
     * resultHandler.hasResult() 用于判断 FFmpeg 子进程是否已经结束。
     * </p>
     *
     * @return true 表示 FFmpeg 正在运行
     */
    public boolean isRunning() {

        /*
         * Java 层面没有标记运行，
         * 直接认为 FFmpeg 没有运行。
         */
        if (!running) {
            return false;
        }

        /*
         * 如果已经产生执行结果，
         * 说明 FFmpeg 已经退出。
         */
        return !resultHandler.hasResult();
    }

    /**
     * 构建 FFmpeg HLS 命令。
     *
     * <p>
     * 最终生成的命令逻辑类似：
     * </p>
     *
     * <pre>
     * ffmpeg
     *     -rtsp_transport tcp
     *     -analyzeduration 1000000
     *     -probesize 524288
     *     -i rtsp://...
     *     -c:v ...
     *     -c:a ...
     *     -f hls
     *     -hls_time ...
     *     -hls_list_size ...
     *     -hls_flags delete_segments+append_list
     *     output.m3u8
     * </pre>
     *
     * @param sourceRtsp  RTSP 地址
     * @param outputPath  HLS m3u8 输出路径
     * @param resolution  视频分辨率
     * @param bitrate     视频码率
     * @param hlsTime     HLS 分片时长
     * @param hlsListSize HLS 播放列表大小
     * @return FFmpeg 命令参数列表
     */
    private List<String> buildHlsCommand(String sourceRtsp,String outputPath,String resolution,String bitrate,
                                         int hlsTime,int hlsListSize) {
        String binPath = FFmpegManager.resolveBinPath(config.getBinPath());
        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);

        /*
         * RTSP 使用 TCP 传输。
         *
         * 相比 UDP，TCP 在网络不稳定环境下通常更可靠，
         * 尤其适合摄像头 RTSP 拉流。
         */
        cmd.add("-rtsp_transport");
        cmd.add("tcp");

        /*
         * FFmpeg 分析输入流的最大时间。
         */
        cmd.add("-analyzeduration");
        cmd.add("1000000");

        /*
         * FFmpeg 探测输入流的数据大小。
         */
        cmd.add("-probesize");
        cmd.add("524288");

        /*
         * 输入 RTSP 地址。
         */
        cmd.add("-i");
        cmd.add(sourceRtsp);

        /*
         * 判断是否使用原始码流复制。
         *
         * videoCodec = copy：
         *
         * RTSP → FFmpeg → HLS
         *
         * 不进行重新编码，可以显著降低 CPU 使用率。
         */
        boolean copy = "copy".equalsIgnoreCase(config.getVideoCodec());

        if (copy) {

            /*
             * 视频直接复制，不重新编码。
             */
            cmd.add("-c:v");
            cmd.add("copy");

            /*
             * 音频直接复制，不重新编码。
             */
            cmd.add("-c:a");
            cmd.add("copy");

        } else {

            /*
             * 指定视频编码器。
             *
             * 例如：
             * libx264
             */
            cmd.add("-c:v");
            cmd.add(config.getVideoCodec());

            /*
             * 设置视频分辨率。
             *
             * 例如：
             * 1920x1080
             */
            cmd.add("-s");
            cmd.add(resolution);

            /*
             * 设置视频码率。
             *
             * 例如：
             * 2M
             */
            cmd.add("-b:v");
            cmd.add(bitrate);

            /*
             * 设置编码预设。
             *
             * 例如：
             * ultrafast / veryfast / medium
             */
            cmd.add("-preset");
            cmd.add(config.getPreset());

            /*
             * 设置音频编码器。
             */
            cmd.add("-c:a");
            cmd.add(config.getAudioCodec());

            /*
             * 设置音频码率。
             */
            cmd.add("-b:a");
            cmd.add(config.getAudioBitrate());
        }

        /*
         * 指定输出格式为 HLS。
         */
        cmd.add("-f");
        cmd.add("hls");

        /*
         * 每个 TS 分片的目标时长。
         */
        cmd.add("-hls_time");
        cmd.add(String.valueOf(hlsTime));

        /*
         * m3u8 播放列表中保留的分片数量。
         */
        cmd.add("-hls_list_size");
        cmd.add(String.valueOf(hlsListSize));

        /*
         * HLS 分片管理策略：
         *
         * delete_segments：
         * 删除已经不在播放列表中的旧 TS 文件。
         *
         * append_list：
         * 追加更新播放列表。
         */
        cmd.add("-hls_flags");
        cmd.add("delete_segments+append_list");

        /*
         * 最终输出 m3u8 文件。
         */
        cmd.add(outputPath);

        return cmd;
    }

    /**
     * 构建 FFmpeg FLV 命令。
     *
     * <p>
     * FLV 模式与 HLS 最大的区别是：
     * <p>
     * HLS：
     * FFmpeg → m3u8 + TS 文件
     * <p>
     * FLV：
     * FFmpeg → pipe:1 → Java OutputStream → HTTP 客户端
     * </p>
     *
     * @param sourceRtsp RTSP 地址
     * @return FFmpeg 命令参数列表
     */
    private List<String> buildFlvCommand(String sourceRtsp) {
        String binPath = FFmpegManager.resolveBinPath(config.getBinPath());

        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);
        cmd.add("-rtsp_transport");
        cmd.add("tcp");

        /*
         * 设置输入流分析时间。
         */
        cmd.add("-analyzeduration");
        cmd.add("1000000");

        /*
         * 设置输入流探测大小。
         */
        cmd.add("-probesize");
        cmd.add("524288");

        /*
         * RTSP 输入地址。
         */
        cmd.add("-i");
        cmd.add(sourceRtsp);

        /*
         * 判断是否直接复制视频码流。
         */
        boolean copy = "copy".equalsIgnoreCase(config.getVideoCodec());
        if (copy) {
            /*
             * 视频直接复制。
             */
            cmd.add("-c:v");
            cmd.add("copy");

            /*
             * 音频直接复制。
             */
            cmd.add("-c:a");
            cmd.add("copy");

        } else {
            /*
             * 视频编码器。
             */
            cmd.add("-c:v");
            cmd.add(config.getVideoCodec());

            /*
             * 视频分辨率。
             */
            cmd.add("-s");
            cmd.add(config.getResolution());

            /*
             * 视频码率。
             */
            cmd.add("-b:v");
            cmd.add(config.getBitrate());

            /*
             * 编码速度预设。
             */
            cmd.add("-preset");
            cmd.add(config.getPreset());

            /*
             * 音频编码器。
             */
            cmd.add("-c:a");
            cmd.add(config.getAudioCodec());

            /*
             * 音频码率。
             */
            cmd.add("-b:a");
            cmd.add(config.getAudioBitrate());
        }

        /*
         * 指定输出格式为 FLV。
         */
        cmd.add("-f");
        cmd.add("flv");

        /*
         * pipe:1 表示将 FLV 数据输出到标准输出 stdout。
         *
         * Java Commons Exec 会接收 stdout，
         * 然后通过 PumpStreamHandler 写入 HTTP OutputStream。
         */
        cmd.add("pipe:1");
        return cmd;
    }
}