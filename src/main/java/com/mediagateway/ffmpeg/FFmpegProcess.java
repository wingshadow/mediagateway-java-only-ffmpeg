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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 FFmpeg 进程管理器。
 *
 * <p>
 * 一个 FFmpegProcess 对应一个摄像头视频流，
 * 负责 FFmpeg 进程的启动、运行状态监控、异常检测、自动重启以及停止。
 * </p>
 *
 * <p>
 * 当前支持：
 * </p>
 *
 * <ul>
 *     <li>HLS：RTSP → FFmpeg → m3u8 + ts</li>
 *     <li>FLV：RTSP → FFmpeg → pipe:1 → HTTP</li>
 * </ul>
 *
 * <p>
 * 状态监管统一放在 FFmpegProcess 内部，
 * FFmpegManager 负责管理多个 FFmpegProcess。
 * </p>
 */
@Slf4j
public class FFmpegProcess {

    /**
     * FFmpeg 配置。
     */
    private final GatewayProperties.FFmpegConfig config;
    private final String streamId;
    private volatile DefaultExecutor executor;

    /**
     * FFmpeg 看门狗。
     */
    private volatile ExecuteWatchdog watchdog;

    /**
     * FFmpeg 异步执行结果处理器。
     *
     * <p>
     * 每次启动 FFmpeg 时重新创建。
     * </p>
     */
    private volatile DefaultExecuteResultHandler resultHandler;
    private volatile FFmpegProcessState state = FFmpegProcessState.STOPPED;

    /**
     * 当前 FFmpeg 是否是 HLS 模式。
     */
    private volatile boolean hlsMode;

    /**
     * HLS 输出文件。
     */
    private volatile String hlsOutputPath;

    /**
     * HLS 分片时间。
     */
    private volatile int hlsTime;

    /**
     * FFmpeg 启动时间。
     */
    private volatile long processStartTime;

    /**
     * 最近一次 HLS m3u8 文件更新时间。
     */
    private volatile long lastHlsUpdateTime;

    /**
     * HLS 监控线程是否运行。
     */
    private volatile boolean hlsMonitorRunning;

    /**
     * HLS 监控线程。
     */
    private volatile Thread hlsMonitorThread;

    /**
     * 是否正在自动重启。
     */
    private final AtomicBoolean restarting = new AtomicBoolean(false);

    /**
     * HLS 监控间隔。
     */
    private static final long HLS_MONITOR_INTERVAL_MS = 2000L;

    /**
     * HLS 最大允许无更新时间。
     *
     * <p>
     * 如果 m3u8 超过该时间没有更新，
     * 认为 FFmpeg/HLS 输出异常。
     * </p>
     */
    private static final long HLS_STALE_TIMEOUT_MS = 15000L;

    /**
     * FFmpeg 异常退出后重新启动前的等待时间。
     */
    private static final long RESTART_DELAY_MS = 3000L;

    /**
     * FFmpeg 停止最大等待时间。
     */
    private static final long STOP_TIMEOUT_MS = 5000L;

    /**
     * 创建 FFmpegProcess。
     *
     * @param config   FFmpeg 配置
     * @param streamId 流 ID
     */
    public FFmpegProcess(GatewayProperties.FFmpegConfig config, String streamId) {
        this.config = config;
        this.streamId = streamId;
    }

