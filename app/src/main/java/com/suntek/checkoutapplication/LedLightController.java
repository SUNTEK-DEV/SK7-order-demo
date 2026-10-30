package com.suntek.checkoutapplication;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WS2812B LED status light controller (STC8G1K08A board, protocol V1).
 *
 * Talks to the LED board directly on /dev/ttySx (115200 8N1, binary):
 *
 *   request frame:  AA 55 VER CMD LEN PAYLOAD CRC16_L CRC16_H
 *   reply frame:    same, CMD = request CMD + 0x80, payload = status or data
 *   - VER fixed 01, LEN = payload length (max 16)
 *   - CRC16 = Modbus (poly 0xA001, init 0xFFFF) over VER + CMD + LEN + PAYLOAD,
 *     low byte first
 *
 * Commands used here:
 *   SET_STATIC    (01): R G B BRIGHTNESS  - solid color, protocol order is R G B
 *                       (firmware converts to WS2812B G R B itself)
 *   SET_LED_COUNT (04): COUNT_L COUNT_H   - little endian, 1..1024
 *   SET_POWER     (07): 00/01             - blank / restore display, keeps state
 *   GET_INFO      (09): read protocol version / max count / capability
 *   GET_STATUS    (08): read running state
 *
 * This mainboard's vendor binder service (com.sdkapi.sdkapiservice) has an
 * UNIMPLEMENTED serial API (open/write/read are empty stubs returning 0), so
 * the port is opened directly instead: SELinux is permissive and /dev/ttyS1
 * is 0666, which a normal app can open. Baud/termios is configured through
 * /system/bin/stty, then FileInputStream/FileOutputStream are used for I/O.
 *
 * The firmware has no blink command, so blinkAsync() is implemented app-side:
 * send the solid color once, then toggle SET_POWER on/off on a scheduler. A
 * generation counter cancels the previous blink whenever a new LED command is
 * issued.
 */
public class LedLightController {

    private static final String TAG = "LedLightController";

    private static final String STTY = "/system/bin/stty";

    // LED board UART: fixed 115200 8N1 per protocol spec
    private static final int BAUD_RATE = 115200;
    private static final long ACK_TIMEOUT_MS = 500;
    private static final long CMD_GAP_MS = 20;

    // This mainboard's app-accessible LED port (0666); others as fallback
    private static final String PREFERRED_PORT = "/dev/ttyS1";
    private static final String[] FALLBACK_PORTS = {
            "/dev/ttyS4", "/dev/ttyS8", "/dev/ttyS9", "/dev/ttyS3", "/dev/ttyS6", "/dev/ttyS7"
    };

    // Protocol constants
    private static final int FRAME_HEAD_0 = 0xAA;
    private static final int FRAME_HEAD_1 = 0x55;
    private static final int PROTOCOL_VERSION = 0x01;
    private static final int MAX_PAYLOAD = 16;

    private static final int CMD_SET_STATIC = 0x01;
    private static final int CMD_SET_LED_COUNT = 0x04;
    private static final int CMD_SET_POWER = 0x07;
    private static final int CMD_GET_STATUS = 0x08;
    private static final int CMD_GET_INFO = 0x09;

    private static final int POWER_OFF = 0x00;
    private static final int POWER_ON = 0x01;

    private static final int STATUS_OK = 0x00;

