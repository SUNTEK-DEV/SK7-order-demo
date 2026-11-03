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
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

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
    /** 英文唤醒（独立 3.3M 英文模型，和中文共用同一路音频）。 */
    private KeywordSpotter spotterEn;
    private OnlineStream streamEn;
    private AudioRecord recorder;
    private Thread thread;
    /** 仅调试用：同一套模型以普通识别方式解码，看它到底听到了什么。 */
    private OnlineRecognizer debugRecognizer;
    private OnlineRecognizer debugRecognizerEn;

    private volatile boolean running = false;
    private volatile boolean ready = false;
    private volatile boolean paused = false;
    private volatile long lastHitMs = 0;
    /** 电平诊断（每 3 秒打一次，用于判断麦克风是否真的收到声音）。 */
    private long lastLevelLogMs = 0;
    private int peakSinceLog = 0;
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
            File dir = ensureModelsOnDisk("kws", "kws");
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
                    feat, model, 8, kwFile.getAbsolutePath(), 1.0f, 0.25f, 1);

            // 传 null 表示"按文件路径加载"（模型已复制到 filesDir），
            // 传 AssetManager 则所有路径都会按 assets 相对路径解析，这里不能混用。
            spotter = new KeywordSpotter(null, config);
            ready = true;
            Log.i(TAG, "关键词模型加载完成, keywords=" + SpeechConfig.WAKE_WORDS.size());
            loadEnglishSpotter(feat);
            return true;
        } catch (Throwable e) {
            unavailableReason = "唤醒模型加载失败：" + e.getMessage();
            Log.e(TAG, unavailableReason, e);
            ready = false;
            return false;
        }
    }

    /** 英文唤醒模型（可选）：缺失或加载失败时只影响英文唤醒，中文照常工作。 */
    private void loadEnglishSpotter(FeatureConfig feat) {
        try {
            File dir = ensureModelsOnDisk("kws_en", "kws_en");
            if (dir == null) {
                Log.w(TAG, "未找到 assets/kws_en，英文唤醒不可用");
                return;
            }
            File kwFile = new File(dir, "keywords.txt");
            if (!writeEnKeywords(kwFile)) {
                Log.w(TAG, "英文唤醒词全部无效，跳过英文模型");
                return;
            }
            String enc = new File(dir, "encoder.onnx").getAbsolutePath();
            String dec = new File(dir, "decoder.onnx").getAbsolutePath();
            String joi = new File(dir, "joiner.onnx").getAbsolutePath();
            String tokens = new File(dir, "tokens.txt").getAbsolutePath();
            OnlineModelConfig model = new OnlineModelConfig(
                    new OnlineTransducerModelConfig(enc, dec, joi),
                    new OnlineParaformerModelConfig(),
                    new OnlineZipformer2CtcModelConfig(),
                    new OnlineNeMoCtcModelConfig(),
                    new OnlineToneCtcModelConfig(),
                    tokens, 2, false, "cpu", "", "", "");
            // 最后一个参数 num_trailing_blanks 必须是 0：
            // 它要求关键词后面跟 N 个静音帧才算命中。设 1 时，
            // "Hello, I'd like to order" 这种 hello 后面不停顿的说法永远无法定稿
            // （实测「hello there」一条都命中不了，只有单独说「hello」才行）。
            KeywordSpotterConfig cfg = new KeywordSpotterConfig(
                    feat, model, 8, kwFile.getAbsolutePath(), 1.0f, 0.3f, 0);
            spotterEn = new KeywordSpotter(null, cfg);
            Log.i(TAG, "英文唤醒模型加载完成, keywords=" + SpeechConfig.WAKE_WORDS_EN.length);
        } catch (Throwable e) {
            spotterEn = null;
            Log.e(TAG, "英文唤醒模型加载失败（不影响中文唤醒）: " + e.getMessage(), e);
        }
    }

    /** 生成英文关键词文件（BPE token）。返回是否至少写了一行。 */
    private boolean writeEnKeywords(File out) throws Exception {
        Set<String> vocab = loadVocabulary(new File(out.getParentFile(), "tokens.txt"));
        StringBuilder sb = new StringBuilder();
        for (String[] kw : SpeechConfig.WAKE_WORDS_EN) {
            if (!allTokensKnown(kw[0], vocab)) {
                Log.w(TAG, "英文唤醒词被跳过（含词表外的 token）：" + kw[0]);
                continue;
            }
            sb.append(kw[0]).append(" :").append(kw[2]).append(" #").append(kw[3])
                    .append(" @").append(kw[1].replace(' ', '_')).append('\n');
        }
        if (sb.length() == 0) {
            return false;
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
            Log.i(TAG, "英文关键词文件已写入:\n" + content);
        }
        return true;
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
        try {
            if (spotterEn != null) {
                spotterEn.release();
            }
        } catch (Exception ignored) {
        }
        spotterEn = null;
        try {
            if (debugRecognizer != null) {
                debugRecognizer.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (debugRecognizerEn != null) {
                debugRecognizerEn.release();
            }
        } catch (Exception ignored) {
        }
        debugRecognizer = null;
        debugRecognizerEn = null;
        ready = false;
    }

    /**
     * 调试用：把一段 16k/16bit/单声道 PCM 直接喂给唤醒模型，返回命中的关键词。
     *
     * 为什么需要它：板子开着 AEC（{@link MediaRecorder.AudioSource#VOICE_RECOGNITION}），
     * 用它自己的喇叭放"hello"时，麦克风侧基本被抵消掉，靠外放测唤醒是测不出来的。
     * 直接把 PCM 灌进解码器，才能判断「模型到底认不认这个词」。
     *
     * @return 命中的关键词；null 表示未命中；非 null 且以 "!" 开头表示调用失败
     */
    public String debugDetect(byte[] pcm16, boolean english) {
        KeywordSpotter sp = english ? spotterEn : spotter;
        if (sp == null) {
            return english ? "!英文模型未加载" : "!模型未加载";
        }
        if (pcm16 == null || pcm16.length < 3200) {
            return "!音频太短";
        }
        boolean wasPaused = paused;
        // 和常驻监听抢占同一个 spotter 会崩，先让它让出麦克风与解码循环
        paused = true;
        stopRecorder();
        sleepQuietly(300);

        OnlineStream s = null;
        try {
            s = sp.createStream("");
            short[] buf = new short[pcm16.length / 2];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (short) ((pcm16[2 * i + 1] << 8) | (pcm16[2 * i] & 0xFF));
            }
            // 前后各补静音：新流的开头几百毫秒会被特征预热带掉，
            // 直接拿首帧喂长词会把第一个词解错（实测 "hello world" 会解成 "NO WORLD"）。
            short[] all = new short[buf.length + 9600 + 12800];
            System.arraycopy(buf, 0, all, 12800, buf.length);

            int chunk = 1600; // 100ms
            String hit = null;
            for (int off = 0; off < all.length; off += chunk) {
                int n = Math.min(chunk, all.length - off);
                float[] f = new float[n];
                for (int i = 0; i < n; i++) {
                    f[i] = all[off + i] / 32768.0f;
                }
                s.acceptWaveform(f, 16000);
                while (sp.isReady(s)) {
                    sp.decode(s);
                }
                KeywordSpotterResult r = sp.getResult(s);
                String kw = (r == null) ? "" : r.getKeyword();
                if (kw != null && !kw.isEmpty()) {
                    hit = kw;
                    sp.reset(s);
                    break;
                }
            }
            Log.i(TAG, "debugDetect(" + (english ? "en" : "zh") + "): "
                    + (all.length / 16) + "ms, hit=" + hit);
            return hit;
        } catch (Throwable e) {
            Log.e(TAG, "debugDetect 失败", e);
            return "!异常：" + e.getMessage();
        } finally {
            if (s != null) {
                try {
                    s.release();
                } catch (Exception ignored) {
                }
            }
            paused = wasPaused;
        }
    }

    /**
     * 调试用：用同一套声学模型把 PCM 当普通语音识别，输出它"听到"的拼音 token。
     *
     * 唤醒词写错（声调不对 / 韵母不同）时，KWS 只会静默不触发，很难猜；
     * 先看模型实际解出什么，再照着写关键词，一次就对。
     */
    public String debugTranscribe(byte[] pcm16) {
        return debugTranscribe(pcm16, false);
    }

    public String debugTranscribe(byte[] pcm16, boolean english) {
        if (pcm16 == null || pcm16.length < 3200) {
            return "!音频太短";
        }
        boolean wasPaused = paused;
        paused = true;
        stopRecorder();
        sleepQuietly(300);
        OnlineStream s = null;
        try {
            OnlineRecognizer rec = ensureDebugRecognizer(english);
            if (rec == null) {
                return "!识别器初始化失败";
            }
            s = rec.createStream("");
            short[] buf = new short[pcm16.length / 2];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = (short) ((pcm16[2 * i + 1] << 8) | (pcm16[2 * i] & 0xFF));
            }
            short[] all = new short[buf.length + 9600 + 12800];
            System.arraycopy(buf, 0, all, 12800, buf.length);

            int chunk = 1600;
            for (int off = 0; off < all.length; off += chunk) {
                int n = Math.min(chunk, all.length - off);
                float[] f = new float[n];
                for (int i = 0; i < n; i++) {
                    f[i] = all[off + i] / 32768.0f;
                }
                s.acceptWaveform(f, 16000);
                while (rec.isReady(s)) {
                    rec.decode(s);
                }
            }
            OnlineRecognizerResult r = rec.getResult(s);
            String text = (r == null) ? "" : r.getText();
            String tokens = "";
            if (r != null && r.getTokens() != null) {
                StringBuilder sb = new StringBuilder();
                for (String t : r.getTokens()) {
                    sb.append(t).append(' ');
                }
                tokens = sb.toString().trim();
            }
            Log.i(TAG, "debugTranscribe: text=[" + text + "] tokens=[" + tokens + "]");
            return "text=[" + text + "] tokens=[" + tokens + "]";
        } catch (Throwable e) {
            Log.e(TAG, "debugTranscribe 失败", e);
            return "!异常：" + e.getMessage();
        } finally {
            if (s != null) {
                try {
                    s.release();
                } catch (Exception ignored) {
                }
            }
            paused = wasPaused;
        }
    }

    /**
     * 调试用：把英文 KWS 模型下载到 filesDir/kws_en（板子/PC 都没有 curl，只能让 App 自己下）。
     * 下完用 adb pull 取出来放进 assets 即可，运行时不再联网。
     */
    public boolean downloadEnglishModelForDebug() {
        final String base = "https://www.modelscope.cn/api/v1/models/pkufool/"
                + "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01/repo"
                + "?Revision=master&FilePath=";
        String[][] files = {
                {"encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "encoder.onnx"},
                {"decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "decoder.onnx"},
                {"joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "joiner.onnx"},
                {"tokens.txt", "tokens.txt"},
        };
        File dir = new File(context.getFilesDir(), "kws_en");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "无法创建 " + dir);
            return false;
        }
        boolean allOk = true;
        for (String[] f : files) {
            File out = new File(dir, f[1]);
            try {
                String url = base + f[0];
                Log.i(TAG, "开始下载 " + f[0]);
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code != 200) {
                    Log.e(TAG, "下载失败 " + f[0] + " HTTP " + code + " url=" + url);
                    allOk = false;
                    continue;
                }
                long total = 0;
                try (InputStream is = conn.getInputStream();
                     FileOutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        total += n;
                    }
                }
                Log.i(TAG, "下载完成 " + f[1] + " (" + total + " bytes)");
            } catch (Exception e) {
                Log.e(TAG, "下载异常 " + f[0] + ": " + e.getMessage(), e);
                allOk = false;
            }
        }
        Log.i(TAG, "英文唤醒模型下载结束, dir=" + dir.getAbsolutePath() + " ok=" + allOk);
        return allOk;
    }

    private OnlineRecognizer ensureDebugRecognizer(boolean english) {
        if (english ? debugRecognizerEn != null : debugRecognizer != null) {
            return english ? debugRecognizerEn : debugRecognizer;
        }
        try {
            File dir = new File(context.getFilesDir(), english ? "kws_en" : "kws");
            String enc = new File(dir, "encoder.onnx").getAbsolutePath();
            String dec = new File(dir, "decoder.onnx").getAbsolutePath();
            String joi = new File(dir, "joiner.onnx").getAbsolutePath();
            String tokens = new File(dir, "tokens.txt").getAbsolutePath();
            FeatureConfig feat = new FeatureConfig(16000, 80, 0f);
            OnlineModelConfig model = new OnlineModelConfig(
                    new OnlineTransducerModelConfig(enc, dec, joi),
                    new OnlineParaformerModelConfig(),
                    new OnlineZipformer2CtcModelConfig(),
                    new OnlineNeMoCtcModelConfig(),
                    new OnlineToneCtcModelConfig(),
                    tokens, 2, false, "cpu", "", "", "");
            OnlineRecognizerConfig cfg = new OnlineRecognizerConfig(
                    feat, model,
                    new com.k2fsa.sherpa.onnx.OnlineLMConfig("", 0f),
                    new com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig(),
                    new com.k2fsa.sherpa.onnx.HomophoneReplacerConfig("", "", ""),
                    new com.k2fsa.sherpa.onnx.EndpointConfig(),
                    false, "greedy_search", 4, "", 1.0f, "", "", 0f);
            OnlineRecognizer rec = new OnlineRecognizer(null, cfg);
            if (english) {
                debugRecognizerEn = rec;
            } else {
                debugRecognizer = rec;
            }
            return rec;
        } catch (Throwable e) {
            Log.e(TAG, "创建调试识别器失败", e);
            return null;
        }
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
                    if (spotterEn != null) {
                        streamEn = spotterEn.createStream("");
                    }
                    rec.startRecording();
                    Log.i(TAG, "唤醒监听中…（中文" + (spotterEn != null ? " + 英文" : "") + "）");

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
                        int peak = 0;
                        for (int i = 0; i < n; i++) {
                            samples[i] = buffer[i] / 32768.0f;
                            int a = Math.abs(buffer[i]);
                            if (a > peak) {
                                peak = a;
                            }
                        }
                        // 每 3 秒打一次电平，用来区分「麦克风没声音」和「有声音但没命中关键词」：
                        // 正常说话峰值 1000~8000，<300 基本是静音/被回声消除吃掉。
                        if (peak > peakSinceLog) {
                            peakSinceLog = peak;
                        }
                        long levelNow = System.currentTimeMillis();
                        if (levelNow - lastLevelLogMs > 3000) {
                            Log.d(TAG, "麦克风电平 peak=" + peakSinceLog + " / 32767");
                            lastLevelLogMs = levelNow;
                            peakSinceLog = 0;
                        }
                        // 同一路音频同时喂给中英文两个模型：中文模型自带拼音词表，
                        // 英文模型是单独的 gigaspeech BPE 模型，互相不干扰。
                        stream.acceptWaveform(samples, 16000);
                        while (spotter.isReady(stream)) {
                            spotter.decode(stream);
                        }
                        if (streamEn != null) {
                            streamEn.acceptWaveform(samples, 16000);
                            while (spotterEn.isReady(streamEn)) {
                                spotterEn.decode(streamEn);
                            }
                        }
                        String kw = keywordOf(spotter, stream);
                        if (kw == null && streamEn != null) {
                            kw = keywordOf(spotterEn, streamEn);
                        }
                        if (kw != null && !kw.isEmpty()) {
                            long now = System.currentTimeMillis();
                            // 2 秒冷却，避免一句话触发多次
                            if (now - lastHitMs > 2000) {
                                lastHitMs = now;
                                Log.i(TAG, "检测到唤醒词：" + kw);
                                spotter.reset(stream);
                                if (streamEn != null) {
                                    spotterEn.reset(streamEn);
                                }
                                if (cb != null) {
                                    cb.onWakeWord(kw);
                                }
                            } else {
                                spotter.reset(stream);
                                if (streamEn != null) {
                                    spotterEn.reset(streamEn);
                                }
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

    private String keywordOf(KeywordSpotter sp, OnlineStream s) {
        KeywordSpotterResult r = sp.getResult(s);
        String kw = (r == null) ? null : r.getKeyword();
        return (kw == null || kw.isEmpty()) ? null : kw;
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
        OnlineStream s2 = streamEn;
        streamEn = null;
        if (s2 != null) {
            try {
                s2.release();
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

    /** 把 assets/<assetDir> 下的模型复制到私有目录（sherpa 的 C++ 侧按文件路径加载）。 */
    private File ensureModelsOnDisk(String assetDir, String destName) {
        File dir = new File(context.getFilesDir(), destName);
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
                try (InputStream is = am.open(assetDir + "/" + name);
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                Log.i(TAG, "已复制模型 " + assetDir + "/" + name + " (" + out.length() + " bytes)");
            }
            return dir;
        } catch (Exception e) {
            Log.e(TAG, "复制唤醒模型失败: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 生成关键词文件（音素/字母序列 + @显示名，sherpa 的 KWS 格式）。
     *
     * 两个坑（都踩过）：
     *  1) 文件是按空格切 token 的，@ 后面的显示名也不能带空格，
     *     否则 "I'd like to order" 会被当成 token → "Encode keywords failed" → 进程崩溃；
     *  2) token 必须存在于 tokens.txt，写错的词会让整个 spotter 构造失败，
     *     所以这里先按词表校验，无效的关键词直接跳过并打日志。
     */
    private void writeKeywords(File out) throws Exception {
        Set<String> vocab = loadVocabulary(new File(out.getParentFile(), "tokens.txt"));
        StringBuilder sb = new StringBuilder();
        for (SpeechConfig.WakeWord kw : SpeechConfig.WAKE_WORDS) {
            String tokens = kw.tokens;
            if (!allTokensKnown(tokens, vocab)) {
                Log.w(TAG, "唤醒词被跳过（含词表外的 token）：" + tokens + " @ " + kw.display);
                continue;
            }
            // 显示名不能带空格：换成下划线
            String display = kw.display.replace(' ', '_');
            sb.append(tokens);
            // sherpa 语法：tokens :boost #threshold @显示名（: 和 # 后面不能有空格）
            if (kw.boost > 0f) {
                sb.append(" :").append(kw.boost);
            }
            if (kw.threshold > 0f) {
                sb.append(" #").append(kw.threshold);
            }
            sb.append(" @").append(display).append('\n');
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

    /** 读 tokens.txt 有效 token（第一列）。 */
    private static Set<String> loadVocabulary(File tokensFile) {
        Set<String> vocab = new HashSet<>();
        if (!tokensFile.exists()) {
            return vocab;
        }
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(tokensFile),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                int sp = line.indexOf(' ');
                vocab.add(sp > 0 ? line.substring(0, sp) : line);
            }
        } catch (Exception e) {
            Log.w(TAG, "读取 tokens.txt 失败: " + e.getMessage());
        }
        Log.i(TAG, "词表大小: " + vocab.size());
        return vocab;
    }

    private static boolean allTokensKnown(String tokens, Set<String> vocab) {
        if (vocab.isEmpty()) {
            return true; // 读不到词表就别拦，交给 sherpa 判断
        }
        for (String t : tokens.trim().split("\\s+")) {
            if (!vocab.contains(t)) {
                Log.w(TAG, "词表里没有 token: [" + t + "]");
                return false;
            }
        }
        return true;
    }
}
