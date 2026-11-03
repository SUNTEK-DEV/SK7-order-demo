package com.suntek.checkoutapplication;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;

import androidx.activity.EdgeToEdge;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.suntek.checkoutapplication.Product;
import com.suntek.checkoutapplication.ProductAdapter;
import com.suntek.checkoutapplication.OrderItem;
import com.suntek.checkoutapplication.OrderAdapter;
import com.printer.sdk.BarcodeCreater;
import com.printer.sdk.zxing.EncodeHintType;
import com.printer.sdk.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity
        implements ProductAdapter.OnProductClickListener,
        OrderAdapter.OnQuantityChangeListener {

    private static final String TAG = "MainActivity";
    private static final String ACTION_USB_PERMISSION = "com.suntek.checkoutapplication.USB_PERMISSION";
    private static final long SCAN_TIMEOUT_MS = 200L;
    private static final double TAX_RATE = 0.05;
    private static final String RECEIPT_QR_URL = "https://www.suntekai.com/";

    private static final String CATEGORY_DRINKS = "Drinks";
    private static final String CATEGORY_SNACKS = "Snacks";
    private static final String CATEGORY_DAILY_NEEDS = "Daily Needs";
    private static final String CATEGORY_FRESH = "Fresh";

    // Peripherals are powered but slow to settle after a cold boot: probe the
    // printer bus and the LED board again on this backoff instead of failing once.
    private static final long[] PERIPHERAL_RETRY_DELAYS_MS = {500, 1000, 2000, 4000, 8000};
    private static final int PAPER_WIDTH_DOTS = 576;
    private static final int QR_SIZE_DOTS = 160;
    private static final int MULTILINGUAL_QR_SIZE_DOTS = 208;
    private static final long AFTER_QR_DELAY_MS = 500L;

    private final StringBuilder scanResult = new StringBuilder();
    private final Handler scanHandler = new Handler();
    private final Runnable clearScanResult = () -> scanResult.setLength(0);
    private double pendingUnknownPrice;

    private RecyclerView recyclerProducts;
    private RecyclerView recyclerOrderItems;
    private TextView tvTotal;
    private TextView tvItemCount;
    private TextView tvStatus;
    private View btnPay;
    private View btnPrint;
    private View btnHold;
    private View btnClear;
    // Collapsible checkout panel
    private View panelContent;
    private View panelHeader;
    private View panelToggleArrow;
    private View tvClearCart;
    // NFC "tap your card" prompt (shakes while waiting for the card)
    private View nfcPrompt;
    private View nfcScrim;
    private View ivNfcPrompt;
    private TextView tvNfcPromptLabel;
    private AnimatorSet nfcPromptAnimator;
    private boolean nfcPromptWanted = false;
    private boolean panelExpanded = false;
    private ValueAnimator panelAnimator;
    private static final long PANEL_ANIM_DURATION = 250L;

    // ============ Voice ordering (multilingual, online) ============
    private IVoiceRecognizer voiceRecognizer;
    private VoiceAnnouncer voiceAnnouncer;
    private Spinner spinnerLanguage;
    private ImageButton btnMic;
    private TextView tvVoiceStatus;
    private String currentLangTag = "zh";
    private boolean isVoiceListening = false;
    private static final int REQUEST_RECORD_AUDIO = 2001;

    // 语音唤醒（离线关键词）
    private WakeWordDetector wakeDetector;
    private boolean wakeEnabled = SpeechConfig.WAKE_ENABLED_BY_DEFAULT;
    private boolean wakeLoading = false;
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());
    private String lastWakeKeyword = "";

    // ============ 语音支付（全程免接触） ============
    /** true 表示 Visa / Mastercard 卡片已弹出、正在等「确认支付 / check」。 */
    private volatile boolean voicePaymentPending = false;
    private static final long VOICE_PAYMENT_TIMEOUT_MS = 45000L;
    /** 播报结束后的静音起点，用于判断"真的安静下来了"再开麦。 */
    private long voiceQuietSinceMs = 0;
    /** true 表示"这轮播报念完就自动开麦"，与支付等待无关（唤醒欢迎语走这条）。 */
    private volatile boolean voiceRelistenArmed = false;
    private final Runnable voicePaymentTimeout = () -> {
        if (voicePaymentPending) {
            Log.w(TAG, "voicePaymentTimeout: no confirmation in "
                    + VOICE_PAYMENT_TIMEOUT_MS + "ms");
            cancelVoicePayment(true);
        }
    };
    /** 播报结束后自动重新开麦：支付确认、或唤醒欢迎语念完。 */
    private final Runnable voiceRelistenRunnable = new Runnable() {
        @Override
        public void run() {
            if (!voicePaymentPending && !voiceRelistenArmed) {
                return;
            }
            if (voiceAnnouncer != null && voiceAnnouncer.isBusy()) {
                voiceQuietSinceMs = 0;
                voiceHandler.postDelayed(this, 150);
                return;
            }
            // 讯飞合成是"先联网再出声"，isBusy 可能早于真实播放结束，
            // 所以要求安静满 700ms 再开麦，否则会把播报当成顾客说话。
            long now = android.os.SystemClock.uptimeMillis();
            if (voiceQuietSinceMs == 0) {
                voiceQuietSinceMs = now;
                voiceHandler.postDelayed(this, 150);
                return;
            }
            if (now - voiceQuietSinceMs < 700) {
                voiceHandler.postDelayed(this, 100);
                return;
            }
            if (isVoiceListening) {
                return;
            }
            if (ContextCompat.checkSelfPermission(MainActivity.this,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            Log.i(TAG, "voiceRelisten: reopen mic (paymentPending="
                    + voicePaymentPending + ")");
            voiceRelistenArmed = false;
            startVoiceListening();
        }
    };

    private List<Product> productList;
    private HashMap<String, OrderItem> orderItemMap;
    private List<OrderItem> orderItems;
    private ProductAdapter productAdapter;
    private OrderAdapter orderAdapter;
    private DecimalFormat decimalFormat;

    // Search + category filter
    private EditText etSearch;
    private View btnClearSearch;
    private final List<TextView> categoryChips = new ArrayList<>();
    private final List<String> categoryChipValues = new ArrayList<>();
    private String selectedCategory = ProductAdapter.CATEGORY_ALL;

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbPrinterConnection usbPrinter;
    // Cold boot: the USB stack enumerates the printer after onCreate has already run.
    private int usbRetryAttempt = 0;
    private final Runnable usbRetryRunnable = this::initUSBConnection;

    private LedLightController ledLight;
    private int ledRetryAttempt = 0;

    private NfcAdapter nfcAdapter;
    private PendingIntent nfcPendingIntent;
    private IntentFilter[] nfcIntentFilters;
    private String[][] nfcTechLists;
    private boolean nfcModeEnabled = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)
                    || UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                // The printer can show up long after onCreate on a cold boot.
                usbRetryAttempt = 0;
                if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    usbDevice = null;
                }
                initUSBConnection();
                return;
            }
            if (!ACTION_USB_PERMISSION.equals(action)) {
                return;
            }
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                Log.d(TAG, "USB permission granted: " + device.getDeviceName());
                usbDevice = device;
                connectPrinter();
            } else {
                // A grant that was still pending when the activity went away is
                // reported as denied here; keep retrying instead of giving up.
                Log.w(TAG, "USB permission response: denied");
                showStatus("USB permission denied");
                scheduleUsbRetry();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Enable full-screen mode
        enableFullScreen();
        
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        usbPrinter = new UsbPrinterConnection(usbManager);
        registerUsbReceiver();
        if (BuildConfig.DEBUG) {
            registerDebugVoiceReceiver();
        }

        initViews();
        initData();
        setupRecyclerViews();
        setupButtonListeners();
        updateOrderSummary();
        initUSBConnection();
        initNFC();
        initLedLight();
        initVoice();
        ensureRecordPermission();
    }

    /** 主动申请录音权限（语音唤醒要在启动后就能常驻监听）。 */
    private void ensureRecordPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "ensureRecordPermission: 申请 RECORD_AUDIO");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else if (wakeEnabled) {
            initWakeWord();
        }
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    // ============ 调试钩子（仅 debug 包） ============
    /**
     * adb 注入一句"识别结果"，用来在没有麦克风的场合验证意图路由：
     * adb shell am broadcast -a com.suntek.checkoutapplication.DEBUG_VOICE --es text "我要两个鸡肉卷"
     * 加 --ez speak true 则改成让机器把这句念出来（用于验证唤醒词）。
     */
    private static final String ACTION_DEBUG_VOICE = "com.suntek.checkoutapplication.DEBUG_VOICE";

    private final BroadcastReceiver debugVoiceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String text = intent.getStringExtra("text");
            boolean speak = intent.getBooleanExtra("speak", false);
            // --es wake "Hello, I'd like to order" 直接模拟一次唤醒（英文唤醒词模型听不出来时用）
            String wake = intent.getStringExtra("wake");
            // --es kwsTest "hello" [--es kwsVcn aisjiuxu]
            // 合成一段语音直接灌给唤醒模型，测「这个词到底能不能唤醒」。
            // 不能用外放测：板子的 VOICE_RECOGNITION 音源带 AEC，会把自己放的声音抵消掉。
            String kwsTest = intent.getStringExtra("kwsTest");
            String kwsAsr = intent.getStringExtra("kwsAsr");
            Log.i(TAG, "debugVoice: speak=" + speak + ", wake=[" + wake
                    + "], kwsTest=[" + kwsTest + "], kwsAsr=[" + kwsAsr + "], text=[" + text + "]");
            if (intent.getBooleanExtra("dlKws", false)) {
                new Thread(() -> {
                    if (wakeDetector == null) {
                        Log.w(TAG, "dlKws: 唤醒模块未就绪");
                        return;
                    }
                    wakeDetector.downloadEnglishModelForDebug();
                }, "KwsDownload").start();
                return;
            }
            if (kwsAsr != null && !kwsAsr.isEmpty()) {
                String vcnExtra = intent.getStringExtra("kwsVcn");
                final String vcn = (vcnExtra == null || vcnExtra.isEmpty()) ? "aisjiuxu" : vcnExtra;
                new Thread(() -> {
                    if (voiceAnnouncer == null || wakeDetector == null) {
                        Log.w(TAG, "kwsAsr: 语音模块未就绪");
                        return;
                    }
                    byte[] pcm = voiceAnnouncer.synthesizeToPcm(kwsAsr, vcn);
                    boolean en = intent.getBooleanExtra("kwsLangEn", false);
                    String heard = wakeDetector.debugTranscribe(pcm, en);
                    Log.i(TAG, "kwsAsr 结果: text=[" + kwsAsr + "] vcn=" + vcn
                            + " model=" + (en ? "en" : "zh") + " => " + heard);
                }, "KwsAsrThread").start();
                return;
            }
            if (kwsTest != null && !kwsTest.isEmpty()) {
                String vcnExtra = intent.getStringExtra("kwsVcn");
                final String vcn = (vcnExtra == null || vcnExtra.isEmpty()) ? "aisjiuxu" : vcnExtra;
                new Thread(() -> {
                    if (voiceAnnouncer == null || wakeDetector == null) {
                        Log.w(TAG, "kwsTest: 语音模块未就绪");
                        return;
                    }
                    byte[] pcm = voiceAnnouncer.synthesizeToPcm(kwsTest, vcn);
                    boolean en = intent.getBooleanExtra("kwsLangEn", false);
                    String hit = wakeDetector.debugDetect(pcm, en);
                    Log.i(TAG, "kwsTest 结果: text=[" + kwsTest + "] vcn=" + vcn
                            + " model=" + (en ? "en" : "zh")
                            + " => " + (hit == null ? "未命中" : hit));
                }, "KwsTestThread").start();
                return;
            }
            if (wake != null && !wake.isEmpty()) {
                runOnUiThread(() -> onWakeWordDetected(wake));
                return;
            }
            if (text == null || text.isEmpty()) {
                return;
            }
            if (speak) {
                if (voiceAnnouncer != null) {
                    // 跟随界面语言，否则念中文也用英文音色，实机测唤醒时会失真
                    voiceAnnouncer.announce(text, SpeechConfig.getLangByTag(currentLangTag).locale);
                }
                return;
            }
            runOnUiThread(() -> {
                if (isVoiceListening) {
                    stopVoiceListening();
                }
                finishVoiceRecognition(text);
            });
        }
    };

    private void registerDebugVoiceReceiver() {
        IntentFilter filter = new IntentFilter(ACTION_DEBUG_VOICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugVoiceReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(debugVoiceReceiver, filter);
        }
        Log.i(TAG, "debug voice receiver ready: " + ACTION_DEBUG_VOICE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nfcPromptAnimator != null) {
            nfcPromptAnimator.cancel();
            nfcPromptAnimator = null;
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Exception ignored) {
        }
        if (BuildConfig.DEBUG) {
            try {
                unregisterReceiver(debugVoiceReceiver);
            } catch (Exception ignored) {
            }
        }
        voiceHandler.removeCallbacks(voicePaymentTimeout);
        voiceHandler.removeCallbacks(voiceRelistenRunnable);
        if (usbPrinter != null) {
            usbPrinter.close();
        }
        if (ledLight != null) {
            ledLight.close();
        }
        if (voiceRecognizer != null) {
            voiceRecognizer.release();
        }
        if (wakeDetector != null) {
            voiceHandler.removeCallbacks(resumeWakeRunnable);
            wakeDetector.release();
            wakeDetector = null;
        }
        if (voiceAnnouncer != null) {
            voiceAnnouncer.shutdown();
        }
        scanHandler.removeCallbacks(clearScanResult);
        scanHandler.removeCallbacks(usbRetryRunnable);
    }

    // ============ LED status light ============

    private void initLedLight() {
        if (ledLight == null) {
            ledLight = new LedLightController(getApplicationContext());
        }
        ledLight.initAsync((success, path, ver) -> {
            if (success) {
                ledRetryAttempt = 0;
                showStatus("LED light ready: " + path + " (" + ver + ")");
                ledLight.turnOffAsync();
                if (usbPrinter != null && usbPrinter.isConnected()) {
                    ledPrinterConnected();
                }
            } else if (ledRetryAttempt < PERIPHERAL_RETRY_DELAYS_MS.length) {
                // Right after boot the board has not answered on the uart yet.
                long delay = PERIPHERAL_RETRY_DELAYS_MS[ledRetryAttempt++];
                showStatus("LED light not ready, retrying");
                scanHandler.postDelayed(this::initLedLight, delay);
            } else {
                showStatus("LED light not found");
            }
        });
    }

    private void ledPrinterConnected() {
        // solid blue = printer connected
        if (ledLight != null) {
            ledLight.setColorAsync(0x00, 0x00, 0xFF);
        }
    }

    private void ledPrinting() {
        // solid red = printing in progress
        if (ledLight != null) {
            ledLight.setColorAsync(0xFF, 0x00, 0x00);
        }
    }

    private void ledPaymentWaiting() {
        // blue blink while waiting for NFC card
        if (ledLight != null) {
            ledLight.blinkAsync(0x00, 0x00, 0xFF, 600, 400, 0, false);
        }
    }

    private void ledPaymentProcessing() {
        if (ledLight != null) {
            ledLight.setColorAsync(0x00, 0x00, 0xFF); // solid blue = processing
        }
    }

    private void ledPaymentSuccess() {
        if (ledLight != null) {
            ledLight.setColorAsync(0x00, 0xFF, 0x00); // solid green = success
        }
    }

    private void ledPaymentFailed() {
        if (ledLight != null) {
            ledLight.blinkAsync(0xFF, 0x00, 0x00, 600, 400, 0, false); // red blink = failure
        }
    }

    /**
     * Long-press on the status line: sweep the strip through red/green/blue and
     * then blank it, so the LED wiring can be checked without a sale.
     */
    private void runLedSelfTest() {
        final LedLightController led = ledLight;
        if (led == null) {
            showStatus("LED test: controller not created");
            initLedLight();
            return;
        }
        if (!led.isConnected()) {
            showStatus("LED test: opening port");
            led.initAsync((success, path, ver) -> {
                if (success) {
                    runLedSelfTest();
                } else {
                    showStatus("LED test: no LED port answered");
                }
            });
            return;
        }
        showStatus("LED test on " + led.getDevicePath() + " (" + led.getVersion() + ")");
        led.setColorAsync(0xFF, 0x00, 0x00);
        scanHandler.postDelayed(() -> led.setColorAsync(0x00, 0xFF, 0x00), 700);
        scanHandler.postDelayed(() -> led.setColorAsync(0x00, 0x00, 0xFF), 1400);
        scanHandler.postDelayed(led::turnOffAsync, 2100);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // While the search box is focused the user is typing on it:
        // let key events reach the EditText instead of the scanner buffer.
        if (etSearch != null && etSearch.isFocused()) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            char pressedKey = (char) event.getUnicodeChar();
            if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
                if (scanResult.length() > 0) {
                    String scannedCode = scanResult.toString();
                    handleScannedCode(scannedCode);
                    scanResult.setLength(0);
                    return true;
                }
            } else if (isValidScanCharacter(pressedKey)) {
                scanResult.append(pressedKey);
                scanHandler.removeCallbacks(clearScanResult);
                scanHandler.postDelayed(clearScanResult, SCAN_TIMEOUT_MS);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isValidScanCharacter(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '+' || c == '*' || c == '/';
    }

    private void handleScannedCode(String code) {
        Product matchedProduct = findProductById(code);
        if (matchedProduct == null) {
            matchedProduct = findProductByName(code);
        }
        if (matchedProduct != null) {
            onProductClick(matchedProduct);
            showToast("Added: " + matchedProduct.getName());
        } else {
            addUnknownProduct(code);
        }
    }

    /**
     * Unknown barcode: no dialog and no new card on the product grid —
     * the line is added straight to the checkout bill.
     */
    private void addUnknownProduct(String barcode) {
        OrderItem existing = orderItemMap.get(barcode);
        if (existing != null) {
            // Already on the bill: just add one more, the line keeps its price.
            onProductClick(existing.getProduct());
            showToast("Added: " + existing.getProduct().getName());
            return;
        }
        pendingUnknownPrice = 5.0 + (int) (Math.random() * 31);
        Product unknown = new Product(barcode, "new Product " + barcode, pendingUnknownPrice, R.drawable.goods);
        onProductClick(unknown);
        showToast("Added to bill: " + unknown.getName() + "  " + decimalFormat.format(pendingUnknownPrice));
    }

    private Product findProductById(String id) {//故障
        for (Product product : productList) {
            if (product.getId().equals(id)) {
                return product;
            }
        }
        return null;
    }

    private Product findProductByName(String name) {
        for (Product product : productList) {
            if (product.getName().equalsIgnoreCase(name.trim())) {
                return product;
            }
        }
        return null;
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void initViews() {
        recyclerProducts = findViewById(R.id.recycler_products);
        recyclerOrderItems = findViewById(R.id.recycler_order_items);
        tvTotal = findViewById(R.id.tv_total);
        tvItemCount = findViewById(R.id.tv_item_count);
        btnPay = findViewById(R.id.btn_pay);
        btnPrint = findViewById(R.id.btn_print);
        btnHold = findViewById(R.id.btn_hold);
        btnClear = findViewById(R.id.btn_clear);
        tvStatus = findViewById(R.id.tv_status);
        panelContent = findViewById(R.id.panel_content);
        panelToggleArrow = findViewById(R.id.btn_panel_toggle);
        panelHeader = findViewById(R.id.panel_header);
        tvClearCart = findViewById(R.id.tv_clear_cart);
        nfcPrompt = findViewById(R.id.nfc_prompt);
        nfcScrim = findViewById(R.id.nfc_scrim);
        ivNfcPrompt = findViewById(R.id.iv_nfc_prompt);
        tvNfcPromptLabel = findViewById(R.id.tv_nfc_prompt_label);
        findViewById(R.id.tv_shop_name).setOnLongClickListener(v -> {
            printMultilingualTestReceipt();
            return true;
        });
        // Long-press the status line to sweep the LED strip through R/G/B.
        tvStatus.setOnLongClickListener(v -> {
            runLedSelfTest();
            return true;
        });
        // Portrait-only search + category bar (the landscape layout has none).
        etSearch = findViewById(R.id.et_search);
        btnClearSearch = findViewById(R.id.btn_clear_search);
        addCategoryChip(R.id.chip_all, ProductAdapter.CATEGORY_ALL);
        addCategoryChip(R.id.chip_drinks, CATEGORY_DRINKS);
        addCategoryChip(R.id.chip_snacks, CATEGORY_SNACKS);
        addCategoryChip(R.id.chip_daily_needs, CATEGORY_DAILY_NEEDS);
        addCategoryChip(R.id.chip_fresh, CATEGORY_FRESH);
    }

    private void addCategoryChip(int viewId, String category) {
        TextView chip = findViewById(viewId);
        if (chip == null) {
            return;
        }
        categoryChips.add(chip);
        categoryChipValues.add(category);
    }

    private void initData() {
        decimalFormat = new DecimalFormat("$#,##0.00");
        productList = new ArrayList<>();
        productList.add(new Product("6907878050110", "Sweets", 3.00, R.drawable.sweets, CATEGORY_SNACKS));
        productList.add(new Product("2", "Sprite", 3.00, R.drawable.sprite, CATEGORY_DRINKS));
        productList.add(new Product("6944649700058", "Mineral Water", 2.00, R.drawable.water, CATEGORY_DRINKS));
        productList.add(new Product("4", "Hamburger", 15.00, R.drawable.flame, CATEGORY_SNACKS));
        productList.add(new Product("5", "Digital Exclusives", 8.00, R.drawable.digital, CATEGORY_DAILY_NEEDS));
        productList.add(new Product("6", "Fried Chicken", 12.00, R.drawable.chicken, CATEGORY_SNACKS));
        productList.add(new Product("7", "Honey Mustard Royal Crispy Wrap", 10.00, R.drawable.fish, CATEGORY_SNACKS));
        productList.add(new Product("8", "Ultimate Wrap Party", 10.00, R.drawable.party, CATEGORY_SNACKS));
        productList.add(new Product("9", "Chicken Fries", 8.00, R.drawable.chickenf, CATEGORY_SNACKS));
        productList.add(new Product("10", "Cake", 20.00, R.drawable.cake, CATEGORY_FRESH));
        productList.add(new Product("48511322", "Ciggie", 22.00, R.drawable.ciggy, CATEGORY_DAILY_NEEDS));
        productList.add(new Product("48511400", "Coffee", 4.50, R.drawable.coffe, CATEGORY_DRINKS));
        productList.add(new Product("48511500", "Tea", 3.50, R.drawable.tea, CATEGORY_DRINKS));

        orderItemMap = new HashMap<>();
        orderItems = new ArrayList<>();
    }

    private void initUSBConnection() {
        if (usbManager == null) {
            showStatus("Unable to get USB service");
            return;
        }
        if (usbPrinter != null && usbPrinter.isConnected()) {
            scanHandler.removeCallbacks(usbRetryRunnable);
            return;
        }
        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        UsbDevice printer = null;
        for (UsbDevice device : deviceList.values()) {
            Log.d(TAG, "USB device: " + describeUsbDevice(device));
            if (printer == null && UsbPrinterConnection.isPrinterDevice(device)) {
                printer = device;
            }
        }

        if (printer == null) {
            if (usbRetryAttempt < PERIPHERAL_RETRY_DELAYS_MS.length) {
                showStatus("USB printer not ready, retrying");
                scheduleUsbRetry();
            } else {
                showStatus("USB printer not found");
            }
            return;
        }

        usbDevice = printer;
        if (usbManager.hasPermission(usbDevice)) {
            connectPrinter();
        } else {
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE
            );
            usbManager.requestPermission(usbDevice, permissionIntent);
            showStatus("Grant USB permission in the dialog");
        }
    }

    /** Re-runs discovery until the printer shows up on the bus (cold boot is slow). */
    private void scheduleUsbRetry() {
        if (usbRetryAttempt >= PERIPHERAL_RETRY_DELAYS_MS.length) {
            return;
        }
        long delay = PERIPHERAL_RETRY_DELAYS_MS[usbRetryAttempt++];
        scanHandler.removeCallbacks(usbRetryRunnable);
        scanHandler.postDelayed(usbRetryRunnable, delay);
    }

    private String describeUsbDevice(UsbDevice device) {
        StringBuilder builder = new StringBuilder();
        builder.append(device.getDeviceName())
                .append(" VID:")
                .append(device.getVendorId())
                .append(" PID:")
                .append(device.getProductId())
                .append(" IFACES:")
                .append(device.getInterfaceCount());
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            builder.append(" [")
                    .append(i)
                    .append(":")
                    .append(device.getInterface(i).getInterfaceClass())
                    .append("/")
                    .append(device.getInterface(i).getInterfaceSubclass())
                    .append("]");
        }
        return builder.toString();
    }

    private void connectPrinter() {
        if (usbDevice == null) {
            showStatus("USB printer not selected");
            return;
        }
        String error = usbPrinter.connect(usbDevice);
        if (error == null) {
            showStatus("Printer connected");
            ledPrinterConnected();
        } else {
            showStatus("Printer connect failed: " + error);
        }
    }

    private void initNFC() {
        try {
            nfcAdapter = NfcAdapter.getDefaultAdapter(this);
            if (nfcAdapter == null) {
                showStatus("NFC not supported");
                return;
            }
            if (!nfcAdapter.isEnabled()) {
                showStatus("Enable NFC in system settings");
                return;
            }
            initNfcDispatcher();
        } catch (Exception e) {
            Log.e(TAG, "initNFC failed", e);
            showStatus("NFC init failed");
        }
    }

    private void initNfcDispatcher() {
        Intent intent = new Intent(this, getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        nfcPendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
        IntentFilter ndef = new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED);
        IntentFilter tag = new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED);
        IntentFilter tech = new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED);
        try {
            ndef.addDataType("*/*");
        } catch (Exception e) {
            Log.e(TAG, "addDataType failed", e);
        }
        nfcIntentFilters = new IntentFilter[]{ndef, tag, tech};
        nfcTechLists = new String[][]{
                new String[]{android.nfc.tech.Ndef.class.getName()},
                new String[]{android.nfc.tech.NfcA.class.getName()},
                new String[]{android.nfc.tech.NfcB.class.getName()},
                new String[]{android.nfc.tech.NfcF.class.getName()},
                new String[]{android.nfc.tech.NfcV.class.getName()},
                new String[]{android.nfc.tech.IsoDep.class.getName()},
                new String[]{android.nfc.tech.MifareClassic.class.getName()},
                new String[]{android.nfc.tech.MifareUltralight.class.getName()},
                new String[]{android.nfc.tech.NdefFormatable.class.getName()}
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcModeEnabled && nfcAdapter != null && nfcAdapter.isEnabled()) {
            try {
                nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, nfcIntentFilters, nfcTechLists);
            } catch (Exception e) {
                Log.e(TAG, "enableForegroundDispatch failed", e);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(this);
            } catch (Exception e) {
                Log.e(TAG, "disableForegroundDispatch failed", e);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (nfcModeEnabled &&
                (NfcAdapter.ACTION_TAG_DISCOVERED.equals(intent.getAction())
                        || NfcAdapter.ACTION_NDEF_DISCOVERED.equals(intent.getAction())
                        || NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction()))) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            if (tag != null) {
                handleNFCDetected(tag);
            }
        }
    }

    private void enableNFCForegroundDispatch() {
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            try {
                nfcAdapter.enableForegroundDispatch(this, nfcPendingIntent, nfcIntentFilters, nfcTechLists);
            } catch (Exception e) {
                Log.e(TAG, "enableNFCForegroundDispatch failed", e);
                showStatus("Enable NFC failed");
            }
        }
    }

    private void disableNFCForegroundDispatch() {
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(this);
            } catch (Exception e) {
                Log.e(TAG, "disableNFCForegroundDispatch failed", e);
            }
        }
    }

    private void handleNFCDetected(Tag tag) {
        hideNfcPrompt();
        // 贴卡支付也算完成，清掉"等语音确认"的状态
        voicePaymentPending = false;
        voiceHandler.removeCallbacks(voicePaymentTimeout);
        voiceHandler.removeCallbacks(voiceRelistenRunnable);
        if (isVoiceListening) {
            stopVoiceListening();
        }
        Toast.makeText(this, "NFC card detected, processing payment...", Toast.LENGTH_SHORT).show();
        nfcModeEnabled = false;
        disableNFCForegroundDispatch();
        ledPaymentProcessing();

        double subtotal = calculateSubtotal();
        double tax = calculateTax(subtotal);
        double total = calculateTotal(subtotal, tax);
        printReceipt("swipe card payment", subtotal, tax, total);
    }

    private void showStatus(String message) {
        runOnUiThread(() -> tvStatus.setText("Status: " + message));
    }

    private void setupRecyclerViews() {
        productAdapter = new ProductAdapter(productList, this);
        // Portrait: 2 large columns so food photos stay big and appetizing;
        // Landscape: 3 columns for the wider product pane.
        boolean isLandscape = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        recyclerProducts.setLayoutManager(new GridLayoutManager(this, isLandscape ? 3 : 2));
        recyclerProducts.setAdapter(productAdapter);

        orderAdapter = new OrderAdapter(orderItems, this);
        recyclerOrderItems.setLayoutManager(new LinearLayoutManager(this));
        recyclerOrderItems.setAdapter(orderAdapter);
    }

    private void setupButtonListeners() {
        btnPay.setOnClickListener(v -> handlePayment("cash payment"));
        btnPrint.setOnClickListener(v -> handlePaymentNFC("swipe card payment"));
        btnHold.setOnClickListener(v -> handleHoldOrder());
        btnClear.setOnClickListener(v -> clearOrder());
        if (tvClearCart != null) {
            tvClearCart.setOnClickListener(v -> clearOrder());
        }
        // Expand / collapse the bill: the whole header row AND the arrow toggle it,
        // so the collapsed bar offers one large touch target.
        View.OnClickListener toggleBill = v -> setOrderPanelExpanded(!panelExpanded);
        if (panelHeader != null) {
            panelHeader.setOnClickListener(toggleBill);
        }
        if (panelToggleArrow != null) {
            panelToggleArrow.setOnClickListener(toggleBill);
        }
        setupSearchAndCategoryFilter();
    }

    // ============ Search box + category chips ============
    private void setupSearchAndCategoryFilter() {
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (btnClearSearch != null) {
                        btnClearSearch.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                    }
                    applyProductFilter();
                }
            });
            etSearch.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    // Barcode scanning has its own hardware path; the search key
                    // only dismisses the keyboard and keeps the filtered grid.
                    hideKeyboard();
                    etSearch.clearFocus();
                    return true;
                }
                return false;
            });
        }
        if (btnClearSearch != null) {
            btnClearSearch.setOnClickListener(v -> {
                etSearch.setText("");
                etSearch.requestFocus();
            });
        }
        for (int i = 0; i < categoryChips.size(); i++) {
            final String category = categoryChipValues.get(i);
            categoryChips.get(i).setOnClickListener(v -> selectCategory(category));
        }
        updateChipStyles();
    }

    private void selectCategory(String category) {
        if (selectedCategory.equals(category)) {
            return;
        }
        selectedCategory = category;
        updateChipStyles();
        applyProductFilter();
        hideKeyboard();
    }

    private void updateChipStyles() {
        for (int i = 0; i < categoryChips.size(); i++) {
            TextView chip = categoryChips.get(i);
            boolean active = selectedCategory.equals(categoryChipValues.get(i));
            chip.setBackgroundResource(active ? R.drawable.chip_active_bg : R.drawable.chip_bg);
            chip.setTextColor(getResources().getColor(active ? R.color.white : R.color.brand_brown, null));
        }
    }

    private void applyProductFilter() {
        String query = etSearch != null ? etSearch.getText().toString() : "";
        productAdapter.filter(query, selectedCategory);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View focused = getCurrentFocus();
        if (imm != null && focused != null) {
            imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    // ============ Collapsible checkout panel ============
    private void setOrderPanelExpanded(final boolean expand) {
        if (panelContent == null || panelExpanded == expand) {
            return;
        }
        panelExpanded = expand;

        if (panelToggleArrow != null) {
            panelToggleArrow.animate()
                    .rotation(expand ? 180f : 0f)
                    .setDuration(PANEL_ANIM_DURATION)
                    .setInterpolator(expand ? new DecelerateInterpolator() : new AccelerateInterpolator())
                    .start();
            panelToggleArrow.setContentDescription(expand ? "Collapse order panel" : "Expand order panel");
        }

        panelContent.clearAnimation();
        if (panelAnimator != null) {
            panelAnimator.cancel();
            panelAnimator = null;
        }

        // "Clear" only shows while the bill is expanded, so the collapsed bar is a
        // single clean tap target.
        if (tvClearCart != null) {
            tvClearCart.setVisibility(expand ? View.VISIBLE : View.GONE);
        }

        if (expand) {
            // Measure the full height of the content, then animate 0 -> target.
            panelContent.setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams lp = panelContent.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            panelContent.setLayoutParams(lp);

            View parent = (View) panelContent.getParent();
            int width = parent != null ? parent.getWidth() : 0;
            if (width <= 0) {
                width = getResources().getDisplayMetrics().widthPixels;
            }
            panelContent.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            final int targetHeight = panelContent.getMeasuredHeight();

            ValueAnimator animator = ValueAnimator.ofInt(0, targetHeight);
            animator.setDuration(PANEL_ANIM_DURATION);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                ViewGroup.LayoutParams params = panelContent.getLayoutParams();
                params.height = (int) animation.getAnimatedValue();
                panelContent.setLayoutParams(params);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    ViewGroup.LayoutParams params = panelContent.getLayoutParams();
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    panelContent.setLayoutParams(params);
                }
            });
            panelAnimator = animator;
            animator.start();
        } else {
            // Animate the current height -> 0, then hide.
            final int startHeight = panelContent.getHeight();
            ValueAnimator animator = ValueAnimator.ofInt(startHeight, 0);
            animator.setDuration(PANEL_ANIM_DURATION);
            animator.setInterpolator(new AccelerateInterpolator());
            animator.addUpdateListener(animation -> {
                ViewGroup.LayoutParams params = panelContent.getLayoutParams();
                params.height = (int) animation.getAnimatedValue();
                panelContent.setLayoutParams(params);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    panelContent.setVisibility(View.GONE);
                    ViewGroup.LayoutParams params = panelContent.getLayoutParams();
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    panelContent.setLayoutParams(params);
                }
            });
            panelAnimator = animator;
            animator.start();
        }
    }

    // ============ NFC "tap your card" prompt ============
    /** Shows the NFC image in the middle of the screen, zooming in/out, to ask for a card tap. */
    private void showNfcPrompt() {
        if (nfcPrompt == null || ivNfcPrompt == null) {
            return;
        }
        nfcPromptWanted = true;
        nfcPrompt.animate().cancel();
        nfcPrompt.setVisibility(View.VISIBLE);
        nfcPrompt.setAlpha(1f);

        // 卡片下方提示随界面语言切换：贴卡和语音两种确认方式都说明白
        if (tvNfcPromptLabel != null) {
            switch (currentLangTag) {
                case "zh":
                    tvNfcPromptLabel.setText("贴卡，或说「确认支付」");
                    break;
                case "de":
                    tvNfcPromptLabel.setText("Karte vorhalten oder „bestätigen“");
                    break;
                default:
                    tvNfcPromptLabel.setText("Tap card, or say “check”");
                    break;
            }
        }

        // Dim the product background so the customer's eyes lock onto the shaking card.
        if (nfcScrim != null) {
            nfcScrim.animate().cancel();
            nfcScrim.setVisibility(View.VISIBLE);
            nfcScrim.setAlpha(0f);
            nfcScrim.animate().alpha(1f).setDuration(220).start();
        }

        if (nfcPromptAnimator != null) {
            nfcPromptAnimator.cancel();
        }

        // Zoom in / zoom out (scale pulse) instead of the old left/right wobble,
        // so the card keeps "breathing" at the customer's eyes.
        ObjectAnimator pulseX = ObjectAnimator.ofFloat(ivNfcPrompt, "scaleX", 0.9f, 1.09f);
        pulseX.setDuration(450);
        pulseX.setRepeatCount(ValueAnimator.INFINITE);
        pulseX.setRepeatMode(ValueAnimator.REVERSE);
        pulseX.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator pulseY = ObjectAnimator.ofFloat(ivNfcPrompt, "scaleY", 0.9f, 1.09f);
        pulseY.setDuration(450);
        pulseY.setRepeatCount(ValueAnimator.INFINITE);
        pulseY.setRepeatMode(ValueAnimator.REVERSE);
        pulseY.setInterpolator(new AccelerateDecelerateInterpolator());

        nfcPromptAnimator = new AnimatorSet();
        nfcPromptAnimator.playTogether(pulseX, pulseY);
        nfcPromptAnimator.start();
    }

    private void hideNfcPrompt() {
        if (nfcPrompt == null) {
            return;
        }
        nfcPromptWanted = false;
        if (nfcPromptAnimator != null) {
            nfcPromptAnimator.cancel();
            nfcPromptAnimator = null;
        }
        if (ivNfcPrompt != null) {
            ivNfcPrompt.setScaleX(1f);
            ivNfcPrompt.setScaleY(1f);
        }
        nfcPrompt.animate().cancel();
        nfcPrompt.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction(() -> {
                    // Never hide a prompt that was re-shown while fading out.
                    if (!nfcPromptWanted) {
                        nfcPrompt.setVisibility(View.GONE);
                        nfcPrompt.setAlpha(1f);
                    }
                })
                .start();

        if (nfcScrim != null) {
            nfcScrim.animate().cancel();
            nfcScrim.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        if (!nfcPromptWanted) {
                            nfcScrim.setVisibility(View.GONE);
                            nfcScrim.setAlpha(1f);
                        }
                    })
                    .start();
        }
    }

    private void handlePaymentNFC(String paymentMethod) {
        if (orderItems.isEmpty()) {
            Toast.makeText(this, "The order is empty, please add products first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (nfcAdapter == null) {
            showStatus("NFC not supported");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            showStatus("Enable NFC in settings");
            return;
        }
        nfcModeEnabled = true;
        enableNFCForegroundDispatch();
        showNfcPrompt();
        Toast.makeText(this, "Please tap card...", Toast.LENGTH_LONG).show();
        showStatus("Waiting for NFC card...");
        ledPaymentWaiting();
    }

    private void handleHoldOrder() {
        hideNfcPrompt();
        if (orderItems.isEmpty()) {
            Toast.makeText(this, "The order is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Order held", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onProductClick(Product product) {
        String productId = product.getId();
        if (orderItemMap.containsKey(productId)) {
            orderItemMap.get(productId).incrementQuantity();
        } else {
            OrderItem newOrderItem = new OrderItem(product);
            orderItemMap.put(productId, newOrderItem);
            orderItems.add(newOrderItem);
        }
        refreshOrderList();
    }

    // ============ Cart quantity +/- controls ============
    @Override
    public void onIncrease(OrderItem item) {
        item.incrementQuantity();
        refreshOrderList();
    }

    @Override
    public void onDecrease(OrderItem item) {
        if (item.getQuantity() > 1) {
            item.decrementQuantity();
        } else {
            // Quantity is already 1: removing the whole line from the order.
            removeOrderItem(item);
        }
        refreshOrderList();
    }

    private void removeOrderItem(OrderItem item) {
        orderItems.remove(item);
        String productId = item.getProduct().getId();
        // Only drop the map entry if it still points at this very line.
        if (orderItemMap.get(productId) == item) {
            orderItemMap.remove(productId);
        }
    }

    private void refreshOrderList() {
        orderAdapter.notifyDataSetChanged();
        updateOrderSummary();
    }

    private void updateOrderSummary() {
        double subtotal = calculateSubtotal();
        double tax = calculateTax(subtotal);
        double total = calculateTotal(subtotal, tax);
        tvTotal.setText(decimalFormat.format(total));

        int itemCount = 0;
        for (OrderItem item : orderItems) {
            itemCount += item.getQuantity();
        }
        tvItemCount.setText(itemCount + " items");
    }

    private double calculateSubtotal() {
        double subtotal = 0.0;
        for (OrderItem item : orderItems) {
            subtotal += item.getTotalPrice();
        }
        return subtotal;
    }

    private double calculateTax(double subtotal) {
        return subtotal * TAX_RATE;
    }

    private double calculateTotal(double subtotal, double tax) {
        return subtotal + tax;
    }

    private void handlePayment(String paymentMethod) {
        hideNfcPrompt();
        if (orderItems.isEmpty()) {
            Toast.makeText(this, "The order is empty, please add products first", Toast.LENGTH_SHORT).show();
            return;
        }
        double subtotal = calculateSubtotal();
        double tax = calculateTax(subtotal);
        double total = calculateTotal(subtotal, tax);
        Toast.makeText(this, "Selected " + paymentMethod + ", total: " + decimalFormat.format(total), Toast.LENGTH_LONG).show();
        printReceipt(paymentMethod, subtotal, tax, total);
    }

    private void printReceipt(String paymentMethod, double subtotal, double tax, double total) {
        if (usbPrinter == null || !usbPrinter.isConnected()) {
            showStatus("Printer not connected");
            return;
        }
        ledPrinting();
        new Thread(() -> {
            try {
                byte[] receipt = buildReceiptData(paymentMethod, subtotal, tax, total);
                int written = usbPrinter.write(receipt, 3000);
                boolean qrOk = written > 0 && printReceiptQr(QR_SIZE_DOTS);
                boolean cut = qrOk && cutPaper();
                runOnUiThread(() -> {
                    if (written > 0 && qrOk && cut) {
                        showStatus("Receipt printed successfully");
                        Toast.makeText(this, "Receipt printed successfully", Toast.LENGTH_SHORT).show();
                        clearOrder();
                        ledPaymentSuccess();
                    } else {
                        showStatus("Receipt print failed");
                        Toast.makeText(this, "Receipt print failed", Toast.LENGTH_LONG).show();
                        ledPaymentFailed();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showStatus("Receipt print failed: " + e.getMessage());
                    Toast.makeText(this, "Receipt print failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    ledPaymentFailed();
                });
            }
        }).start();
    }

    private byte[] buildReceiptData(String paymentMethod, double subtotal, double tax, double total) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);
        out.write(0x40);
        out.write(0x1B);
        out.write(0x61);
        out.write(0x01);
        out.write(0x1B);
        out.write(0x45);
        out.write(0x01);
        writeAscii(out, "SUNTEK CHECKOUT\n");
        out.write(0x1B);
        out.write(0x45);
        out.write(0x00);
        out.write(0x1B);
        out.write(0x61);
        out.write(0x00);
        writeAscii(out, "--------------------------------\n");
        writeAscii(out, "Order: " + System.currentTimeMillis() + "\n");
        writeAscii(out, "Time : " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
        writeAscii(out, "--------------------------------\n");
        writeAscii(out, "Item        Price  Qty   Total\n");
        writeAscii(out, "--------------------------------\n");
        for (OrderItem item : orderItems) {
            String name = item.getProduct().getName();
            if (name.length() > 10) {
                name = name.substring(0, 10);
            }
            String line = String.format(Locale.US, "%-10s %5.2f %4d %7.2f\n",
                    name,
                    item.getProduct().getPrice(),
                    item.getQuantity(),
                    item.getTotalPrice());
            writeAscii(out, line);
        }
        writeAscii(out, "--------------------------------\n");
        writeAscii(out, String.format(Locale.US, "Subtotal:           %7.2f\n", subtotal));
        writeAscii(out, String.format(Locale.US, "Tax(5%%):            %7.2f\n", tax));
        writeAscii(out, String.format(Locale.US, "Total:              %7.2f\n", total));
        writeAscii(out, "--------------------------------\n");
        writeAscii(out, "Payment: " + paymentMethod + "\n");
        writeAscii(out, "Thank you!\n");
        return out.toByteArray();
    }

    private void writeAscii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    // ============ Multilingual receipt test (SK1-15, 80mm) ============

    private static final Charset CP1252 = Charset.forName("Cp1252");

    private void writeText(ByteArrayOutputStream out, String text) {
        // Western mode: FS . enters, FS & exits (per SDK CodePageUtils)
        // ASCII 0x20-0x7F prints in either mode; CP1252 0x80-0xFF covers EU chars
        byte[] bytes = text.getBytes(CP1252);
        out.write(bytes, 0, bytes.length);
    }

    private void enterWesternMode(ByteArrayOutputStream out) {
        out.write(0x1C);
        out.write(0x2E);
        out.write(0x1B);
        out.write(0x74);
        out.write(16); // WCP1252 Latin I
    }

    private void exitWesternMode(ByteArrayOutputStream out) {
        out.write(0x1C);
        out.write(0x26);
    }

    private void printMultilingualTestReceipt() {
        if (usbPrinter == null || !usbPrinter.isConnected()) {
            showStatus("Printer not connected");
            return;
        }
        ledPrinting();
        new Thread(() -> {
            try {
                byte[] receipt = buildMultilingualTestReceipt();
                Log.i(TAG, "Multilingual receipt built, total bytes: " + receipt.length);
                long start = System.currentTimeMillis();
                int written = usbPrinter.write(receipt, 3000);
                boolean qrOk = written > 0 && printReceiptQr(MULTILINGUAL_QR_SIZE_DOTS);
                boolean cut = qrOk && cutPaper();
                Log.i(TAG, "write() returned: " + written + " of " + receipt.length + " bytes, qr=" + qrOk + ", cut=" + cut + ", took " + (System.currentTimeMillis() - start) + "ms");
                runOnUiThread(() -> {
                    if (written > 0 && qrOk && cut) {
                        showStatus("Multilingual receipt printed successfully");
                        Toast.makeText(this, "Multilingual receipt printed", Toast.LENGTH_SHORT).show();
                        ledPaymentSuccess();
                    } else {
                        Log.e(TAG, "Multilingual receipt print failed, write returned " + written);
                        showStatus("Multilingual receipt print failed");
                        Toast.makeText(this, "Multilingual receipt print failed", Toast.LENGTH_LONG).show();
                        ledPaymentFailed();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Multilingual receipt print exception", e);
                runOnUiThread(() -> {
                    showStatus("Multilingual print failed: " + e.getMessage());
                    Toast.makeText(this, "Multilingual print failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    ledPaymentFailed();
                });
            }
        }).start();
    }

    private byte[] buildMultilingualTestReceipt() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);
        out.write(0x40);
        enterWesternMode(out);
        center(out);
        bold(out, true);
        writeText(out, "SUNTEK\n");
        writeText(out, "MULTILINGUAL RECEIPT TEST\n");
        bold(out, false);
        writeText(out, "SK1-15 KIOSK\n");
        left(out);
        writeAscii(out, "------------------------------------------\n");
        writeText(out, "Document No.: MET-2026-0815-001\n");
        writeText(out, "Date: 15/08/2026\n");
        writeText(out, "Time: 16:55\n");
        writeText(out, "Currency: EUR\n");
        writeAscii(out, "------------------------------------------\n");
        writeAscii(out, "Description          Qty.           Total\n");
        writeAscii(out, "------------------------------------------\n");
        writeText(out, "Coffee                 1           2,50 €\n");
        writeText(out, "Sandwich               1           4,20 €\n");
        writeText(out, "Mineral Water           1           1,30 €\n");
        writeAscii(out, "------------------------------------------\n");
        writeAscii(out, "Subtotal                            8,00 €\n");
        writeText(out, "VAT / IVA / TVA / MwSt.             1,50 €\n");
        writeAscii(out, "------------------------------------------\n");
        writeAscii(out, "TOTAL                               8,00 €\n");
        writeAscii(out, "------------------------------------------\n");
        writeText(out, "Payment Method: Card\n");
        writeAscii(out, "==========================================\n");
        center(out);
        bold(out, true);
        writeText(out, "EUROPEAN LANGUAGE TEST\n");
        bold(out, false);
        writeAscii(out, "==========================================\n");
        left(out);
        writeText(out, "PORTUGUÊS\n");
        writeText(out, "Informação fiscal\n");
        writeText(out, "IVA incluído\n");
        writeText(out, "Solução de pagamento\n");
        writeText(out, "Saúde e atenção\n");
        writeText(out, "\n");
        writeText(out, "ESPAÑOL\n");
        writeText(out, "Información fiscal\n");
        writeText(out, "IVA incluido\n");
        writeText(out, "Facturación electrónica\n");
        writeText(out, "Atención al cliente\n");
        writeText(out, "\n");
        writeText(out, "FRANÇAIS\n");
        writeText(out, "Informations fiscales\n");
        writeText(out, "TVA incluse\n");
        writeText(out, "Reçu électronique\n");
        writeText(out, "Service clientèle\n");
        writeText(out, "\n");
        writeText(out, "DEUTSCH\n");
        writeText(out, "Steuerinformationen\n");
        writeText(out, "MwSt. enthalten\n");
        writeText(out, "Elektronische Rechnung\n");
        writeText(out, "Gebühren und Prüfung\n");
        writeText(out, "\n");
        writeText(out, "ITALIANO\n");
        writeText(out, "Informazioni fiscali\n");
        writeText(out, "IVA inclusa\n");
        writeText(out, "Ricevuta elettronica\n");
        writeText(out, "Città e servizi\n");
        writeText(out, "\n");
        writeText(out, "ENGLISH\n");
        writeText(out, "Fiscal information\n");
        writeText(out, "VAT included\n");
        writeText(out, "Electronic receipt\n");
        writeText(out, "Customer service\n");
        writeAscii(out, "------------------------------------------\n");
        center(out);
        bold(out, true);
        writeText(out, "Special Character Test:\n");
        bold(out, false);
        writeText(out, "á à â ã ä\n");
        writeText(out, "é è ê ë\n");
        writeText(out, "í ì î ï\n");
        writeText(out, "ó ò ô õ ö\n");
        writeText(out, "ú ù û ü\n");
        writeText(out, "ç ñ ß\n");
        writeText(out, "€  £\n");
        left(out);
        writeAscii(out, "------------------------------------------\n");
        center(out);
        writeText(out, "PRINT TEST\n");
        writeText(out, "FOR DEMO PURPOSES\n");
        writeText(out, "NO FISCAL VALUE\n");
        writeAscii(out, "------------------------------------------\n");
        left(out);
        writeText(out, "Powered by SUNTEK\n");
        writeText(out, "SK1-15 Printing Solution\n");
        writeText(out, "www.suntekai.com\n");
        exitWesternMode(out);
        return out.toByteArray();
    }

    private void center(ByteArrayOutputStream out) {
        out.write(0x1B);
        out.write(0x61);
        out.write(0x01);
    }

    private void left(ByteArrayOutputStream out) {
        out.write(0x1B);
        out.write(0x61);
        out.write(0x00);
    }

    private void bold(ByteArrayOutputStream out, boolean on) {
        out.write(0x1B);
        out.write(0x45);
        out.write(on ? 0x01 : 0x00);
    }

    private boolean printReceiptQr(int sizeDots) {
        if (usbPrinter == null || !usbPrinter.isConnected()) {
            return false;
        }
        if (!sleepQuietly(120)) {
            return false;
        }

        Bitmap qrBitmap = createQrBitmap(RECEIPT_QR_URL, sizeDots);
        if (qrBitmap == null) {
            Log.e(TAG, "QR bitmap create failed");
            return false;
        }

        try {
            byte[] imageBytes = bitmapToGsV0(qrBitmap);
            if (imageBytes.length == 0) {
                Log.e(TAG, "QR raster is empty");
                return false;
            }

            int leftMargin = Math.max(0, (PAPER_WIDTH_DOTS - qrBitmap.getWidth()) / 2);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeAscii(out, "\n");
            setLeftMargin(out, leftMargin);
            out.write(imageBytes, 0, imageBytes.length);
            setLeftMargin(out, 0);
            writeAscii(out, "\n");
            center(out);
            writeAscii(out, "www.suntekai.com\n");
            left(out);

            int written = usbPrinter.write(out.toByteArray(), 8000);
            Log.i(TAG, "printReceiptQr wrote " + written + " of " + out.size() + " bytes");
            if (written <= 0) {
                return false;
            }
            return sleepQuietly(AFTER_QR_DELAY_MS);
        } finally {
            qrBitmap.recycle();
        }
    }

    private Bitmap createQrBitmap(String content, int size) {
        Hashtable<EncodeHintType, Object> hints = new Hashtable<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        return BarcodeCreater.createCode(
                false,
                BarcodeCreater.QR_CODE,
                content,
                size,
                size,
                2,
                hints
        );
    }

    private byte[] bitmapToGsV0(Bitmap bitmap) {
        int srcWidth = bitmap.getWidth();
        int srcHeight = bitmap.getHeight();
        int dstWidth = ((srcWidth + 7) / 8) * 8;
        int bytesPerLine = dstWidth / 8;
        byte[] raster = new byte[bytesPerLine * srcHeight];
        int[] pixels = new int[srcWidth];

        for (int y = 0; y < srcHeight; y++) {
            bitmap.getPixels(pixels, 0, srcWidth, 0, y, srcWidth, 1);
            int rowOffset = y * bytesPerLine;
            for (int x = 0; x < srcWidth; x++) {
                if (isBlackPixel(pixels[x])) {
                    raster[rowOffset + x / 8] |= (byte) (0x80 >> (x % 8));
                }
            }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(8 + raster.length);
        out.write(0x1D);
        out.write(0x76);
        out.write(0x30);
        out.write(0x00);
        out.write(bytesPerLine & 0xFF);
        out.write((bytesPerLine >> 8) & 0xFF);
        out.write(srcHeight & 0xFF);
        out.write((srcHeight >> 8) & 0xFF);
        out.write(raster, 0, raster.length);
        return out.toByteArray();
    }

    private boolean isBlackPixel(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return r + g + b < 384;
    }

    private void setLeftMargin(ByteArrayOutputStream out, int dots) {
        out.write(0x1D);
        out.write(0x4C);
        out.write(dots & 0xFF);
        out.write((dots >> 8) & 0xFF);
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean cutPaper() {
        if (usbPrinter == null || !usbPrinter.isConnected()) {
            return false;
        }
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1B);
        out.write(0x64);
        out.write(0x05);
        out.write(0x1D);
        out.write(0x56);
        out.write(0x30);
        int written = usbPrinter.write(out.toByteArray(), 3000);
        Log.i(TAG, "cutPaper wrote " + written + " bytes");
        return written > 0;
    }

    private void clearOrder() {
        hideNfcPrompt();
        orderItemMap.clear();
        orderItems.clear();
        refreshOrderList();
        Toast.makeText(this, "Order cleared", Toast.LENGTH_SHORT).show();
    }

    // ============ Voice ordering (multilingual, online) ============

    private void initVoice() {
        spinnerLanguage = findViewById(R.id.spinner_language);
        btnMic = findViewById(R.id.btn_mic);
        tvVoiceStatus = findViewById(R.id.tv_voice_status);

        List<String> langDisplays = new ArrayList<>();
        for (SpeechConfig.Lang l : SpeechConfig.LANGUAGES) {
            langDisplays.add(l.display);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, langDisplays);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(adapter);

        int defaultPos = 0;
        for (int i = 0; i < SpeechConfig.LANGUAGES.size(); i++) {
            if (SpeechConfig.LANGUAGES.get(i).tag.equals(currentLangTag)) {
                defaultPos = i;
                break;
            }
        }
        spinnerLanguage.setSelection(defaultPos);
        applyLanguage(defaultPos);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyLanguage(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        btnMic.setOnClickListener(v -> onMicClick());
        // 长按麦克风 = 开关语音唤醒
        btnMic.setOnLongClickListener(v -> {
            toggleWake();
            return true;
        });

        // 云端在线识别（默认讯飞语音听写 iat，无需 GMS）
        voiceRecognizer = new CloudSpeechRecognizer();
        // 语音播报（讯飞在线合成，系统 TTS 兜底）
        voiceAnnouncer = new VoiceAnnouncer(this);
        voiceAnnouncer.init();
        Log.i(TAG, "播报状态：" + voiceAnnouncer.getDiagnostics());

        setVoiceStatus("语音点餐就绪（讯飞在线识别）");
    }

    // ============ 语音唤醒 ============

    /** 后台加载唤醒模型并开始常驻监听。 */
    private void initWakeWord() {
        if (!wakeEnabled) {
            setVoiceStatus("语音点餐就绪（唤醒已关闭，点麦克风说话；长按麦克风可开启）");
            return;
        }
        if (wakeLoading) {
            return;
        }
        if (wakeDetector != null && wakeDetector.isReady()) {
            startWakeListening();
            return;
        }
        wakeLoading = true;
        setVoiceStatus("正在加载唤醒模型…");
        new Thread(() -> {
            WakeWordDetector d = new WakeWordDetector(MainActivity.this);
            boolean ok = d.load();
            final String reason = d.getUnavailableReason();
            runOnUiThread(() -> {
                wakeLoading = false;
                if (!ok) {
                    Log.e(TAG, "唤醒不可用：" + reason);
                    setVoiceStatus("唤醒不可用：" + reason + "（仍可点麦克风说话）");
                    return;
                }
                wakeDetector = d;
                startWakeListening();
            });
        }, "WakeModelLoader").start();
    }

    private void startWakeListening() {
        if (!wakeEnabled || wakeDetector == null) {
            return;
        }
        if (!wakeDetector.isRunning()) {
            wakeDetector.start(new WakeWordDetector.Callback() {
                @Override
                public void onWakeWord(String keyword) {
                    runOnUiThread(() -> onWakeWordDetected(keyword));
                }

                @Override
                public void onUnavailable(String reason) {
                    runOnUiThread(() -> setVoiceStatus("唤醒不可用：" + reason));
                }
            });
        }
        if (!isVoiceListening) {
            setVoiceStatus(idleHint());
        }
    }

    /** 空闲状态提示语（说明当前怎么触发）。 */
    private String idleHint() {
        if (wakeEnabled) {
            return "🎙 待唤醒：说" + SpeechConfig.wakeHint() + "，或点麦克风";
        }
        return "点麦克风开始说话（长按麦克风可开启语音唤醒）";
    }

    private void onWakeWordDetected(String keyword) {
        lastWakeKeyword = keyword;
        Log.i(TAG, "onWakeWordDetected: " + keyword + ", listening=" + isVoiceListening);
        if (isVoiceListening) {
            return;
        }
        // 唤醒监听与识别都要占用麦克风，必须互斥
        if (wakeDetector != null) {
            wakeDetector.pause();
        }
        if (voiceAnnouncer != null) {
            voiceAnnouncer.stop(); // 万一正在播报，先掐掉，避免被自己的声音打断
        }
        // 英文唤醒词 → 自动切英文识别；中文唤醒词 → 切回中文。
        // 否则「Hello, I'd like to order」被中文模型识别出来全是乱码。
        boolean englishWake = keyword != null && keyword.matches(".*[A-Za-z]{2,}.*");
        switchRecognitionLanguage(englishWake ? "en" : "zh");
        // 支付类快捷唤醒词（"我要结算 / 我要买单" 等）：直接进入支付流程，
        // 不再播欢迎语——弹卡 + 念金额 + 持续监听确认，直到付款结束。
        if (SpeechConfig.isPaymentWake(keyword)) {
            Log.i(TAG, "支付类唤醒词，直接进入支付流程");
            startVoicePayment();
            return;
        }
        // 先回应一句"我准备好了"，等这句话念完再开麦，
        // 否则麦克风会把这句欢迎语当成顾客点的菜。
        announceWakeReady(englishWake);
        listenAgainAfterAnnouncement(300);
    }

    /**
     * 唤醒后的欢迎播报。语言跟唤醒词走：说中文唤醒词就中文回复，
     * 说英文唤醒词就英文回复，不受界面下拉框影响（下拉框的切换是异步的）。
     */
    private void announceWakeReady(boolean english) {
        String msg = english
                ? "I'm ready. What would you like to order?"
                : "我已经准备好了，你可以点餐了";
        setVoiceStatus((english ? "🔔 Awake — " : "🔔 已唤醒 — ") + msg);
        Log.i(TAG, "announceWakeReady: english=" + english + ", text=" + msg);
        if (voiceAnnouncer != null && voiceAnnouncer.isAvailable()) {
            voiceAnnouncer.announce(msg, english ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
        }
    }

    /** 唤醒语言和界面语言不一致时同步下拉框（顺便切换识别/播报语言）。 */
    private void switchRecognitionLanguage(String tag) {
        if (tag == null || tag.equals(currentLangTag) || spinnerLanguage == null) {
            return;
        }
        for (int i = 0; i < SpeechConfig.LANGUAGES.size(); i++) {
            if (SpeechConfig.LANGUAGES.get(i).tag.equals(tag)) {
                Log.i(TAG, "switchRecognitionLanguage: " + currentLangTag + " -> " + tag);
                spinnerLanguage.setSelection(i); // 触发 applyLanguage
                return;
            }
        }
    }

    private final Runnable resumeWakeRunnable = new Runnable() {
        @Override
        public void run() {
            if (!wakeEnabled || wakeDetector == null || isVoiceListening) {
                return;
            }
            // 播报期间不要监听，否则会把自己的声音当唤醒词
            if (voiceAnnouncer != null && voiceAnnouncer.isBusy()) {
                voiceHandler.postDelayed(this, 400);
                return;
            }
            wakeDetector.resume();
            if (!wakeDetector.isRunning()) {
                startWakeListening();
            } else {
                setVoiceStatus(idleHint());
            }
        }
    };

    /** 识别/播报结束后，延迟恢复唤醒监听。 */
    private void resumeWakeLater(long delayMs) {
        if (!wakeEnabled || wakeDetector == null) {
            return;
        }
        voiceHandler.removeCallbacks(resumeWakeRunnable);
        voiceHandler.postDelayed(resumeWakeRunnable, delayMs);
    }

    /** 长按麦克风：开关语音唤醒。 */
    private void toggleWake() {
        wakeEnabled = !wakeEnabled;
        Log.i(TAG, "toggleWake: enabled=" + wakeEnabled);
        if (wakeEnabled) {
            showToast("语音唤醒已开启：说 " + SpeechConfig.wakeHint());
            if (wakeDetector != null && wakeDetector.isReady()) {
                wakeDetector.resume();
                startWakeListening();
            } else {
                initWakeWord();
            }
        } else {
            showToast("语音唤醒已关闭");
            voiceHandler.removeCallbacks(resumeWakeRunnable);
            if (wakeDetector != null) {
                wakeDetector.pause();
            }
            setVoiceStatus(idleHint());
        }
    }

    private void applyLanguage(int position) {
        SpeechConfig.Lang lang = SpeechConfig.getLang(position);
        currentLangTag = lang.tag;
        if (voiceRecognizer != null) {
            voiceRecognizer.setLanguage(lang.bcp47);
        }
        if (voiceAnnouncer != null) {
            voiceAnnouncer.setLocale(lang.locale);
        }
        // 讯飞账号暂未开通德语许可（服务端返回 licc failed），提前给出提示
        if (SpeechConfig.PROVIDER == SpeechConfig.Provider.XUNFEI && "de".equals(lang.tag)) {
            setVoiceStatus("⚠ 德语需在讯飞控制台开通“小语种”服务（当前 licc failed），可先切 中文 / English");
        } else if (!isVoiceListening) {
            setVoiceStatus(idleHint());
        }
    }

    private void onMicClick() {
        Log.i(TAG, "onMicClick: listening=" + isVoiceListening);
        if (isVoiceListening) {
            stopVoiceListening();
        } else {
            int perm = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
            Log.i(TAG, "onMicClick: RECORD_AUDIO permission=" + perm
                    + " (GRANTED=" + PackageManager.PERMISSION_GRANTED + ")");
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "onMicClick: requesting RECORD_AUDIO permission");
                pendingMicAfterPermission = true;
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
                return;
            }
            startVoiceListening();
        }
    }

    private void startVoiceListening() {
        Log.i(TAG, "startVoiceListening: recognizer=" + voiceRecognizer + ", langTag=" + currentLangTag);
        if (voiceRecognizer == null) {
            Log.e(TAG, "startVoiceListening: voiceRecognizer is null!");
            return;
        }
        // 唤醒监听与识别共用麦克风，先让唤醒让出
        if (wakeDetector != null) {
            wakeDetector.pause();
            voiceHandler.removeCallbacks(resumeWakeRunnable);
        }
        isVoiceListening = true;
        if (btnMic != null) {
            btnMic.setColorFilter(Color.RED);
        }
        setVoiceStatus(voicePaymentPending
                ? "💳 聆听中：说「确认支付」或 check"
                : "🎤 聆听中…（说完停顿会自动结束）");
        voiceRecognizer.start(new IVoiceRecognizer.Listener() {
            @Override
            public void onReady() {
                Log.i(TAG, "voice: onReady");
            }

            @Override
            public void onPartialResult(String text) {
                Log.d(TAG, "voice: onPartial=[" + text + "]");
                runOnUiThread(() -> setVoiceStatus(text));
            }

            @Override
            public void onFinalResult(String text) {
                Log.i(TAG, "voice: onFinal=[" + text + "]");
                runOnUiThread(() -> finishVoiceRecognition(text));
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "voice: onError=" + message);
                runOnUiThread(() -> onVoiceError(message));
            }
        });
    }

    private void stopVoiceListening() {
        isVoiceListening = false;
        voiceRelistenArmed = false;
        if (voiceRecognizer != null) {
            voiceRecognizer.stop();
        }
        if (btnMic != null) {
            btnMic.clearColorFilter();
        }
    }

    private void finishVoiceRecognition(String text) {
        Log.i(TAG, "finishVoiceRecognition: text=[" + text + "]");
        isVoiceListening = false;
        if (btnMic != null) {
            btnMic.clearColorFilter();
        }
        if (text == null || text.trim().isEmpty()) {
            Log.w(TAG, "finishVoiceRecognition: empty transcript");
            setVoiceStatus("未识别到语音");
            announceNoMatch();
            resumeWakeLater(800);
            return;
        }
        setVoiceStatus("识别：" + text);
        handleVoiceUtterance(text);
    }

    /**
     * 一句识别结果进来后先判意图：
     *  - 正在等支付确认 → 只认「确认支付 / check」「取消 / cancel」；
     *  - 说「支付 / pay」→ 弹卡；
     *  - 其余按点餐处理。
     */
    private void handleVoiceUtterance(String text) {
        VoiceIntentParser.Intent intent = VoiceIntentParser.classify(text);
        Log.i(TAG, "handleVoiceUtterance: intent=" + intent
                + ", paymentPending=" + voicePaymentPending + ", text=[" + text + "]");
        setVoiceStatus("识别：" + text + "（" + intentLabel(intent) + "）");

        if (voicePaymentPending) {
            if (intent == VoiceIntentParser.Intent.CONFIRM) {
                confirmVoicePayment();
                return;
            }
            if (intent == VoiceIntentParser.Intent.CANCEL) {
                cancelVoicePayment(false);
                return;
            }
            // 说了别的（比如又点了东西、或听到自己的回声）：不打断支付，静默继续听。
            // 这里绝不能再播报"请说确认支付"，否则录到自己的声音会无限自问自答。
            Log.i(TAG, "payment pending: ignore utterance, keep listening");
            listenAgainAfterAnnouncement(400);
            return;
        }

        if (intent == VoiceIntentParser.Intent.PAY) {
            startVoicePayment();
            return;
        }

        processVoiceOrder(text);
        resumeWakeLater(1000);
    }

    private static String intentLabel(VoiceIntentParser.Intent intent) {
        switch (intent) {
            case PAY:
                return "支付";
            case CONFIRM:
                return "确认";
            case CANCEL:
                return "取消";
            case ORDER:
                return "点餐";
            default:
                return "未识别";
        }
    }

    // ============ 语音支付：pay → 卡片 → check → 打印 ============

    /** 说「支付 / pay」：弹出 Visa / Mastercard 卡片，等确认。 */
    private void startVoicePayment() {
        if (orderItems.isEmpty()) {
            Log.w(TAG, "startVoicePayment: order is empty");
            announceForCurrentLang(
                    "订单还是空的，请先点餐",
                    "Your order is empty, please order something first",
                    "Ihre Bestellung ist leer");
            resumeWakeLater(1200);
            return;
        }
        Log.i(TAG, "startVoicePayment: items=" + orderItems.size());
        voicePaymentPending = true;

        // 贴卡仍然可用：说 check 或直接刷卡都能完成，两条路都留着。
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            nfcModeEnabled = true;
            enableNFCForegroundDispatch();
        }
        showNfcPrompt();
        ledPaymentWaiting();
        showStatus("Waiting for payment confirmation... (say 确认支付 / check)");
        setVoiceStatus("💳 请说「确认支付」或 check，也可以直接贴卡");

        voiceHandler.removeCallbacks(voicePaymentTimeout);
        voiceHandler.postDelayed(voicePaymentTimeout, VOICE_PAYMENT_TIMEOUT_MS);

        // 注意：这里的播报不能出现"支付/确认/check"这类触发词，
        // 否则麦克风会把播报当成顾客的确认指令（自问自答）。
        double sum = calculateSubtotal();
        String amount = decimalFormat.format(calculateTotal(sum, calculateTax(sum)));
        announceForCurrentLang(
                "总共 " + amount + " 元，请看屏幕上的卡片",
                "Total " + amount + ", please check the card on screen",
                "Gesamtbetrag " + amount + ", bitte Karte auf dem Bildschirm prüfen");
        listenAgainAfterAnnouncement(300);
    }

    /** 说「确认支付 / check」：卡片收起 → 打印小票。 */
    private void confirmVoicePayment() {
        Log.i(TAG, "confirmVoicePayment");
        voicePaymentPending = false;
        voiceRelistenArmed = false;
        voiceHandler.removeCallbacks(voicePaymentTimeout);
        voiceHandler.removeCallbacks(voiceRelistenRunnable);
        // 先关麦：否则「支付成功…」的播报会被自己识别成又要支付
        if (isVoiceListening) {
            stopVoiceListening();
        }

        hideNfcPrompt();
        nfcModeEnabled = false;
        disableNFCForegroundDispatch();
        ledPaymentProcessing();

        double subtotal = calculateSubtotal();
        double tax = calculateTax(subtotal);
        double total = calculateTotal(subtotal, tax);
        Log.i(TAG, "confirmVoicePayment: total=" + total);
        announceForCurrentLang(
                "支付成功，正在打印小票",
                "Payment confirmed, printing your receipt",
                "Zahlung bestätigt, Bon wird gedruckt");
        printReceipt("voice card payment", subtotal, tax, total);
        resumeWakeLater(2500);
    }

    /** 说「取消 / cancel」或等太久：收起卡片，回到待唤醒。 */
    private void cancelVoicePayment(boolean byTimeout) {
        Log.i(TAG, "cancelVoicePayment: byTimeout=" + byTimeout);
        voicePaymentPending = false;
        voiceRelistenArmed = false;
        voiceHandler.removeCallbacks(voicePaymentTimeout);
        voiceHandler.removeCallbacks(voiceRelistenRunnable);
        if (isVoiceListening) {
            stopVoiceListening();
        }

        hideNfcPrompt();
        nfcModeEnabled = false;
        disableNFCForegroundDispatch();
        if (usbPrinter != null && usbPrinter.isConnected()) {
            ledPrinterConnected();
        }
        if (byTimeout) {
            announceForCurrentLang("支付超时，已取消", "Payment timed out, cancelled",
                    "Zeitüberschreitung, abgebrochen");
        } else {
            announceForCurrentLang("已取消支付", "Payment cancelled", "Zahlung abgebrochen");
        }
        resumeWakeLater(1200);
    }

    /** 播报结束后（含合成缓冲）再开麦，避免把自己的声音识别成指令。 */
    private void listenAgainAfterAnnouncement(long extraDelayMs) {
        voiceQuietSinceMs = 0;
        voiceRelistenArmed = true;
        voiceHandler.removeCallbacks(voiceRelistenRunnable);
        voiceHandler.postDelayed(voiceRelistenRunnable, Math.max(200L, extraDelayMs));
    }

    /** 按当前界面语言播报，同时更新状态栏。 */
    private void announceForCurrentLang(String zh, String en, String de) {
        String msg;
        switch (currentLangTag) {
            case "zh":
                msg = zh;
                break;
            case "de":
                msg = de;
                break;
            default:
                msg = en;
                break;
        }
        setVoiceStatus(msg);
        showStatus(msg);
        if (voiceAnnouncer != null && voiceAnnouncer.isAvailable()) {
            voiceAnnouncer.announce(msg, SpeechConfig.getLangByTag(currentLangTag).locale);
        }
    }

    private void onVoiceError(String message) {
        isVoiceListening = false;
        if (btnMic != null) {
            btnMic.clearColorFilter();
        }
        setVoiceStatus("⚠️ " + message);
        showToast(message);
        resumeWakeLater(1000);
    }

    /** 把识别文本解析成「数量+商品」并自动加购，再用对应语言播报。 */
    private void processVoiceOrder(String transcript) {
        List<VoiceOrderParser.OrderCommand> commands =
                VoiceOrderParser.parse(transcript, currentLangTag, productList);
        Log.i(TAG, "processVoiceOrder: lang=" + currentLangTag + ", transcript=[" + transcript
                + "], parsedCommands=" + commands.size());
        if (commands.isEmpty()) {
            Log.w(TAG, "processVoiceOrder: no product matched, products=" + (productList == null ? -1 : productList.size()));
            announceNoMatch();
            return;
        }
        for (VoiceOrderParser.OrderCommand cmd : commands) {
            Log.i(TAG, "processVoiceOrder: add " + cmd.displayName + " x" + cmd.quantity
                    + " (id=" + cmd.product.getId() + ")");
            addProduct(cmd.product, cmd.quantity);
        }
        // 展开订单面板，让用户看到已点内容
        setOrderPanelExpanded(true);

        String msg = buildVoiceMessage(commands);
        setVoiceStatus(msg);
        showToast(msg);
        if (voiceAnnouncer != null && voiceAnnouncer.isAvailable()) {
            voiceAnnouncer.announce(msg, SpeechConfig.getLangByTag(currentLangTag).locale);
        } else {
            // 无 TTS 引擎时给出提示，避免“没声音”却不知原因
            Log.w(TAG, "TTS unavailable: " + (voiceAnnouncer != null ? voiceAnnouncer.getDiagnostics() : ""));
        }
    }

    /** 一次加入多个数量的同一商品。 */
    private void addProduct(Product product, int count) {
        if (count <= 0) {
            count = 1;
        }
        String productId = product.getId();
        OrderItem item = orderItemMap.get(productId);
        if (item != null) {
            for (int i = 0; i < count; i++) {
                item.incrementQuantity();
            }
        } else {
            OrderItem newItem = new OrderItem(product);
            for (int i = 1; i < count; i++) {
                newItem.incrementQuantity();
            }
            orderItemMap.put(productId, newItem);
            orderItems.add(newItem);
        }
        refreshOrderList();
    }

    private String buildVoiceMessage(List<VoiceOrderParser.OrderCommand> commands) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < commands.size(); i++) {
            if (i > 0) {
                items.append("，");
            }
            // 注意：播报用文本不要带 "×"（TTS 会念成"乘号"），用空格 + 数字
            items.append(commands.get(i).displayName).append(" ")
                    .append(commands.get(i).quantity);
        }
        switch (currentLangTag) {
            case "zh":
                return "已添加：" + items;
            case "de":
                return "Hinzugefügt: " + items;
            default:
                return "Added: " + items;
        }
    }

    private void announceNoMatch() {
        String msg;
        switch (currentLangTag) {
            case "zh":
                msg = "没有识别到点餐内容，请重试";
                break;
            case "de":
                msg = "Kein Artikel erkannt, bitte erneut versuchen";
                break;
            default:
                msg = "No item recognized, please try again";
                break;
        }
        setVoiceStatus(msg);
        showToast(msg);
        if (voiceAnnouncer != null && voiceAnnouncer.isAvailable()) {
            voiceAnnouncer.announce(msg, SpeechConfig.getLangByTag(currentLangTag).locale);
        }
    }

    private void setVoiceStatus(String text) {
        if (tvVoiceStatus == null) {
            return;
        }
        final String value = text;
        runOnUiThread(() -> tvVoiceStatus.setText(value));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.i(TAG, "onRequestPermissionsResult: RECORD_AUDIO granted=" + granted
                    + ", grantResults.length=" + grantResults.length);
            if (granted) {
                if (pendingMicAfterPermission) {
                    pendingMicAfterPermission = false;
                    startVoiceListening();
                } else {
                    initWakeWord();
                }
            } else {
                setVoiceStatus("⚠️ 麦克风权限被拒绝（语音功能不可用）");
                showToast("需要录音权限才能使用语音点餐");
            }
        }
    }

    private boolean pendingMicAfterPermission = false;

    
    /**
     * Enable full-screen mode by hiding system bars (navigation bar and status bar).
     * Called in onWindowFocusChanged to ensure the window is fully attached.
     */
    private boolean fullscreenEnabled = false;
    
    private void enableFullScreen() {
        fullscreenEnabled = true;
        setSystemUIVisibility();
    }
    
    private void setSystemUIVisibility() {
        if (!fullscreenEnabled) return;
        
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                     | View.SYSTEM_UI_FLAG_FULLSCREEN
                     | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                     | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                     | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                     | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        decorView.setSystemUiVisibility(uiOptions);
        
        getWindow().setDecorFitsSystemWindows(false);
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && fullscreenEnabled) {
            // Re-apply fullscreen flags when window gains focus
            // This handles gestures that show system bars
            Handler handler = new Handler();
            handler.postDelayed(() -> setSystemUIVisibility(), 100);
        }
    }
}