    public interface InitListener {
        void onInitResult(boolean success, String devicePath, String version);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    /** Bumped on every command; running blink tasks check it and self-cancel. */
    private final AtomicLong generation = new AtomicLong(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile FileInputStream uartIn;
    private volatile FileOutputStream uartOut;
    private volatile String devicePath;
    private volatile String version = "WS2812B";

    public LedLightController(Context context) {
        this.context = context.getApplicationContext();
    }

    // ==================== lifecycle ====================

    public void initAsync(InitListener listener) {
        executor.execute(() -> doInit(listener));
    }

    private void doInit(InitListener listener) {
        boolean ok = false;
        String found = null;
        String ver = "WS2812B";
        try {
            String port = openPort(PREFERRED_PORT);
            if (port == null) {
                for (String path : FALLBACK_PORTS) {
                    port = openPort(path);
                    if (port != null) {
                        break;
                    }
                }
            }
            if (port != null) {
                ok = true;
                found = port;
                // Recommended init sequence: GET_INFO -> GET_STATUS
                byte[] info = sendAndRead(buildFrame(CMD_GET_INFO, new byte[0]));
                if (info != null && info.length >= 4) {
                    int fwVersion = info[1] & 0xFF;
                    int maxCount = (info[3] & 0xFF) << 8 | (info[2] & 0xFF);
                    ver = "WS2812B-V" + fwVersion + " max" + maxCount;
                    Log.i(TAG, "GET_INFO ok: version=" + fwVersion + " maxCount=" + maxCount);
                } else {
                    ver = "WS2812B-NOACK";
                    Log.w(TAG, "GET_INFO no reply (board TX not wired or wrong port?)");
                }
                sendAndRead(buildFrame(CMD_GET_STATUS, new byte[0]));
            }
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
        }
        final boolean fOk = ok;
        final String fPath = found;
        final String fVer = ver;
        if (listener != null) {
            mainHandler.post(() -> listener.onInitResult(fOk, fPath, fVer));
        }
    }

    /** Configure termios and open streams; returns the path on success. */
    private String openPort(String path) {
        try {
            if (!configureUart(path)) {
                Log.e(TAG, "stty failed for " + path);
                return null;
            }
            uartIn = new FileInputStream(path);
            uartOut = new FileOutputStream(path);
            devicePath = path;
            Log.i(TAG, "opened " + path + " @ " + BAUD_RATE);
            return path;
        } catch (Exception e) {
            Log.w(TAG, "open " + path + " failed: " + e);
            uartIn = null;
            uartOut = null;
            devicePath = null;
            return null;
        }
    }

    private boolean configureUart(String path) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    STTY, "-F", path, String.valueOf(BAUD_RATE),
                    "cs8", "-cstopb", "-parenb", "raw", "-echo");
            pb.redirectErrorStream(true);
            p = pb.start();
            drainQuietly(p.getInputStream());
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            Log.e(TAG, "stty " + path + " failed", e);
            return false;
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
    }

    private static void drainQuietly(InputStream is) {
        try {
            byte[] buf = new byte[256];
            while (is.read(buf) > 0) {
                // discard
            }
        } catch (IOException ignored) {
        }
    }

    public void close() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        generation.incrementAndGet(); // stop any running blink
        executor.execute(() -> {
            if (uartOut != null) {
                // Blank the LEDs before closing the port
                try {
                    uartOut.write(buildFrame(CMD_SET_POWER, new byte[]{u8(POWER_OFF)}));
                    uartOut.flush();
                } catch (Exception e) {
                    Log.w(TAG, "turn off on exit failed", e);
                }
            }
            closeQuietly(uartIn);
            closeQuietly(uartOut);
            uartIn = null;
            uartOut = null;
        });
        scheduler.shutdownNow();
        executor.shutdown();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ==================== public API (async, safe from any thread) ====================

    public boolean isConnected() {
        return uartOut != null;
    }

    public String getDevicePath() {
        return devicePath;
    }

    public String getVersion() {
        return version;
    }

    /**
     * Solid LED color. Sends SET_STATIC with full brightness; the command
     * also switches the firmware back to static mode and turns the display
     * power back on, which stops any blink started by blinkAsync().
     */
    public void setColorAsync(int r, int g, int b) {
        generation.incrementAndGet();
        final byte[] frame = buildFrame(CMD_SET_STATIC, rgbBrightnessPayload(r, g, b, 0xFF));
        post(() -> sendCommand(frame));
    }

    /**
     * Blink the LEDs. The firmware has no blink command, so this sets the
     * solid color once and then toggles SET_POWER on the scheduler.
     *
     * @param onMs   lit phase duration in ms (min 50)
     * @param offMs  dark phase duration in ms (min 50)
     * @param count  number of dark phases, 0 = blink until next LED command
     * @param endOn  when count > 0 and the blink finishes, restore power
     */
    public void blinkAsync(int r, int g, int b, long onMs, long offMs, int count, boolean endOn) {
        final long gen = generation.incrementAndGet();
        final byte[] colorFrame = buildFrame(CMD_SET_STATIC, rgbBrightnessPayload(r, g, b, 0xFF));
        post(() -> sendCommand(colorFrame));
        final long on = Math.max(onMs, 50);
        final long off = Math.max(offMs, 50);
        scheduler.schedule(new BlinkTask(gen, on, off, count, endOn), on, TimeUnit.MILLISECONDS);
    }

