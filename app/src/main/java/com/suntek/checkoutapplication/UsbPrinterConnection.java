package com.suntek.checkoutapplication;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

class UsbPrinterConnection {

    private final UsbManager usbManager;

    private UsbDevice device;
    private UsbInterface printerInterface;
    private UsbDeviceConnection connection;
    private UsbEndpoint readEndpoint;
    private UsbEndpoint writeEndpoint;

    UsbPrinterConnection(UsbManager usbManager) {
        this.usbManager = usbManager;
    }

    static boolean isPrinterDevice(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            if (usbInterface.getInterfaceClass() == 7 && usbInterface.getInterfaceSubclass() == 1) {
                return true;
            }
        }
        return false;
    }

    String connect(UsbDevice usbDevice) {
        close();
        if (usbManager == null) {
            return "USB manager unavailable";
        }
        if (usbDevice == null) {
            return "USB device is null";
        }
        if (!usbManager.hasPermission(usbDevice)) {
            return "USB permission missing";
        }

        UsbInterface matchedInterface = null;
        UsbEndpoint inEndpoint = null;
        UsbEndpoint outEndpoint = null;

        for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            UsbInterface usbInterface = usbDevice.getInterface(i);
            if (usbInterface.getInterfaceClass() != 7 || usbInterface.getInterfaceSubclass() != 1) {
                continue;
            }
            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    continue;
                }
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    inEndpoint = endpoint;
                } else {
                    outEndpoint = endpoint;
                }
            }
            matchedInterface = usbInterface;
            if (outEndpoint != null) {
                break;
            }
        }

        if (matchedInterface == null || outEndpoint == null) {
            return "Printer interface not found";
        }

        UsbDeviceConnection openedConnection = usbManager.openDevice(usbDevice);
        if (openedConnection == null) {
            return "openDevice returned null";
        }
        if (!openedConnection.claimInterface(matchedInterface, true)) {
            openedConnection.close();
            return "claimInterface failed";
        }

        device = usbDevice;
        printerInterface = matchedInterface;
        connection = openedConnection;
        readEndpoint = inEndpoint;
        writeEndpoint = outEndpoint;
        return null;
    }

    boolean isConnected() {
        return connection != null && writeEndpoint != null;
    }

    private static final int MAX_TRANSFER_SIZE = 512;
    private static final long CHUNK_DELAY_MS = 80;
    private static final String TAG = "UsbPrinterConnection";

    int write(byte[] data, int timeoutMs) {
        if (!isConnected()) {
            Log.e(TAG, "write() called but printer not connected");
            return -1;
        }
        int offset = 0;
        int total = 0;
        int chunkCount = 0;
        while (offset < data.length) {
            int chunk = Math.min(MAX_TRANSFER_SIZE, data.length - offset);
            if (chunkCount > 0) {
                // Give the printer time to drain its buffer; streaming too fast
                // overflows it and bulkTransfer fails with -1.
                try {
                    Thread.sleep(CHUNK_DELAY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return total;
                }
            }
            int written = connection.bulkTransfer(writeEndpoint, data, offset, chunk, timeoutMs);
            if (written < 0) {
                Log.e(TAG, "bulkTransfer failed at offset " + offset + " (chunk " + chunk + "), result " + written);
                return written;
            }
            offset += written;
            total += written;
            chunkCount++;
            Log.d(TAG, "chunk " + chunkCount + ": wrote " + written + "/" + chunk + " bytes (total " + total + "/" + data.length + ")");
            if (written == 0) {
                Log.e(TAG, "bulkTransfer returned 0 at offset " + offset + ", aborting");
                break;
            }
        }
        return total;
    }

    void close() {
        if (connection != null) {
            try {
                if (printerInterface != null) {
                    connection.releaseInterface(printerInterface);
                }
            } catch (Exception ignored) {
            }
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
        device = null;
        printerInterface = null;
        connection = null;
        readEndpoint = null;
        writeEndpoint = null;
    }
}
