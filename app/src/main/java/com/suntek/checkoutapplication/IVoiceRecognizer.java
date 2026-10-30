package com.suntek.checkoutapplication;

/**
 * 语音识别器统一接口（回调风格参考 myAPK 的 VoskSpeechRecognizer）。
 *
 * 这样上层（MainActivity）只依赖接口，底层可以按需替换为：
 *  - 云端 STT（当前默认，见 CloudSpeechRecognizer）
 *  - 系统内置 SpeechRecognizer（需要 GMS）
 *  - 离线 Vosk（需要模型文件）
 */
public interface IVoiceRecognizer {

    /** 识别过程回调，全部在调用方线程之外触发，UI 操作需自行 post 到主线程。 */
    interface Listener {
        /** 录音已开始，可以提示用户说话 */
        void onReady();

        /** 实时（临时）状态文本，例如“🎤 聆听中…” */
        void onPartialResult(String text);

        /** 一句话/整段识别完成后的最终结果（已上传云端并返回文本） */
        void onFinalResult(String text);

        /** 发生错误（无权限、无网络、无 Key、识别失败等） */
        void onError(String message);
    }

    /** 设置识别语言，参数使用 BCP-47 编码，例如 "zh-CN" / "en-US" / "de-DE"。 */
    void setLanguage(String bcp47LanguageCode);

    /** 开始识别（录音）。结果通过 listener 回调。可重复调用（每次先自动 stop）。 */
    void start(Listener listener);

    /** 停止识别并上传音频到云端返回结果（会触发一次 onFinalResult）。多次调用安全。 */
    void stop();

    /** 释放麦克风与网络资源。 */
    void release();

    /** 当前是否正在录音/识别中。 */
    boolean isListening();
}
