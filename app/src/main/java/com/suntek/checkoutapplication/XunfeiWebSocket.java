package com.suntek.checkoutapplication;

import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebSocket 客户端（手写实现，不依赖 OkHttp，保证离线也能编译）。
 *
 * 讯飞开放平台的在线能力（语音听写 iat、语音合成 tts）都走 WebSocket + HMAC-SHA256 签名，
 * 这里把「TLS 连接 + 签名握手 + 文本帧收发 + ping/pong/close」统一封装，
 * 供 {@link CloudSpeechRecognizer} 与 {@link XunfeiTtsEngine} 共用。
 *
 * 线程安全：{@link #sendText(String)} 内部加锁，允许录音线程发送的同时由另一个线程
 * 调用 {@link #readText()} 接收（流式识别必须这么做）。
 */
final class XunfeiWebSocket {

    private static final String TAG = "XunfeiWebSocket";

    private final String host;
    private final String path;
    private final int port;

    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private final SecureRandom random = new SecureRandom();
    private final Object sendLock = new Object();

    private volatile boolean closed = false;

    XunfeiWebSocket(String host, String path) {
        this(host, path, 443);
    }

    XunfeiWebSocket(String host, String path, int port) {
        this.host = host;
        this.path = path;
        this.port = port;
    }

    String getHost() {
        return host;
    }

    /** 建立连接并完成 WebSocket 握手（调用方需自行保证在后台线程执行）。 */
    void connect(String appId, String apiKey, String apiSecret) throws Exception {
        // 1) 生成签名：host / date / request-line
        String date = rfc1123();
        String signatureOrigin = "host: " + host + "\n"
                + "date: " + date + "\n"
                + "GET " + path + " HTTP/1.1";
        String signature = hmacSha256Base64(signatureOrigin, apiSecret);
        String authorizationOrigin = "api_key=\"" + apiKey + "\", algorithm=\"hmac-sha256\", "
                + "headers=\"host date request-line\", signature=\"" + signature + "\"";
        String authorization = Base64.encodeToString(
                authorizationOrigin.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String query = "authorization=" + URLEncoder.encode(authorization, "UTF-8")
                + "&date=" + URLEncoder.encode(date, "UTF-8")
                + "&host=" + host;

        // 2) 建立 TLS 连接
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) factory.createSocket(host, port);
        s.setSoTimeout(20000);
        try {
            javax.net.ssl.SSLParameters params = s.getSSLParameters();
            params.setServerNames(java.util.Collections.singletonList(
                    new javax.net.ssl.SNIHostName(host)));
            s.setSSLParameters(params);
        } catch (Exception e) {
            Log.w(TAG, "set SNI failed (ignored)", e);
        }
        Log.i(TAG, host + ": TLS handshake start");
        s.startHandshake();
        Log.i(TAG, host + ": TLS handshake ok, cipher=" + s.getSession().getCipherSuite());
        this.socket = s;
        this.in = new BufferedInputStream(s.getInputStream());
        this.out = s.getOutputStream();

        // 3) 发送 HTTP Upgrade 请求
        String req = "GET " + path + "?" + query + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Origin: https://" + host + "\r\n"
                + "\r\n";
        out.write(req.getBytes(StandardCharsets.UTF_8));
        out.flush();

        // 4) 读取握手响应
        String header = readHttpHeader();
        Log.i(TAG, host + ": upgrade response = "
                + (header == null ? "null" : header.split("\r\n")[0]));
        if (header == null || !header.contains("101")) {
            String firstLine = (header == null) ? "无响应" : header.split("\r\n")[0];
            throw new IOException("WebSocket 握手失败：" + firstLine);
        }
    }

    private String readHttpHeader() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int p3 = -1, p2 = -1, p1 = -1, c;
        while ((c = in.read()) != -1) {
            buf.write(c);
            if (p3 == '\r' && p2 == '\n' && p1 == '\r' && c == '\n') {
                return buf.toString("UTF-8");
            }
            p3 = p2;
            p2 = p1;
            p1 = c;
        }
        return null;
    }

    /** 发送一个带 mask 的文本帧（客户端必须 mask）。线程安全。 */
    void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81); // FIN=1, opcode=text
        if (payload.length <= 125) {
            frame.write(0x80 | payload.length);
        } else if (payload.length <= 65535) {
            frame.write(0x80 | 126);
            frame.write((payload.length >> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            long len = payload.length;
            for (int i = 7; i >= 0; i--) {
                frame.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i & 3]);
        }
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("连接已关闭");
            }
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    /** 读取一个完整文本消息；返回 null 表示连接关闭。自动处理 ping/pong/close 与分片。 */
    String readText() throws IOException {
        while (true) {
            int b1 = in.read();
            if (b1 == -1) return null;
            int b2 = in.read();
            if (b2 == -1) return null;

            boolean fin = (b1 & 0x80) != 0;
            int opcode = b1 & 0x0F;
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | readByte();
                }
            }
            byte[] maskKey = null;
            if (masked) {
                maskKey = new byte[4];
                readFully(maskKey, 0, 4);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload, 0, payload.length);
            if (maskKey != null) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= maskKey[i & 3];
                }
            }

            if (opcode == 0x8) {            // close
                return null;
            } else if (opcode == 0x9) {     // ping → pong
                sendControl(0xA, payload);
                continue;
            } else if (opcode == 0xA) {     // pong
                continue;
            } else if (opcode == 0x1 || opcode == 0x0) { // text / continuation
                if (fin) {
                    return new String(payload, StandardCharsets.UTF_8);
                }
                ByteArrayOutputStream acc = new ByteArrayOutputStream();
                acc.write(payload);
                boolean done = false;
                while (!done) {
                    int c1 = in.read();
                    if (c1 == -1) break;
                    int c2 = in.read();
                    if (c2 == -1) break;
                    boolean f = (c1 & 0x80) != 0;
                    boolean mk = (c2 & 0x80) != 0;
                    long l = c2 & 0x7F;
                    if (l == 126) {
                        l = ((long) readByte() << 8) | readByte();
                    } else if (l == 127) {
                        l = 0;
                        for (int i = 0; i < 8; i++) l = (l << 8) | readByte();
                    }
                    byte[] k = null;
                    if (mk) {
                        k = new byte[4];
                        readFully(k, 0, 4);
                    }
                    byte[] p = new byte[(int) l];
                    readFully(p, 0, p.length);
                    if (k != null) {
                        for (int i = 0; i < p.length; i++) p[i] ^= k[i & 3];
                    }
                    acc.write(p);
                    done = f;
                }
                return new String(acc.toByteArray(), StandardCharsets.UTF_8);
            }
            // 其它（binary 等）忽略，继续读
        }
    }

    private void sendControl(int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(0x80 | opcode);
        f.write(0x80 | (payload.length & 0x7F));
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask);
        for (int i = 0; i < payload.length; i++) {
            f.write(payload[i] ^ mask[i & 3]);
        }
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            out.write(f.toByteArray());
            out.flush();
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b == -1) throw new IOException("连接已断开");
        return b;
    }

    private void readFully(byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n == -1) throw new IOException("连接已断开");
            read += n;
        }
    }

    void close() {
        closed = true;
        try {
            if (out != null) {
                sendControlQuietly();
            }
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    private void sendControlQuietly() {
        try {
            byte[] f = new byte[]{0x00};
            // 直接写 close 帧，绕过 closed 标记（此时已置位）
            ByteArrayOutputStream frame = new ByteArrayOutputStream();
            frame.write(0x88);
            frame.write(0x80 | 1);
            byte[] mask = new byte[4];
            random.nextBytes(mask);
            frame.write(mask);
            frame.write(f[0] ^ mask[0]);
            synchronized (sendLock) {
                out.write(frame.toByteArray());
                out.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static String rfc1123() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
        return sdf.format(new Date());
    }

    private static String hmacSha256Base64(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec spec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(spec);
        byte[] sign = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(sign, Base64.NO_WRAP);
    }
}
