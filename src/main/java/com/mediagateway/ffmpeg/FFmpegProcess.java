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
 * 单个 FFmpeg 进程
 *
 * 一个 FFmpegProcess 对应一个 FFmpeg 子进程。
 */
@Slf4j
public class FFmpegProcess {
    private final GatewayProperties.FFmpegConfig config;
    private final String streamId;
    private final DefaultExecutor executor;
    private final ExecuteWatchdog watchdog;
    private final DefaultExecuteResultHandler resultHandler;
    private volatile boolean running;

    public FFmpegProcess(GatewayProperties.FFmpegConfig config,String streamId) {

        this.config = config;
        this.streamId = streamId;
        this.executor = DefaultExecutor.builder().get();
        this.executor.setExitValues(null);
        this.watchdog = ExecuteWatchdog.builder()
                .setTimeout(Duration.ofMillis(ExecuteWatchdog.INFINITE_TIMEOUT))
                .get();
        this.executor.setWatchdog(watchdog);
        this.resultHandler = new DefaultExecuteResultHandler();
    }

    /**
     * 启动 HLS
     */
    public synchronized boolean startHls(String sourceRtsp,String outputPath,String resolution,String bitrate,
                                         int hlsTime,int hlsListSize) {

        if (isRunning()) {
            return true;
        }

        List<String> cmd = buildHlsCommand(sourceRtsp,outputPath,resolution,bitrate,hlsTime,hlsListSize);

        try {
            CommandLine commandLine = new CommandLine(cmd.get(0));
            commandLine.addArguments(
                    cmd.subList(1, cmd.size()).toArray(new String[0]),
                    false);

            executor.setStreamHandler(new PumpStreamHandler(
                    new LogOutputStream() {
                        @Override
                        protected void processLine(String line, int logLevel) {
                            log.info("[FFmpeg:{}] {}", streamId, line);
                        }
                    },
                    new LogOutputStream() {
                        @Override
                        protected void processLine(String line, int logLevel) {
                            log.info("[FFmpeg:{}] {}", streamId, line);
                        }
                    }));

            executor.execute(commandLine, resultHandler);

            Thread.sleep(1000);

            if (resultHandler.hasResult()) {
                log.error("FFmpeg HLS 启动后立即退出: streamId={}", streamId);
                return false;
            }

            running = true;

            log.info("FFmpeg HLS 启动成功: streamId={}", streamId);

            return true;

        } catch (IOException e) {
            log.error("FFmpeg HLS 启动失败: streamId={}", streamId, e);
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * FLV 推流
     *
     * RTSP → FFmpeg → FLV → HTTP
     */
    public void streamFlv(String sourceRtsp,OutputStream outputStream)throws IOException, InterruptedException {

        List<String> cmd = buildFlvCommand(sourceRtsp);

        CommandLine commandLine = new CommandLine(cmd.get(0));
        commandLine.addArguments(
                cmd.subList(1, cmd.size()).toArray(new String[0]),
                false);

        AtomicBoolean clientDisconnected = new AtomicBoolean(false);

        OutputStream nonClosingOut = new FilterOutputStream(outputStream) {
            @Override
            public void close() throws IOException {
                flush();
            }
        };

        executor.setStreamHandler(new PumpStreamHandler(
                nonClosingOut,
                new LogOutputStream() {
                    @Override
                    protected void processLine(String line, int logLevel) {
                        log.info("[FFmpeg-FLV:{}] {}", streamId, line);
                    }
                }));

        executor.execute(commandLine, resultHandler);

        running = true;

        Thread disconnectWatcher = new Thread(() -> {
            while (!clientDisconnected.get()) {
                try {
                    Thread.sleep(2000);
                    if (clientDisconnected.get()) {
                        break;
                    }
                    outputStream.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    if (clientDisconnected.compareAndSet(false, true)) {
                        log.info(
                                "检测到 FLV 客户端断开: streamId={}",
                                streamId);

                        stop();
                    }
                    break;
                }
            }

        }, "flv-disconnect-watcher-" + streamId);

        disconnectWatcher.setDaemon(true);
        disconnectWatcher.start();

        try {
            resultHandler.waitFor();
        } finally {
            clientDisconnected.set(true);
            disconnectWatcher.interrupt();
            stop();
        }
    }

    /**
     * 停止 FFmpeg
     */
    public synchronized void stop() {
        if (!isRunning()) {
            return;
        }
        log.info("停止 FFmpeg: streamId={}", streamId);
        running = false;
        if (watchdog != null) {
            watchdog.destroyProcess();
        }
        try {
            if (resultHandler != null) {
                long deadline = System.currentTimeMillis() + 5000;

                while (!resultHandler.hasResult() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        log.info("FFmpeg 已停止: streamId={}", streamId);
    }

    /**
     * 判断 FFmpeg 是否运行
     */
    public boolean isRunning() {
        if (!running) {
            return false;
        }
        return !resultHandler.hasResult();
    }

    private List<String> buildHlsCommand(String sourceRtsp,String outputPath,String resolution,String bitrate,
            int hlsTime,int hlsListSize) {

        String binPath = FFmpegManager.resolveBinPath(config.getBinPath());

        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);
        cmd.add("-rtsp_transport");
        cmd.add("tcp");

        cmd.add("-analyzeduration");
        cmd.add("1000000");

        cmd.add("-probesize");
        cmd.add("524288");

        cmd.add("-i");
        cmd.add(sourceRtsp);

        boolean copy = "copy".equalsIgnoreCase(config.getVideoCodec());

        if (copy) {

            cmd.add("-c:v");
            cmd.add("copy");

            cmd.add("-c:a");
            cmd.add("copy");

        } else {

            cmd.add("-c:v");
            cmd.add(config.getVideoCodec());

            cmd.add("-s");
            cmd.add(resolution);

            cmd.add("-b:v");
            cmd.add(bitrate);

            cmd.add("-preset");
            cmd.add(config.getPreset());

            cmd.add("-c:a");
            cmd.add(config.getAudioCodec());

            cmd.add("-b:a");
            cmd.add(config.getAudioBitrate());
        }

        cmd.add("-f");
        cmd.add("hls");

        cmd.add("-hls_time");
        cmd.add(String.valueOf(hlsTime));

        cmd.add("-hls_list_size");
        cmd.add(String.valueOf(hlsListSize));

        cmd.add("-hls_flags");
        cmd.add("delete_segments+append_list");

        cmd.add(outputPath);

        return cmd;
    }

    private List<String> buildFlvCommand(String sourceRtsp) {
        String binPath = FFmpegManager.resolveBinPath(config.getBinPath());

        List<String> cmd = new ArrayList<>();
        cmd.add(binPath);

        cmd.add("-rtsp_transport");
        cmd.add("tcp");

        cmd.add("-analyzeduration");
        cmd.add("1000000");

        cmd.add("-probesize");
        cmd.add("524288");

        cmd.add("-i");
        cmd.add(sourceRtsp);

        boolean copy = "copy".equalsIgnoreCase(config.getVideoCodec());

        if (copy) {
            cmd.add("-c:v");
            cmd.add("copy");

            cmd.add("-c:a");
            cmd.add("copy");
        } else {
            cmd.add("-c:v");
            cmd.add(config.getVideoCodec());

            cmd.add("-s");
            cmd.add(config.getResolution());

            cmd.add("-b:v");
            cmd.add(config.getBitrate());

            cmd.add("-preset");
            cmd.add(config.getPreset());

            cmd.add("-c:a");
            cmd.add(config.getAudioCodec());

            cmd.add("-b:a");
            cmd.add(config.getAudioBitrate());
        }
        cmd.add("-f");
        cmd.add("flv");
        cmd.add("pipe:1");
        return cmd;
    }
}