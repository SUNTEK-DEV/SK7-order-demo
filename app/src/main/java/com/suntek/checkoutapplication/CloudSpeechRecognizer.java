package com.suntek.checkoutapplication;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 云端语音识别器（默认讯飞语音听写 iat）。
 *
 * 【这一版的关键改动：从"整段录完再上传"改成"边说边传"】
 *
 * 旧实现是本地用 RMS 能量门限判断有没有说话、有没有停下来。但这台板子的环境底噪很大
 * （实测静音段 rms 也能到 2000+，并且每隔一两秒就有一次噪声尖峰），于是：
 *   - 静音断句永远不触发 → 每次都要一直录到 20 秒上限；
 *   - 用户说完话后还得再点一次麦克风才能结束，体验很差。
 *
 * 现在改为把音频按 100ms 分片实时发给讯飞，由**服务端 VAD**（vad_eos）判断一句话结束，
 * 服务端返回 status=2 时本地立刻停止录音。这样：
 *   - 说完停顿约 2 秒就自动结束，无需再点按钮；
 *   - 识别中间结果实时回显（onPartialResult），用户能立刻看到"听懂了什么"；
 *   - 底噪再大也不影响断句判断（音频一直在传，判断交给模型）。
 */
public class CloudSpeechRecognizer implements IVoiceRecognizer {

    private static final String TAG = "CloudSpeechRecognizer";

    private static final String XF_ASR_HOST = "iat-api.xfyun.cn";
    private static final String XF_ASR_PATH = "/v2/iat";

    private static final int SAMPLE_RATE = 16000;
    /** 每次上传 100ms 音频（1600 samples @16k） */
    private static final int FRAME_SAMPLES = 1600;
    /** 单次识别最长时长 */
    private static final long MAX_SESSION_MS = 20000;
    /** 一直没检测到说话就放弃 */
    private static final long NO_SPEECH_TIMEOUT_MS = 8000;
    /** 说过话后长时间没有有效语音则强制收尾（服务端 VAD 失效时兜底） */
    private static final long LONG_SILENCE_MS = 8000;
    /** 本地"有声音"判据，仅用于超时兜底，不参与断句 */
    private static final double RMS_SPEECH_THRESHOLD = 400.0;

    private String bcp47Language = "zh-CN";

    private volatile Listener listener;
    private volatile boolean recording = false;
    private volatile boolean serverEnded = false;
    private volatile String sessionError = null;
    private volatile String finalText = "";

    private volatile AudioRecord recorder;
    private volatile XunfeiWebSocket ws;
    private Thread sessionThread;

    @Override
    public void setLanguage(String bcp47LanguageCode) {
        if (bcp47LanguageCode != null && !bcp47LanguageCode.isEmpty()) {
            this.bcp47Language = bcp47LanguageCode;
        }
    }

    @Override
    public boolean isListening() {
        return recording;
    }