    /** LEDs off (SET_POWER 00). Color/mode/brightness state is kept in the firmware. */
    public void turnOffAsync() {
        generation.incrementAndGet();
        final byte[] frame = buildFrame(CMD_SET_POWER, new byte[]{u8(POWER_OFF)});
        post(() -> sendCommand(frame));
    }

    /** Set the actual number of WS2812B beads (1..1024), little endian. */
    public void setLedCountAsync(int count) {
        generation.incrementAndGet();
        final int c = clamp(count, 1, 1024);
        final byte[] frame = buildFrame(CMD_SET_LED_COUNT, new byte[]{u8(c), u8(c >> 8)});
        post(() -> sendCommand(frame));
    }

    // ==================== blink engine ====================

    /** Toggles SET_POWER once per run() and reschedules itself until done. */
    private final class BlinkTask implements Runnable {
        private final long gen;
        private final long onMs;
        private final long offMs;
        private final boolean endOn;
        private boolean offPhase;
        private int remaining; // -1 = infinite

        BlinkTask(long gen, long onMs, long offMs, int count, boolean endOn) {
            this.gen = gen;
            this.onMs = onMs;
            this.offMs = offMs;
            this.remaining = count <= 0 ? -1 : count;
            this.endOn = endOn;
        }

        @Override
        public void run() {
            if (shutdown.get() || gen != generation.get()) {
                return;
            }
            if (offPhase) {
                // dark phase ends -> light back up
                post(() -> sendCommand(powerFrame(POWER_ON)));
                offPhase = false;
                scheduler.schedule(this, onMs, TimeUnit.MILLISECONDS);
                return;
            }
            // lit phase ends -> go dark
            post(() -> sendCommand(powerFrame(POWER_OFF)));
            offPhase = true;
            if (remaining > 0 && --remaining == 0) {
                if (endOn) {
                    scheduler.schedule(() -> {
                        if (!shutdown.get() && gen == generation.get()) {
                            post(() -> sendCommand(powerFrame(POWER_ON)));
                        }
                    }, offMs, TimeUnit.MILLISECONDS);
                }
                return;
            }
            scheduler.schedule(this, offMs, TimeUnit.MILLISECONDS);
        }
    }

    // ==================== protocol ====================

    private static byte[] powerFrame(int on) {
        return buildFrame(CMD_SET_POWER, new byte[]{u8(on)});
    }

    private static byte[] rgbBrightnessPayload(int r, int g, int b, int brightness) {
        return new byte[]{
                u8(r), u8(g), u8(b), u8(brightness)
        };
    }

    /**
     * Build a request frame: AA 55 VER CMD LEN PAYLOAD CRC16_L CRC16_H.
     * CRC is Modbus (init 0xFFFF, poly 0xA001) over VER CMD LEN PAYLOAD only.
     */
    private static byte[] buildFrame(int command, byte[] payload) {
        byte[] data = payload == null ? new byte[0] : payload;
        if (data.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException("payload too long: " + data.length);
        }
        byte[] body = new byte[3 + data.length];
        body[0] = u8(PROTOCOL_VERSION);
        body[1] = u8(command);
        body[2] = u8(data.length);
        System.arraycopy(data, 0, body, 3, data.length);

        int crc = crc16Modbus(body);
        byte[] frame = new byte[2 + body.length + 2];
        frame[0] = u8(FRAME_HEAD_0);
        frame[1] = u8(FRAME_HEAD_1);
        System.arraycopy(body, 0, frame, 2, body.length);
        frame[frame.length - 2] = u8(crc);        // low byte first
        frame[frame.length - 1] = u8(crc >> 8);   // high byte
        return frame;
    }

