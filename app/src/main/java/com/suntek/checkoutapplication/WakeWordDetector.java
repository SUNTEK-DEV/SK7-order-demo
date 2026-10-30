package com.suntek.checkoutapplication;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 离线语音唤醒（sherpa-onnx KeywordSpotter + 拼音关键词模型）。
 *
 * 选型说明：
 *  - 唤醒必须"常驻监听"，用云端识别轮询既费流量又有 2~3 秒延迟，且本机底噪很大
 *    （实测静音段 rms 也能到 2000+），基于能量的 VAD 会疯狂误触发；
 *  - 关键词模型是个 3.3M 参数的小 zipformer，专门做「只在听到这几个音时才响应」，
 *    在噪声环境下的鲁棒性远好于能量门限，且完全离线、不吃流量。
 *
 * 唤醒词在 {@link SpeechConfig#WAKE_WORDS} 里配置（拼音音素 + @文字）。
 */
public class WakeWordDetector {

    private static final String TAG = "WakeWord";

    public interface Callback {
        /** 检测到唤醒词。 */
        void onWakeWord(String keyword);

        /** 引擎不可用（模型缺失 / so 未加载），reason 用于界面提示。 */
        void onUnavailable(String reason);
    }

    private final Context context;

    private KeywordSpotter spotter;
    private OnlineStream stream;
    private AudioRecord recorder;
    private Thread thread;

    private volatile boolean running = false;
    private volatile boolean ready = false;
    private volatile boolean paused = false;
    private volatile long lastHitMs = 0;
    private String unavailableReason;

    public WakeWordDetector(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isRunning() {
        return running;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    /** 加载模型（耗时约 0.5~1s，请在后台线程调用）。 */
    public boolean load() {
        if (ready) {
            return true;
        }
        try {
            File dir = ensureModelsOnDisk();
            if (dir == null) {
                unavailableReason = "唤醒模型缺失（assets/kws）";
                Log.e(TAG, unavailableReason);
                return false;
            }
            String enc = new File(dir, "encoder.onnx").getAbsolutePath();
            String dec = new File(dir, "decoder.onnx").getAbsolutePath();
            String joi = new File(dir, "joiner.onnx").getAbsolutePath();
            String tokens = new File(dir, "tokens.txt").getAbsolutePath();
            File kwFile = new File(dir, "keywords.txt");
            writeKeywords(kwFile);

            FeatureConfig feat = new FeatureConfig(16000, 80, 0f);
            OnlineTransducerModelConfig transducer =
                    new OnlineTransducerModelConfig(enc, dec, joi);
            // 注意：这些配置类由 Kotlin 编译，非空参数不能传 null（会抛 NPE），
            // 用不到的模型给一个空对象即可。
            OnlineModelConfig model = new OnlineModelConfig(
                    transducer,
                    new OnlineParaformerModelConfig(),
                    new OnlineZipformer2CtcModelConfig(),
                    new OnlineNeMoCtcModelConfig(),
                    new OnlineToneCtcModelConfig(),
                    tokens, 2, false, "cpu", "", "", "");
            KeywordSpotterConfig config = new KeywordSpotterConfig(
                    feat, model, 4, kwFile.getAbsolutePath(), 1.0f, 0.25f, 1);

            // 传 null 表示"按文件路径加载"（模型已复制到 filesDir），
            // 传 AssetManager 则所有路径都会按 assets 相对路径解析，这里不能混用。
            spotter = new KeywordSpotter(null, config);
            ready = true;
            Log.i(TAG, "关键词模型加载完成, keywords=" + SpeechConfig.WAKE_WORDS.length);
            return true;
        } catch (Throwable e) {
            unavailableReason = "唤醒模型加载失败：" + e.getMessage();
            Log.e(TAG, unavailableReason, e);
            ready = false;
            return false;
        }
    }

    /** 开始常驻监听（需已 load 成功）。 */
    public void start(Callback cb) {
        if (!ready && !load()) {
            if (cb != null) {
                cb.onUnavailable(unavailableReason);
            }
            return;
        }
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(() -> loop(cb), "WakeWordThread");
        thread.start();
    }

    /** 暂停监听（开始语音识别前必须调用，麦克风不能同时被两路占用）。 */
    public void pause() {
        paused = true;
        stopRecorder();
    }

    /** 恢复监听。 */
    public void resume() {
        paused = false;
    }

    public void stop() {
        running = false;
        paused = false;
        stopRecorder();
        Thread t = thread;
        thread = null;
        if (t != null && Thread.currentThread() != t) {
            try {
                t.join(1200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void release() {
        stop();
        try {
            if (spotter != null) {
                spotter.release();
            }
        } catch (Exception ignored) {
        }
        spotter = null;
        ready = false;
    }

    private void loop(Callback cb) {
        short[] buffer = new short[1600]; // 100ms @16k
        float[] samples = new float[1600];
        Log.i(TAG, "唤醒监听线程启动");
        try {
            while (running) {
                if (paused) {
                    sleepQuietly(120);
                    continue;
                }
                AudioRecord rec = openRecorder();
                if (rec == null) {
                    Log.e(TAG, "麦克风打开失败，1 秒后重试");
                    sleepQuietly(1000);
                    continue;
                }
                try {
                    stream = spotter.createStream("");
                    rec.startRecording();
                    Log.i(TAG, "唤醒监听中…");

                    while (running && !paused) {
                        int n;
                        try {
                            n = rec.read(buffer, 0, buffer.length);
                        } catch (Exception e) {
                            // 暂停时会释放录音器，此时 read 可能抛异常：退出本轮，等恢复后重建
                            Log.w(TAG, "AudioRecord.read 异常（多半是暂停释放），退出本轮监听");
                            break;
                        }
                        if (n <= 0) {
                            continue;
                        }
                        for (int i = 0; i < n; i++) {
                            samples[i] = buffer[i] / 32768.0f;
                        }
                        stream.acceptWaveform(samples, 16000);
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream);
                        }
                        KeywordSpotterResult r = spotter.getResult(stream);
                        String kw = (r == null) ? "" : r.getKeyword();
                        if (kw != null && !kw.isEmpty()) {
                            long now = System.currentTimeMillis();
                            // 2 秒冷却，避免一句话触发多次
                            if (now - lastHitMs > 2000) {
                                lastHitMs = now;
                                Log.i(TAG, "检测到唤醒词：" + kw);
                                spotter.reset(stream);
                                if (cb != null) {
                                    cb.onWakeWord(kw);
                                }
                            } else {
                                spotter.reset(stream);
                            }
                        }
                    }
                } catch (Exception e) {
                    // 单轮失败（麦克风被抢占、模型解码异常等）不应让常驻监听整体退出
                    Log.w(TAG, "监听本轮异常，500ms 后重试", e);
                    sleepQuietly(500);
                } finally {
                    releaseStream();
                    try {
                        rec.stop();
                    } catch (Exception ignored) {
                    }
                    try {
                        rec.release();
                    } catch (Exception ignored) {
                    }
                    recorder = null;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "唤醒线程异常退出", e);
            if (cb != null) {
                cb.onUnavailable("唤醒监听异常：" + e.getMessage());
            }
        }
        Log.i(TAG, "唤醒监听线程结束");
    }

    private AudioRecord openRecorder() {
        int minBuf = AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 2, 16000);
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord rec = new AudioRecord(src, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    recorder = rec;
                    return rec;
                }
                rec.release();
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord source=" + src + " 失败: " + e.getMessage());
            }
        }
        return null;
    }

    private void stopRecorder() {
        AudioRecord rec = recorder;
        recorder = null;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Exception ignored) {
            }
            try {
                rec.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void releaseStream() {
        OnlineStream s = stream;
        stream = null;
        if (s != null) {
            try {
                s.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把 assets/kws 下的模型复制到私有目录（sherpa 的 C++ 侧按文件路径加载）。 */
    private File ensureModelsOnDisk() {
        File dir = new File(context.getFilesDir(), "kws");
        String[] files = {"encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"};
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            AssetManager am = context.getAssets();
            for (String name : files) {
                File out = new File(dir, name);
                if (out.exists() && out.length() > 0) {
                    continue;
                }
                try (InputStream is = am.open("kws/" + name);
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                Log.i(TAG, "已复制模型 " + name + " (" + out.length() + " bytes)");
            }
            return dir;
        } catch (Exception e) {
            Log.e(TAG, "复制唤醒模型失败: " + e.getMessage(), e);
            return null;
        }
    }

    /** 生成关键词文件（拼音音素 + @中文，sherpa 的 KWS 格式）。 */
    private void writeKeywords(File out) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String[] kw : SpeechConfig.WAKE_WORDS) {
            // kw[0] = 音素串（空格分隔），kw[1] = 中文显示
            sb.append(kw[0]).append(" @").append(kw[1]).append('\n');
        }
        String content = sb.toString();
        boolean needWrite = true;
        if (out.exists()) {
            byte[] old = new byte[(int) out.length()];
            try (InputStream is = new java.io.FileInputStream(out)) {
                int read = 0;
                while (read < old.length) {
                    int n = is.read(old, read, old.length - read);
                    if (n < 0) break;
                    read += n;
                }
            } catch (Exception ignored) {
            }
            needWrite = !content.equals(new String(old, StandardCharsets.UTF_8));
        }
        if (needWrite) {
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "关键词文件已写入:\n" + content);
        }
    }
}
