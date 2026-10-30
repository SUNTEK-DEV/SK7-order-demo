package com.suntek.checkoutapplication;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 讯飞【在线语音合成 tts】引擎。
 *
 * 为什么用它而不是系统 TextToSpeech / 离线 Sherpa：
 *  - 这台 RK 板没有系统 TTS 引擎（日志：TTS 引擎初始化失败 status=-1），系统播报根本不出声；
 *  - 离线 Sherpa 需要往 APK 里塞 100MB+ 的中文声学模型，且无法念英文；
 *  - 讯飞在线合成只需一次 WebSocket（与识别同一套签名），零模型体积，中英文都支持，
 *    实测本账号 zh(xiaoyan) / en(aisjiuxu) 均返回音频。
 *
 * 播放部分沿用了 myAPK 里 SherpaTtsEngine 的经验：
 *  ① 嵌入式板媒体音量常为 0 → 主动拉起来；
 *  ② 默认输出可能被路由到 HDMI 死 sink → 强制 setPreferredDevice 到板载扬声器；
 *  ③ 写完音频要等 playbackHeadPosition 追上再 release，否则缓冲区里的声音会被丢弃。
 */
public class XunfeiTtsEngine {

    private static final String TAG = "XunfeiTts";
    private static final String HOST = "tts-api.xfyun.cn";
    private static final String PATH = "/v2/tts";
    private static final int SAMPLE_RATE = 16000;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "XunfeiTtsThread");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger sessionSeq = new AtomicInteger(0);
    private volatile int activeSession = -1;
    private volatile XunfeiWebSocket activeWs;

    private volatile AudioTrack track;
    private final Object trackLock = new Object();

    public XunfeiTtsEngine(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    /** 当前是否正在播报。 */
    public boolean isSpeaking() {
        AudioTrack t = track;
        return t != null && t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING;
    }

    /**
     * 用指定发音人播报文本（后台执行，立即返回）。
     *
     * @param text 要念的文本
     * @param vcn  讯飞发音人，例如 xiaoyan（中文）/ aisjiuxu（英文）
     */
    public void speak(String text, String vcn) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        final int mySession = sessionSeq.incrementAndGet();
        activeSession = mySession;
        // 打断上一句
        stopCurrent();

        executor.execute(() -> doSpeak(text.trim(), vcn, mySession));
    }

    /** 打断当前播报。 */
    public void stop() {
        sessionSeq.incrementAndGet();
        stopCurrent();
    }

    private void stopCurrent() {
        XunfeiWebSocket ws = activeWs;
        activeWs = null;
        if (ws != null) {
            try {
                ws.close();
            } catch (Exception ignored) {
            }
        }
        stopTrack();
    }

    private void doSpeak(String text, String vcn, int mySession) {
        XunfeiWebSocket ws = null;
        ByteArrayOutputStream pcmAll = new ByteArrayOutputStream();
        try {
            if (isBlank(SpeechConfig.XUNFEI_APP_ID) || isBlank(SpeechConfig.XUNFEI_API_KEY)
                    || isBlank(SpeechConfig.XUNFEI_API_SECRET)) {
                Log.e(TAG, "讯飞凭证为空，无法合成");
                return;
            }
            ws = new XunfeiWebSocket(HOST, PATH);
            activeWs = ws;
            ws.connect(SpeechConfig.XUNFEI_APP_ID, SpeechConfig.XUNFEI_API_KEY,
                    SpeechConfig.XUNFEI_API_SECRET);
            Log.i(TAG, "tts connected, vcn=" + vcn + ", len=" + text.length());

            JSONObject root = new JSONObject();
            JSONObject common = new JSONObject();
            common.put("app_id", SpeechConfig.XUNFEI_APP_ID);
            root.put("common", common);

            JSONObject business = new JSONObject();
            business.put("aue", "raw");                       // 原始 PCM，省一次解码
            business.put("auf", "audio/L16;rate=16000");      // 16k / 16bit / 单声道
            business.put("vcn", vcn);
            business.put("speed", 50);
            business.put("volume", 80);
            business.put("pitch", 50);
            business.put("tte", "UTF8");
            root.put("business", business);

            JSONObject data = new JSONObject();
            data.put("status", 2); // 一次性合成（非分块文本）
            data.put("text", Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            root.put("data", data);

            ws.sendText(root.toString());

            AudioTrack at = ensureTrack();
            if (at == null) {
                Log.e(TAG, "AudioTrack 创建失败，放弃播报");
                return;
            }
            at.play();

            int totalBytes = 0;
            while (true) {
                if (activeSession != mySession) {
                    Log.i(TAG, "tts session superseded, abort");
                    break;
                }
                String msg = ws.readText();
                if (msg == null) {
                    break;
                }
                JSONObject json = new JSONObject(msg);
                int code = json.optInt("code", -1);
                if (code != 0) {
                    Log.e(TAG, "tts error code=" + code + ", msg=" + json.optString("message", ""));
                    break;
                }
                JSONObject d = json.optJSONObject("data");
                if (d == null) {
                    continue;
                }
                String audioB64 = d.optString("audio", "");
                if (!audioB64.isEmpty()) {
                    byte[] pcm = Base64.decode(audioB64, Base64.NO_WRAP);
                    pcmAll.write(pcm);
                    totalBytes += pcm.length;
                    at.write(pcm, 0, pcm.length);
                }
                if (d.optInt("status", 1) == 2) {
                    break;
                }
            }
            Log.i(TAG, "tts done, audio=" + totalBytes + " bytes ("
                    + (totalBytes / 32) + "ms)");

            // 等硬件真正播完，避免刚写完就 stop 导致尾音被丢弃
            drain(at, totalBytes);
        } catch (Exception e) {
            Log.e(TAG, "tts failed: " + e.getMessage(), e);
        } finally {
            if (ws != null) {
                try {
                    ws.close();
                } catch (Exception ignored) {
                }
            }
            if (activeSession == mySession) {
                stopTrack();
            }
        }
    }

    private void drain(AudioTrack at, int totalBytes) {
        int frames = totalBytes / 2;
        long durationMs = (long) frames * 1000L / SAMPLE_RATE;
        long deadline = System.currentTimeMillis() + durationMs + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (at.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                break;
            }
            if (at.getPlaybackHeadPosition() >= frames) {
                break;
            }
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private AudioTrack ensureTrack() {
        synchronized (trackLock) {
            if (track != null) {
                return track;
            }
            try {
                ensureMediaVolume();
                int minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufSize = Math.max(minBuf, SAMPLE_RATE * 2); // 至少 1 秒，抗抖动
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                routeToSpeaker(t);
                try {
                    t.setVolume(AudioTrack.getMaxVolume());
                } catch (Exception ignored) {
                }
                track = t;
                return t;
            } catch (Exception e) {
                Log.e(TAG, "create AudioTrack failed", e);
                return null;
            }
        }
    }

    private void stopTrack() {
        synchronized (trackLock) {
            if (track == null) {
                return;
            }
            AudioTrack t = track;
            track = null;
            try {
                if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    t.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                t.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** 板子默认媒体音量为 0 时拉起来，否则 AudioTrack 全部乘 0。 */
    private void ensureMediaVolume() {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            Log.i(TAG, "STREAM_MUSIC volume=" + cur + "/" + max);
            if (cur <= 0 && max > 0) {
                int target = Math.max(1, (int) (max * 0.8f));
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                Log.w(TAG, "媒体音量为 0，已强制设为 " + target);
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureMediaVolume failed: " + e.getMessage());
        }
    }

    /** 避免声音被路由到没接喇叭的 HDMI。 */
    private void routeToSpeaker(AudioTrack t) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                return;
            }
            android.media.AudioDeviceInfo[] devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
            if (devices == null || devices.length == 0) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (android.media.AudioDeviceInfo d : devices) {
                sb.append("type=").append(d.getType()).append(' ');
            }
            Log.d(TAG, "output devices: " + sb);
            android.media.AudioDeviceInfo preferred = null;
            int[] preferTypes = {2 /*SPEAKER*/, 4 /*WIRED_HEADPHONE*/, 3 /*WIRED_HEADSET*/, 1 /*EARPIECE*/};
            for (int type : preferTypes) {
                for (android.media.AudioDeviceInfo d : devices) {
                    if (d.getType() == type) {
                        preferred = d;
                        break;
                    }
                }
                if (preferred != null) {
                    break;
                }
            }
            if (preferred == null) {
                for (android.media.AudioDeviceInfo d : devices) {
                    if (d.getType() != 9 /*HDMI*/) {
                        preferred = d;
                        break;
                    }
                }
            }
            if (preferred != null) {
                boolean ok = t.setPreferredDevice(preferred);
                Log.d(TAG, "setPreferredDevice type=" + preferred.getType() + " ok=" + ok);
            }
        } catch (Exception e) {
            Log.w(TAG, "routeToSpeaker failed: " + e.getMessage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    public void release() {
        stop();
        executor.shutdownNow();
    }
}