    @Override
    public void start(Listener l) {
        // 重复调用：先把上一轮彻底停掉
        if (recording) {
            recording = false;
            closeWsQuietly();
            Thread old = sessionThread;
            if (old != null && Thread.currentThread() != old) {
                try {
                    old.join(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        this.listener = l;
        this.serverEnded = false;
        this.sessionError = null;
        this.finalText = "";
        this.recording = true;

        Log.i(TAG, "start(): provider=" + SpeechConfig.PROVIDER + ", lang=" + bcp47Language);

        if (SpeechConfig.PROVIDER == SpeechConfig.Provider.XUNFEI) {
            if (isBlank(SpeechConfig.XUNFEI_APP_ID)
                    || isBlank(SpeechConfig.XUNFEI_API_KEY)
                    || isBlank(SpeechConfig.XUNFEI_API_SECRET)) {
                emitError("未配置讯飞凭证，请在 SpeechConfig 中填入");
                return;
            }
            sessionThread = new Thread(this::runXunfeiStream, "XfAsrSession");
        } else {
            if (isBlank(SpeechConfig.CLOUD_API_KEY)) {
                emitError("未配置云端 STT API Key");
                return;
            }
            sessionThread = new Thread(this::runGoogleBatch, "GoogleAsrSession");
        }
        sessionThread.start();
    }

    // ==================================================================
    //  讯飞：流式识别
    // ==================================================================

    private void runXunfeiStream() {
        AudioRecord rec = null;
        XunfeiWebSocket socket = null;
        Thread reader = null;
        try {
            socket = new XunfeiWebSocket(XF_ASR_HOST, XF_ASR_PATH);
            socket.connect(SpeechConfig.XUNFEI_APP_ID, SpeechConfig.XUNFEI_API_KEY,
                    SpeechConfig.XUNFEI_API_SECRET);
            ws = socket;
            final XunfeiWebSocket socket2 = socket;
            Log.i(TAG, "xunfei: websocket connected, xfLang=" + xunfeiLanguage());

            reader = new Thread(() -> readXunfeiResponses(socket2), "XfAsrReader");
            reader.setDaemon(true);
            reader.start();

            rec = openRecorder();
            if (rec == null) {
                sessionError = "麦克风不可用，请检查设备音频输入";
                return;
            }
            recorder = rec;
            rec.startRecording();
            if (listener != null) {
                listener.onReady();
                listener.onPartialResult("🎤 聆听中…（说完停顿会自动结束）");
            }
            Log.i(TAG, "xunfei: streaming start, source=" + audioSource + ", buf=" + rec.getBufferSizeInFrames());

            short[] buf = new short[FRAME_SAMPLES];
            boolean first = true;
            long startMs = System.currentTimeMillis();
            long lastLoudMs = startMs;
            long lastLogMs = 0;
            boolean hasVoice = false;
            int frames = 0;
            long totalBytes = 0;

            while (recording && !serverEnded) {
                int n;
                try {
                    n = rec.read(buf, 0, buf.length);
                } catch (Exception e) {
                    Log.w(TAG, "AudioRecord.read 异常，结束采集", e);
                    break;
                }
                if (n <= 0) {
                    continue;
                }
                byte[] pcm = toLittleEndianBytes(buf, n);
                socket.sendText(buildXunfeiFrame(first ? 0 : 1,
                        Base64.encodeToString(pcm, Base64.NO_WRAP), first));
                first = false;
                frames++;
                totalBytes += pcm.length;

                long now = System.currentTimeMillis();
                double rms = calcRms(buf, n);
                if (rms > RMS_SPEECH_THRESHOLD) {
                    hasVoice = true;
                    lastLoudMs = now;
                }
                if (now - lastLogMs >= 2000) {
                    Log.d(TAG, String.format(Locale.US,
                            "stream t=%.1fs rms=%.0f frames=%d bytes=%d voice=%b",
                            (now - startMs) / 1000.0, rms, frames, totalBytes, hasVoice));
                    lastLogMs = now;
                }
                if (!hasVoice && now - startMs > NO_SPEECH_TIMEOUT_MS) {
                    Log.i(TAG, "stream: 8s 未检测到说话，收尾");
                    break;
                }
                if (hasVoice && now - lastLoudMs > LONG_SILENCE_MS) {
                    Log.i(TAG, "stream: 长时间静音，收尾");
                    break;
                }
                if (now - startMs > MAX_SESSION_MS) {
                    Log.i(TAG, "stream: 达到最长时长，收尾");
                    break;
                }
            }

            recording = false;
            Log.i(TAG, "stream: 采集结束 frames=" + frames + ", bytes=" + totalBytes
                    + ", serverEnded=" + serverEnded);

            // 主动收尾：告诉服务端"音频结束"，等它把最后一句结果吐出来
            if (!serverEnded) {
                try {
                    socket.sendText(buildXunfeiFrame(2, "", false));
                    Log.i(TAG, "stream: 已发送结束帧，等待最终结果");
                } catch (Exception e) {
                    Log.w(TAG, "stream: 发送结束帧失败", e);
                }
                if (reader != null) {
                    reader.join(4000);
                }
            }
        } catch (SocketTimeoutException e) {
            sessionError = "识别超时，请检查网络后重试";
            Log.e(TAG, "xunfei: timeout", e);
        } catch (Exception e) {
            if (sessionError == null) {
                sessionError = "识别失败：" + e.getMessage();
            }
            Log.e(TAG, "xunfei: stream error", e);
        } finally {
            recording = false;
            releaseRecorder();
            if (socket != null) {
                socket.close();
            }
            ws = null;
            if (reader != null) {
                try {
                    reader.join(600);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Log.i(TAG, "final transcript = [" + finalText + "], error=" + sessionError);
        if (sessionError != null) {
            emitError(sessionError);
        } else if (listener != null) {
            listener.onFinalResult(finalText == null ? "" : finalText.trim());
        }
    }

    /** 解析讯飞返回的每一帧（流式过程中持续触发）。 */
    private void readXunfeiResponses(XunfeiWebSocket socket) {
        java.util.TreeMap<Integer, String> segments = new java.util.TreeMap<>();
        int fallbackSn = 0;
        int respCount = 0;
        try {
            while (!serverEnded) {
                String msg = socket.readText();
                if (msg == null) {
                    Log.w(TAG, "xunfei: 服务端关闭连接");
                    break;
                }
                respCount++;
                JSONObject json = new JSONObject(msg);
                int code = json.optInt("code", 0);
                if (code != 0) {
                    String detail = json.optString("message", "");
                    sessionError = friendlyXunfeiError(code, detail);
                    Log.e(TAG, "xunfei: error code=" + code + ", message=" + detail);
                    serverEnded = true;
                    break;
                }
                JSONObject data = json.optJSONObject("data");
                if (data == null) {
                    continue;
                }
                JSONObject result = data.optJSONObject("result");
                if (result != null) {
                    int sn = result.has("sn") ? result.optInt("sn") : fallbackSn++;
                    String text = extractXunfeiText(result);
                    String pgs = result.optString("pgs", "apd");
                    if ("rpl".equals(pgs)) {
                        JSONArray rg = result.optJSONArray("rg");
                        if (rg != null && rg.length() >= 2) {
                            int from = rg.optInt(0);
                            int to = rg.optInt(1);
                            for (int k = from; k <= to; k++) {
                                segments.remove(k);
                            }
                        }
                    }
                    segments.put(sn, text);
                    String cumulative = joinSegments(segments);
                    if (!cumulative.isEmpty()) {
                        finalText = cumulative;
                        if (listener != null) {
                            listener.onPartialResult(cumulative);
                        }
                    }
                    Log.d(TAG, "xunfei: resp#" + respCount + " status=" + data.optInt("status", 0)
                            + " sn=" + sn + " pgs=" + pgs + " seg=[" + text
                            + "] cumulative=[" + cumulative + "]");
                }
                if (data.optInt("status", 0) == 2) {
                    Log.i(TAG, "xunfei: 服务端判定一句话结束 (status=2)");
                    serverEnded = true;
                    break;
                }
            }
        } catch (Exception e) {
            if (!serverEnded) {
                sessionError = "识别连接异常：" + e.getMessage();
            }
            Log.e(TAG, "xunfei: reader 异常", e);
        } finally {
            serverEnded = true;
        }
    }

    /** 构造讯飞 iat 的请求 JSON 帧。 */
    private String buildXunfeiFrame(int status, String audioB64, boolean first) throws JSONException {
        JSONObject root = new JSONObject();

        JSONObject common = new JSONObject();
        common.put("app_id", SpeechConfig.XUNFEI_APP_ID);
        root.put("common", common);

        if (first) {
            JSONObject business = new JSONObject();
            String lang = xunfeiLanguage();
            business.put("language", lang);
            business.put("domain", "iat");
            if ("zh_cn".equals(lang)) {
                business.put("accent", "mandarin");
            }
            // 服务端静音断句：说完停顿 2 秒即判定一句话结束（允许 700~10000）
            business.put("vad_eos", 2000);
            business.put("dwa", "wpgs"); // 动态修正，结果更准、出字更快
            root.put("business", business);
        }

        JSONObject data = new JSONObject();
        data.put("status", status);
        data.put("format", "audio/L16;rate=16000");
        data.put("encoding", "raw");
        data.put("audio", audioB64);
        root.put("data", data);
        return root.toString();
    }

    /** BCP-47 → 讯飞 iat 语言参数。 */
    private String xunfeiLanguage() {
        if (bcp47Language == null) {
            return "zh_cn";
        }
        String s = bcp47Language.toLowerCase(Locale.US);
        if (s.startsWith("zh")) return "zh_cn";
        if (s.startsWith("en")) return "en_us";
        if (s.startsWith("de")) return "de_de";
        return "zh_cn";
    }

    /** 把讯飞的错误码转成能看懂的提示。 */
    private static String friendlyXunfeiError(int code, String raw) {
        switch (code) {
            case 11200:
                return "讯飞未开通该语言（licc failed）。德语需在讯飞控制台开通“小语种/多语种”服务，或切换中文 / English";
            case 10105:
                return "讯飞鉴权失败：请检查 APPID / APIKey / APISecret 是否匹配，以及设备时间是否准确";
            case 10106:
                return "讯飞参数错误：请检查语言或音频格式（需 16kHz / 16bit 单声道）";
            case 10313:
                return "讯飞音频格式不匹配：需 16kHz / 16bit 单声道 PCM";
            case 10165:
                return "讯飞未识别到有效语音，请再说一次";
            default:
                String extra = (raw == null || raw.trim().isEmpty())
                        ? ("code " + code) : (raw + " (code " + code + ")");
                return "讯飞识别错误：" + extra;
        }
    }

    /** 从一帧响应的 result.ws[].cw[0].w 拼出该片段文本（中英文智能拼接）。 */
    private String extractXunfeiText(JSONObject result) throws JSONException {
        JSONArray wsArr = result.optJSONArray("ws");
        if (wsArr == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wsArr.length(); i++) {
            JSONObject w = wsArr.getJSONObject(i);
            JSONArray cw = w.optJSONArray("cw");
            if (cw != null && cw.length() > 0) {
                appendSmart(sb, cw.getJSONObject(0).optString("w", ""));
            }
        }
        return sb.toString();
    }

    /** 按 sn 顺序拼接各片段；拉丁字母词之间补空格，中文不补。 */
    private static String joinSegments(java.util.TreeMap<Integer, String> segs) {
        StringBuilder sb = new StringBuilder();
        for (String s : segs.values()) {
            if (s == null || s.isEmpty()) {
                continue;
            }
            appendSmart(sb, s);
        }
        return sb.toString();
    }

    private static void appendSmart(StringBuilder sb, String piece) {
        if (piece == null || piece.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            char prev = sb.charAt(sb.length() - 1);
            char next = piece.charAt(0);
            if (isLatinWordChar(prev) && isLatinWordChar(next)) {
                sb.append(' ');
            }
        }
        sb.append(piece);
    }

    private static boolean isLatinWordChar(char c) {
        if (c >= 'a' && c <= 'z') return true;
        if (c >= 'A' && c <= 'Z') return true;
        if (c >= '0' && c <= '9') return true;
        if (c == '\'') return true;
        return c >= 'À' && c <= 'ÿ' && c != '×' && c != '÷';
    }

    // ==================================================================
    //  录音
    // ==================================================================

    private int audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION;

    private AudioRecord openRecorder() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf * 4, SAMPLE_RATE); // 至少 0.5s，抗抖动
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int src : sources) {
            try {
                AudioRecord rec = new AudioRecord(src, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioSource = src;
                    Log.i(TAG, "AudioRecord 就绪 source=" + src + " buf=" + bufSize);
                    return rec;
                }
                rec.release();
            } catch (SecurityException e) {
                Log.e(TAG, "缺少录音权限", e);
                return null;
            } catch (Exception e) {
                Log.w(TAG, "AudioRecord source=" + src + " 失败: " + e.getMessage());
            }
        }
        return null;
    }

    private void releaseRecorder() {
        AudioRecord rec = recorder;
        recorder = null;
        if (rec == null) {
            return;
        }
        try {
            if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                rec.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            rec.release();
        } catch (Exception ignored) {
        }
    }

    private static double calcRms(short[] buf, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (double) buf[i] * buf[i];
        }
        return Math.sqrt(sum / n);
    }

    private static byte[] toLittleEndianBytes(short[] buf, int n) {
        byte[] out = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            out[i * 2] = (byte) (buf[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xFF);
        }
        return out;
    }

    // ==================================================================
    //  Google 云端 STT（备用通道：整段录音后上传）
    // ==================================================================

    private void runGoogleBatch() {
        try {
            AudioRecord rec = openRecorder();
            if (rec == null) {
                emitError("麦克风不可用，请检查设备音频输入");
                return;
            }
            recorder = rec;
            rec.startRecording();
            if (listener != null) {
                listener.onReady();
                listener.onPartialResult("🎤 聆听中…");
            }
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            short[] buf = new short[FRAME_SAMPLES];
            long startMs = System.currentTimeMillis();
            long lastLoudMs = startMs;
            boolean hasVoice = false;
            while (recording) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                pcm.write(toLittleEndianBytes(buf, n));
                long now = System.currentTimeMillis();
                if (calcRms(buf, n) > RMS_SPEECH_THRESHOLD) {
                    hasVoice = true;
                    lastLoudMs = now;
                }
                if (hasVoice && now - lastLoudMs > 1500) break;
                if (now - startMs > MAX_SESSION_MS) break;
            }
            releaseRecorder();
            recording = false;

            byte[] data = pcm.toByteArray();
            if (data.length < 3200) {
                if (listener != null) listener.onFinalResult("");
                return;
            }
            byte[] wav = wrapWav(data);
            String json = buildRequestBody(wav, bcp47Language);
            String response = postJson(getEndpoint(), json);
            String transcript = parseResponse(response);
            if (listener != null) {
                listener.onFinalResult(transcript == null ? "" : transcript.trim());
            }
        } catch (SocketTimeoutException e) {
            emitError("识别超时，请检查网络后重试");
        } catch (Exception e) {
            Log.e(TAG, "google batch failed", e);
            emitError("识别失败：" + e.getMessage());
        }
    }

    private String getEndpoint() {
        return "https://speech.googleapis.com/v1/speech:recognize?key=" + SpeechConfig.CLOUD_API_KEY;
    }

    private String buildRequestBody(byte[] wavBytes, String languageCode) throws JSONException {
        JSONObject config = new JSONObject();
        config.put("encoding", "LINEAR16");
        config.put("sampleRateHertz", SAMPLE_RATE);
        config.put("languageCode", languageCode);
        config.put("model", "latest_short");
        config.put("enableAutomaticPunctuation", false);

        JSONObject audio = new JSONObject();
        audio.put("content", Base64.encodeToString(wavBytes, Base64.NO_WRAP));

        JSONObject root = new JSONObject();
        root.put("config", config);
        root.put("audio", audio);
        return root.toString();
    }

    private String parseResponse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (!root.has("results")) {
            return "";
        }
        JSONArray results = root.getJSONArray("results");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            JSONObject alt = results.getJSONObject(i).optJSONArray("alternatives").optJSONObject(0);
            if (alt != null) {
                sb.append(alt.optString("transcript", ""));
            }
        }
        return sb.toString();
    }