    /**
     * 启动 HLS。
     *
     * @param sourceRtsp  RTSP 地址
     * @param outputPath  HLS m3u8 输出路径
     * @param resolution  视频分辨率
     * @param bitrate     视频码率
     * @param hlsTime     HLS 分片时间
     * @param hlsListSize HLS 播放列表大小
     * @return true 启动成功
     */
    public synchronized boolean startHls(String sourceRtsp,String outputPath,String resolution,String bitrate,
                                         int hlsTime,int hlsListSize) {

        // 已经运行，不重复启动。
        if (isRunning()) {
            return true;
        }

        // 停止状态下允许重新启动。
        state = FFmpegProcessState.STARTING;
        hlsMode = true;
        hlsStartContext = new HlsStartContext(sourceRtsp,outputPath,resolution,bitrate,hlsTime,hlsListSize);

        hlsOutputPath = outputPath;
        this.hlsTime = hlsTime;

        // 创建新的 FFmpeg 生命周期对象。
        createProcessObjects();
        List<String> cmd = buildHlsCommand(sourceRtsp,outputPath,resolution,bitrate,hlsTime,hlsListSize);
        try {
            CommandLine commandLine = new CommandLine(cmd.get(0));
            commandLine.addArguments(cmd.subList(1, cmd.size()).toArray(new String[0]),false);
            LogOutputStream stdout = new LogOutputStream() {
                @Override
                protected void processLine(String line, int logLevel) {
                    if (line != null && !line.trim().isEmpty()) {
                        log.debug("[FFmpeg:{}][stdout] {}", streamId, line);
                    }
                }
            };
            LogOutputStream stderr = new LogOutputStream() {
                @Override
                protected void processLine(String line, int logLevel) {
                    handleFfmpegLog(line);
                }
            };
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
            processStartTime = System.currentTimeMillis();

            // 异步启动 FFmpeg。
            executor.execute(commandLine, resultHandler);
            Thread.sleep(1000L);
            if (resultHandler.hasResult()) {
                // FFmpeg 异步执行任务的最终结果，说明 FFmpeg 进程已经结束。
                state = FFmpegProcessState.ERROR;
                log.error("FFmpeg HLS 启动后立即退出: streamId={}, exitValue={}", streamId, getExitValue());
                return false;
            }

            // FFmpeg 正常进入运行状态。
            state = FFmpegProcessState.RUNNING;
            lastHlsUpdateTime = 0L;
            updateHlsFileState();
            // 启动 HLS 健康监控
            startHlsMonitor();
            log.info("FFmpeg HLS 启动成功: streamId={}", streamId);
            return true;
        } catch (IOException e) {
            state = FFmpegProcessState.ERROR;
            log.error("FFmpeg HLS 启动失败: streamId={}", streamId, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state = FFmpegProcessState.ERROR;
            log.warn("FFmpeg HLS 启动等待被中断: streamId={}", streamId);
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
     * @param sourceRtsp   RTSP 地址
     * @param outputStream HTTP 输出流
     * @throws IOException          IO 异常
     * @throws InterruptedException 中断异常
     */
    public void streamFlv(String sourceRtsp, OutputStream outputStream) throws IOException, InterruptedException {
        synchronized (this) {
            // 当前已经有 FFmpeg 在运行，不允许重复启动。
            if (isRunning()) {
                throw new IllegalStateException("FFmpeg 已经在运行: streamId=" + streamId);
            }
            state = FFmpegProcessState.STARTING;
            hlsMode = false;
            createProcessObjects();
        }

        List<String> cmd = buildFlvCommand(sourceRtsp);
        CommandLine commandLine = new CommandLine(cmd.get(0));
        commandLine.addArguments(cmd.subList(1, cmd.size()).toArray(new String[0]), false);

        // 客户端是否已经断开。
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        // Commons Exec 不允许关闭 Spring MVC的 HTTP OutputStream。
        OutputStream nonClosingOut = new FilterOutputStream(outputStream) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }
                };

        // FFmpeg stdout → HTTP。FFmpeg stderr → 日志。
        executor.setStreamHandler(new PumpStreamHandler(
                nonClosingOut,
                new LogOutputStream() {
                    @Override
                    protected void processLine(String line, int logLevel) {
                        handleFfmpegLog(line);
                    }
        }));
        try {
            processStartTime = System.currentTimeMillis();

            executor.execute(commandLine, resultHandler);
            state = FFmpegProcessState.RUNNING;
            log.info("FFmpeg FLV 启动成功: streamId={}", streamId);
            Thread disconnectWatcher = new Thread(() -> {
                        while (!clientDisconnected.get()) {
                            try {
                                Thread.sleep(2000L);
                                if (clientDisconnected.get()) {
                                    break;
                                }
                                // 尝试刷新 HTTP 输出流。
                                outputStream.flush();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            } catch (IOException e) {
                                if (clientDisconnected.compareAndSet(false, true)) {
                                    log.info("检测到 FLV 客户端断开: streamId={}", streamId);
                                    stop();
                                }
                                break;
                            }
                        }
                    }, "flv-disconnect-watcher-" + streamId);

            disconnectWatcher.setDaemon(true);
            disconnectWatcher.start();
            // 阻塞进程,等待 FFmpeg 退出。
            resultHandler.waitFor();

        } finally {
            clientDisconnected.set(true);
            stop();
        }
    }

    /**
     * 处理 FFmpeg stderr。
     *
     * <p>
     * stderr 主要用于：
     *
     * <ul>
     *     <li>记录 FFmpeg 日志</li>
     *     <li>识别明显的错误信息</li>
     * </ul>
     *
     * <p>
     * 注意：
     * 不能简单地看到 ERROR 就认为进程已经死亡。
     * FFmpeg 最终是否退出，以 ResultHandler 为准。
     * </p>
     *
     * @param line FFmpeg 日志
     */
    private void handleFfmpegLog(String line) {

        if (line == null || line.trim().isEmpty()) {
            return;
        }

        String message = line.trim();

        //FFmpeg 正常运行状态日志。
        if (message.contains("frame=")
                || message.contains("fps=")
                || message.contains("time=")
                || message.contains("bitrate=")) {

            log.debug("[FFmpeg:{}][stderr] {}", streamId, message);
            return;
        }
        // 常见错误
        if (message.contains("Error")
                || message.contains("error")
                || message.contains("ERROR")
                || message.contains("failed")
                || message.contains("Failed")
                || message.contains("Connection refused")
                || message.contains("Connection timed out")
                || message.contains("Input/output error")) {

            log.error("[FFmpeg:{}][stderr] {}", streamId, message);
            return;
        }
        log.info("[FFmpeg:{}][stderr] {}", streamId, message);
    }

    /**
     * 创建一次新的 FFmpeg 生命周期对象。
     */
    private void createProcessObjects() {
        // 每次启动重新创建 Executor。
        executor = DefaultExecutor.builder().get();

        // 不限制 FFmpeg 退出码。
        executor.setExitValues(null);

        // 创建新的 Watchdog。
        watchdog = ExecuteWatchdog.builder()
                .setTimeout(Duration.ofMillis(ExecuteWatchdog.INFINITE_TIMEOUT))
                .get();

        // 设置 Watchdog。
        executor.setWatchdog(watchdog);
        //每次启动重新创建 ResultHandler。
        resultHandler = new DefaultExecuteResultHandler();
    }

    /**
     * 启动 HLS 监控线程。
     */
    private synchronized void startHlsMonitor() {
        //已经存在监控线程。
        if (hlsMonitorRunning) {
            return;
        }
        hlsMonitorRunning = true;
        hlsMonitorThread = new Thread(this::monitorHls, "ffmpeg-hls-monitor-" + streamId);
        hlsMonitorThread.setDaemon(true);
        hlsMonitorThread.start();
    }

    /**
     * HLS 状态监控。
     *
     * <p>
     * 同时监控：
     *
     * <ol>
     *     <li>FFmpeg 进程是否退出</li>
     *     <li>m3u8 是否持续更新</li>
     * </ol>
     * </p>
     */
    private void monitorHls() {
        log.debug("启动 HLS 状态监控: streamId={}", streamId);
        while (hlsMonitorRunning) {
            try {
                Thread.sleep(HLS_MONITOR_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            // 当前已经不是运行状态。
            if (!isRunning()) {
                continue;
            }

            // 监控 FFmpeg 进程。
            DefaultExecuteResultHandler handler = resultHandler;
            if (handler == null) {
                continue;
            }

            // FFmpeg 已经退出。
            if (handler.hasResult()) {
                state = FFmpegProcessState.ERROR;
                log.error("检测到 FFmpeg 进程退出: streamId={}, exitValue={}", streamId, getExitValue());
                // 主动停止不自动重启。
                if (state != FFmpegProcessState.STOPPING) {
                    restartHls();
                }
                break;
            }
            // 检查 HLS 文件。
            updateHlsFileState();
            // HLS 长时间没有更新。
            if (isHlsStale()) {
                log.error("检测到 HLS 长时间没有更新: streamId={}, outputPath={}", streamId, hlsOutputPath);
                // 设置错误状态。
                state = FFmpegProcessState.ERROR;
                // 停止当前 FFmpeg。
                stopProcessOnly();
                // 自动重启。
                restartHls();
                break;
            }
        }

        hlsMonitorRunning = false;
        log.debug("HLS 状态监控结束: streamId={}", streamId);
    }

    /**
     * 更新 HLS 文件状态。
     */
    private void updateHlsFileState() {
        String outputPath = hlsOutputPath;
        if (outputPath == null || outputPath.trim().isEmpty()) {
            return;
        }
        try {
            Path path = Paths.get(outputPath);
            if (Files.exists(path)) {
                long lastModified = Files.getLastModifiedTime(path).toMillis();
                if (lastModified > lastHlsUpdateTime) {
                    lastHlsUpdateTime = lastModified;
                }
            }
        } catch (Exception e) {
            log.debug("读取 HLS 文件状态失败: streamId={}, path={}", streamId, outputPath, e);
        }
    }

    /**
     * 判断 HLS 是否异常。
     *
     * @return true HLS 长时间没有更新
     */
    private boolean isHlsStale() {
        long now = System.currentTimeMillis();
        // FFmpeg 刚启动，给 HLS 一定生成时间。
        if (lastHlsUpdateTime <= 0L) {
            return now - processStartTime > HLS_STALE_TIMEOUT_MS;
        }
        // m3u8 长时间没有更新。
        return now - lastHlsUpdateTime > HLS_STALE_TIMEOUT_MS;
    }

    /**
     * 自动重启 HLS。
     */
    private void restartHls() {
        // 防止多个线程同时重启。
        if (!restarting.compareAndSet(false, true)) {
            return;
        }
        try {
            state = FFmpegProcessState.RESTARTING;
            log.warn("准备自动重启 FFmpeg HLS: streamId={}", streamId);
            // 等待旧 FFmpeg 释放 RTSP 连接。
            Thread.sleep(RESTART_DELAY_MS);

            // 如果已经被主动停止，不再重启。
            if (state == FFmpegProcessState.STOPPING) {
                return;
            }

            HlsStartContext context = hlsStartContext;
            if (context == null) {
                state = FFmpegProcessState.ERROR;
                log.error("无法自动重启 HLS，缺少启动参数: streamId={}", streamId);
                return;
            }
            log.info("自动重启 FFmpeg HLS: streamId={}", streamId);
            startHls(context.sourceRtsp, context.outputPath, context.resolution, context.bitrate, context.hlsTime,
                    context.hlsListSize);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            restarting.set(false);
        }
    }

    /**
     * 停止 FFmpeg。
     *
     * <p>
     * 用户主动调用 stop() 时，
     * 不触发自动重启。
     * </p>
     */
    public synchronized void stop() {
        /*
         * 先设置 STOPPING。
         *
         * 必须在停止监控之前设置，
         * 防止监控线程判断为异常退出后自动重启。
         */
        state = FFmpegProcessState.STOPPING;
        stopHlsMonitor();
        stopProcessOnly();

        state = FFmpegProcessState.STOPPED;
        log.info("FFmpeg 已停止: streamId={}", streamId);
    }

    /**
     * 只停止 FFmpeg 进程，
     * 不修改 STOPPING 状态。
     */
    private synchronized void stopProcessOnly() {
        /*
         * Java 层先标记不运行。
         */
        if (state == FFmpegProcessState.RUNNING) {
            state = FFmpegProcessState.STOPPING;
        }

        /*
         * 获取当前 Watchdog。
         */
        ExecuteWatchdog currentWatchdog = watchdog;
        if (currentWatchdog != null) {
            try {
                currentWatchdog.destroyProcess();
            } catch (Exception e) {
                log.warn("销毁 FFmpeg 进程失败: streamId={}", streamId, e);
            }
        }

        /*
         * 等待 FFmpeg 退出。
         */
        DefaultExecuteResultHandler handler = resultHandler;
        if (handler != null) {
            try {
                long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS;
                while (!handler.hasResult()
                        && System.currentTimeMillis()
                        < deadline) {
                    Thread.sleep(100L);
                }
            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 停止 HLS 监控。
     */
    private synchronized void stopHlsMonitor() {
        hlsMonitorRunning = false;
        Thread thread = hlsMonitorThread;
        if (thread != null) {
            thread.interrupt();
        }
        hlsMonitorThread = null;
    }

    /**
     * 判断 FFmpeg 是否正在运行。
     *
     * @return true 正在运行
     */
    public boolean isRunning() {
        FFmpegProcessState currentState = state;
        /*
         * 非运行状态直接返回 false。
         */
        if (currentState != FFmpegProcessState.RUNNING) {
            return false;
        }

        DefaultExecuteResultHandler handler = resultHandler;
        if (handler == null) {
            return false;
        }

        /*
         * ResultHandler 已经产生结果，
         * 说明 FFmpeg 已经退出。
         */
        if (handler.hasResult()) {
            state = FFmpegProcessState.ERROR;
            return false;
        }
        return true;
    }

    /**
     * 获取当前 FFmpeg 状态。
     *
     * @return FFmpeg 状态
     */
    public FFmpegProcessState getState() {
        return state;
    }

    /**
     * 获取流 ID。
     *
     * @return 流 ID
     */
    public String getStreamId() {
        return streamId;
    }

    /**
     * 获取 FFmpeg 退出码。
     *
     * @return 退出码
     */
    private Integer getExitValue() {
        DefaultExecuteResultHandler handler = resultHandler;
        if (handler == null || !handler.hasResult()) {
            return null;
        }
        try {
            return handler.getExitValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 构建 HLS FFmpeg 命令。
     */
    private List<String> buildHlsCommand(String sourceRtsp, String outputPath, String resolution, String bitrate,
                                         int hlsTime, int hlsListSize) {
        String binPath = FFmpegManager.resolveBinPath(config.getBinPath());
        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);
        cmd.add("-rtsp_transport");
        cmd.add("tcp");
        // 输入流分析。
        cmd.add("-analyzeduration");
        cmd.add("1000000");
        cmd.add("-probesize");
        cmd.add("524288");
        // RTSP 地址。
        cmd.add("-i");
        cmd.add(sourceRtsp);
        // 是否直接复制码流。
        boolean copy = "copy".equalsIgnoreCase(config.getVideoCodec());
        if (copy) {
            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("copy");
        } else {
            // 视频编码器。
            cmd.add("-c:v");
            cmd.add(config.getVideoCodec());
            // 分辨率。
            cmd.add("-s");
            cmd.add(resolution);
            // 视频码率。
            cmd.add("-b:v");
            cmd.add(bitrate);
            // 编码预设。
            cmd.add("-preset");
            cmd.add(config.getPreset());
            // 音频编码器。
            cmd.add("-c:a");
            cmd.add(config.getAudioCodec());
            // 音频码率。
            cmd.add("-b:a");
            cmd.add(config.getAudioBitrate());
        }
        // HLS 输出。
        cmd.add("-f");
        cmd.add("hls");
        // HLS 分片时间。
        cmd.add("-hls_time");
        cmd.add(String.valueOf(hlsTime));
        // HLS 播放列表大小。
        cmd.add("-hls_list_size");
        cmd.add(String.valueOf(hlsListSize));
        // 删除旧分片并追加播放列表。
        cmd.add("-hls_flags");
        cmd.add("delete_segments+append_list");
        // m3u8 输出路径。
        cmd.add(outputPath);
        return cmd;
    }

    /**
     * 构建 FLV FFmpeg 命令。
     */
    private List<String> buildFlvCommand(String sourceRtsp) {
        String binPath = FFmpegManager.resolveBinPath(config.getBinPath());
        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);
        // RTSP TCP。
        cmd.add("-rtsp_transport");
        cmd.add("tcp");
        // 输入流分析。
        cmd.add("-analyzeduration");
        cmd.add("1000000");
        cmd.add("-probesize");
        cmd.add("524288");
        // RTSP 地址。
        cmd.add("-i");
        cmd.add(sourceRtsp);
        // 是否直接复制码流。
        boolean copy = "copy".equalsIgnoreCase(config.getVideoCodec());
        if (copy) {
            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("copy");
        } else {
            // 视频编码器。
            cmd.add("-c:v");
            cmd.add(config.getVideoCodec());
            // 视频分辨率。
            cmd.add("-s");
            cmd.add(config.getResolution());
            // 视频码率。
            cmd.add("-b:v");
            cmd.add(config.getBitrate());
            // 编码预设。
            cmd.add("-preset");
            cmd.add(config.getPreset());
            // 音频编码器。
            cmd.add("-c:a");
            cmd.add(config.getAudioCodec());
            // 音频码率。
            cmd.add("-b:a");
            cmd.add(config.getAudioBitrate());
        }
        // FLV 输出。
        cmd.add("-f");
        cmd.add("flv");
        // 输出到 stdout。
        cmd.add("pipe:1");
        return cmd;
    }

    /**
     * HLS 启动参数。
     */
    private volatile HlsStartContext hlsStartContext;

    /**
     * HLS 启动参数对象。
     */
    private static class HlsStartContext {
        private final String sourceRtsp;
        private final String outputPath;
        private final String resolution;
        private final String bitrate;
        private final int hlsTime;
        private final int hlsListSize;

        private HlsStartContext(String sourceRtsp, String outputPath, String resolution, String bitrate,
                                int hlsTime, int hlsListSize) {

            this.sourceRtsp = sourceRtsp;
            this.outputPath = outputPath;
            this.resolution = resolution;
            this.bitrate = bitrate;
            this.hlsTime = hlsTime;
            this.hlsListSize = hlsListSize;
        }
    }

    /**
     * FFmpeg 进程状态。
     */
    public enum FFmpegProcessState {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        ERROR,
        RESTARTING
    }
}