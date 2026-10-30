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

import androidx.activity.EdgeToEdge;
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
    private AnimatorSet nfcPromptAnimator;
    private boolean nfcPromptWanted = false;
    private boolean panelExpanded = false;
    private ValueAnimator panelAnimator;
    private static final long PANEL_ANIM_DURATION = 250L;

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

        initViews();
        initData();
        setupRecyclerViews();
        setupButtonListeners();
        updateOrderSummary();
        initUSBConnection();
        initNFC();
        initLedLight();
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
        if (usbPrinter != null) {
            usbPrinter.close();
        }
        if (ledLight != null) {
            ledLight.close();
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
        findViewById(R.id.tv_shop_name).setOnLongClickListener(v -> {
            printMultilingualTestReceipt();
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