    private static int crc16Modbus(byte[] bytes) {
        int crc = 0xFFFF;
        for (byte value : bytes) {
            crc ^= (value & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc >>>= 1;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private static byte u8(int value) {
        return (byte) (value & 0xFF);
    }

    // ==================== internal ====================

    private void post(Runnable r) {
        if (shutdown.get()) {
            return;
        }
        try {
            executor.execute(() -> {
                if (!shutdown.get()) {
                    r.run();
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "post failed", e);
        }
    }

    /** Send one frame and wait for its ACK (status 00). */
    private void sendCommand(byte[] frame) {
        byte[] ack = sendAndRead(frame);
        if (ack != null) {
            int status = ack[0] & 0xFF;
            if (status != STATUS_OK) {
                Log.w(TAG, "LED nack status=" + status + " for cmd " + hex(frame));
            }
        } else {
            Log.w(TAG, "LED no ack for: " + hex(frame));
        }
        sleep(CMD_GAP_MS);
    }

    /**
     * Write a frame and read the reply frame with a timeout.
     * Returns the reply payload, or null when no valid reply arrived
     * (command is still assumed sent - the board may not have TX wired).
     */
    private byte[] sendAndRead(byte[] frame) {
        FileOutputStream out = uartOut;
        FileInputStream in = uartIn;
        if (out == null || in == null) {
            Log.w(TAG, "LED not connected, drop cmd: " + hex(frame));
            return null;
        }
        int expectedCmd = (frame[3] & 0xFF) | 0x80; // reply CMD = request + 0x80
        try {
            drainInput(in);
            out.write(frame);
            out.flush();
            Log.d(TAG, "LED tx: " + hex(frame));
            return readReplyFrame(in, expectedCmd, ACK_TIMEOUT_MS);
        } catch (IOException e) {
            Log.e(TAG, "LED write failed", e);
            return null;
        }
    }

    private static void drainInput(FileInputStream in) {
        try {
            byte[] sink = new byte[256];
            while (in.available() > 0) {
                int n = in.read(sink);
                if (n <= 0) {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Polls the uart for a complete reply frame:
     * AA 55 VER CMD LEN PAYLOAD CRC16_L CRC16_H with matching CMD and CRC.
     */
    private static byte[] readReplyFrame(FileInputStream in, int expectedCmd, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64];
        while (System.currentTimeMillis() < deadline) {
            try {
                if (in.available() > 0) {
                    int n = in.read(chunk);
                    if (n > 0) {
                        buf.write(chunk, 0, n);
                        byte[] payload = tryParseFrame(buf.toByteArray(), expectedCmd);
                        if (payload != null) {
                            return payload;
                        }
                    }
                } else {
                    sleep(5);
                }
            } catch (IOException e) {
                Log.e(TAG, "LED read failed", e);
                return null;
            }
        }
        if (buf.size() > 0) {
            Log.d(TAG, "LED rx garbage: " + hex(buf.toByteArray()));
        }
        return null;
    }

    /** Returns the reply payload when a complete valid frame is found, else null. */
    private static byte[] tryParseFrame(byte[] data, int expectedCmd) {
        for (int off = 0; off + 4 <= data.length; off++) {
            if ((data[off] & 0xFF) != FRAME_HEAD_0 || (data[off + 1] & 0xFF) != FRAME_HEAD_1) {
                continue;
            }
            if ((data[off + 2] & 0xFF) != PROTOCOL_VERSION) {
                continue;
            }
            int cmd = data[off + 3] & 0xFF;
            int len = data[off + 4] & 0xFF;
            if (len > MAX_PAYLOAD) {
                continue;
            }
            int total = 5 + len + 2;
            if (off + total > data.length) {
                continue; // incomplete, keep waiting
            }
            byte[] body = new byte[3 + len];
            System.arraycopy(data, off + 2, body, 0, body.length);
            int crc = crc16Modbus(body);
            int crcLo = data[off + 5 + len] & 0xFF;
            int crcHi = data[off + 6 + len] & 0xFF;
            if (crc != (crcHi << 8 | crcLo)) {
                continue; // bad CRC, skip this candidate header
            }
            if (cmd != expectedCmd) {
                Log.w(TAG, "LED reply cmd mismatch: got " + Integer.toHexString(cmd)
                        + " want " + Integer.toHexString(expectedCmd));
                return null;
            }
            byte[] payload = new byte[len];
            System.arraycopy(data, off + 5, payload, 0, len);
            return payload;
        }
        return null;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    private static int clamp(long v, int min, int max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return (int) v;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