    private String postJson(String endpoint, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) {
                bos.write(b, 0, n);
            }
            is.close();
        }
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code + ": " + bos.toString("UTF-8"));
        }
        return bos.toString("UTF-8");
    }

    private byte[] wrapWav(byte[] pcm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 36 + pcm.length);
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, 16);
        writeShort(out, (short) 1);
        writeShort(out, (short) 1);
        writeInt(out, SAMPLE_RATE);
        writeInt(out, SAMPLE_RATE * 2);
        writeShort(out, (short) 2);
        writeShort(out, (short) 16);
        out.write("data".getBytes(StandardCharsets.US_ASCII));
        writeInt(out, pcm.length);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, short v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    // ==================================================================

    private void emitError(final String message) {
        recording = false;
        if (listener != null) {
            listener.onError(message);
        }
    }

    @Override
    public void stop() {
        if (!recording) {
            Log.d(TAG, "stop(): 未在录音，忽略");
            return;
        }
        Log.i(TAG, "stop(): 用户/上层请求停止");
        recording = false;
        // 会话线程会在 100ms 内退出采集循环、发送结束帧并回调结果；
        // 若线程卡在网络上，6 秒后强制断开连接把它踢出来。
        final Thread t = sessionThread;
        final XunfeiWebSocket current = ws;
        if (t != null) {
            Thread watchdog = new Thread(() -> {
                try {
                    t.join(6000);
                } catch (InterruptedException ignored) {
                    return;
                }
                if (t.isAlive() && current != null) {
                    Log.w(TAG, "stop(): 会话线程超时未结束，强制关闭连接");
                    current.close();
                }
            }, "AsrStopWatchdog");
            watchdog.setDaemon(true);
            watchdog.start();
        }
    }

    private void closeWsQuietly() {
        XunfeiWebSocket s = ws;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void release() {
        recording = false;
        closeWsQuietly();
        releaseRecorder();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
