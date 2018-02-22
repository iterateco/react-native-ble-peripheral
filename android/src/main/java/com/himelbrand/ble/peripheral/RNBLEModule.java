package com.himelbrand.ble.peripheral;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;


import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;


import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.facebook.react.bridge.NativeModule;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.common.MapBuilder;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.modules.core.DeviceEventManagerModule;

/**
 * {@link NativeModule} that allows JS to open the default browser
 * for an url.
 */
public class RNBLEModule extends ReactContextBaseJavaModule {
    private static final String LOG_TAG = "ReactNative";

    ReactApplicationContext reactContext;
    HashMap<String, BluetoothGattService> servicesMap;
    HashSet<BluetoothDevice> mBluetoothDevices;
    BluetoothManager mBluetoothManager;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothGattServer mGattServer;
    BluetoothLeAdvertiser advertiser;
    AdvertiseCallback advertisingCallback;
    boolean advertising;
    private Context context;

    private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    public RNBLEModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
        this.context = reactContext;
        this.servicesMap = new HashMap<String, BluetoothGattService>();
        this.advertising = false;
    }

    @Override
    public String getName() {
        return "BLEPeripheral";
    }

    @ReactMethod
    public void addService(String uuid, Boolean primary) {
        UUID SERVICE_UUID = UUID.fromString(uuid);
        int type = primary ? BluetoothGattService.SERVICE_TYPE_PRIMARY : BluetoothGattService.SERVICE_TYPE_SECONDARY;
        BluetoothGattService tempService = new BluetoothGattService(SERVICE_UUID, type);
        if(!this.servicesMap.containsKey(uuid))
            this.servicesMap.put(uuid, tempService);
    }

    @ReactMethod
    public void addCharacteristicToService(String serviceUUID, String uuid, Integer permissions, Integer properties) {
        UUID CHAR_UUID = UUID.fromString(uuid);
        boolean notify = (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY;

        BluetoothGattCharacteristic tempChar = new BluetoothGattCharacteristic(CHAR_UUID, properties, permissions);

        if (notify) {
            BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE | BluetoothGattDescriptor.PERMISSION_READ
            );
            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            tempChar.addDescriptor(descriptor);
        }

        
        this.servicesMap.get(serviceUUID).addCharacteristic(tempChar);
    }

    @ReactMethod
    public void setValue(String serviceUUID, String charUUID, ReadableArray value) {
        BluetoothGattCharacteristic characteristic = servicesMap.get(serviceUUID).getCharacteristic(UUID.fromString(charUUID));
        setCharacteristicValue(characteristic, value);
    }

    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
            Log.d(LOG_TAG, "RNBLEModule - onConnectionStateChange: " + status + " " + newState);

            WritableMap event = Arguments.createMap();
            event.putString("device", device.toString());

            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    mBluetoothDevices.add(device);
                    sendEvent("deviceConnected", event);
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    mBluetoothDevices.remove(device);
                    sendEvent("deviceDisconnected", event);
                }
            } else {
                mBluetoothDevices.remove(device);
                sendEvent("deviceDisconnected", event);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            // Log.d(LOG_TAG, "RNBLEModule - onCharacteristicReadRequest");

            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
            /* value (optional) */ null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, characteristic.getValue());
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            
            // Log.d(LOG_TAG, "RNBLEModule - onCharacteristicWriteRequest id=" + requestId + " offset=" + offset + " data=" + Arrays.toString(value));

            characteristic.setValue(value);

            WritableMap event = Arguments.createMap();
            WritableArray data = Arguments.createArray();
            for (byte b : value) {
              data.pushInt((int)b);
            }
            event.putString("characteristic", characteristic.getUuid().toString());
            event.putString("device", device.toString());
            event.putInt("requestId", requestId);
            event.putArray("data", data);

            sendEvent("characteristicWriteRequested", event);

            if (responseNeeded) {
                mGattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                );
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, 
                                            int requestId, 
                                            int offset, 
                                            BluetoothGattDescriptor descriptor) {
            mGattServer.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                descriptor.getValue()
            );
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, 
                                              int requestId,
                                              BluetoothGattDescriptor descriptor,
                                              boolean preparedWrite,
                                              boolean responseNeeded,
                                              int offset,
                                              byte[] value) {

            if (responseNeeded) {
                mGattServer.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    offset,
                    value
                );
            }
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            Log.d(LOG_TAG, "RNBLEModule - onNotificationSent " + status);
        }
    };

    @ReactMethod
    public void start(final Promise promise) {
        mBluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a dialog requesting user permission to enable Bluetooth.

        mBluetoothDevices = new HashSet<>();
        mGattServer = mBluetoothManager.openGattServer(reactContext, mGattServerCallback);
        mGattServer.clearServices();

        for (BluetoothGattService service : this.servicesMap.values()) {
            mGattServer.addService(service);
        }
        advertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build();


        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder()
            .setIncludeDeviceName(false);
        for (BluetoothGattService service : this.servicesMap.values()) {
            dataBuilder.addServiceUuid(new ParcelUuid(service.getUuid()));
        }
        AdvertiseData data = dataBuilder.build();

        advertisingCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                advertising = true;
                Log.d(LOG_TAG, "RNBLEModule - Started advertising");
                promise.resolve(true);
            }

            @Override
            public void onStartFailure(int errorCode) {
                advertising = false;
                Log.e(LOG_TAG, "RNBLEModule - Advertising onStartFailure: " + errorCode);
                promise.reject(String.valueOf(errorCode));
            }
        };

        advertiser.startAdvertising(settings, data, advertisingCallback);
    }

    @ReactMethod
    public void stop() {
        if (mGattServer != null) {
            mGattServer.close();
        }
        if (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && advertiser != null) {
            // If stopAdvertising() gets called before close() a null
            // pointer exception is raised.
            advertiser.stopAdvertising(advertisingCallback);
        }
    }

    @ReactMethod
    public void sendNotificationToDevices(String serviceUUID, String charUUID, ReadableArray message) {
        BluetoothGattCharacteristic characteristic = servicesMap.get(serviceUUID).getCharacteristic(UUID.fromString(charUUID));
        setCharacteristicValue(characteristic, message);

        boolean indicate = (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_INDICATE)
                == BluetoothGattCharacteristic.PROPERTY_INDICATE;

        for (BluetoothDevice device : mBluetoothDevices) {
            // true for indication (acknowledge) and false for notification (unacknowledge).
            mGattServer.notifyCharacteristicChanged(device, characteristic, indicate);
        }
    }

    @ReactMethod
    public void sendNotificationToDevice(String serviceUUID, String charUUID, String deviceId, ReadableArray message) {
        BluetoothGattCharacteristic characteristic = servicesMap.get(serviceUUID).getCharacteristic(UUID.fromString(charUUID));
        setCharacteristicValue(characteristic, message);

        boolean indicate = (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_INDICATE)
                == BluetoothGattCharacteristic.PROPERTY_INDICATE;

        for (BluetoothDevice device : mBluetoothDevices) {
            if (device.toString().equals(deviceId)) {
                // true for indication (acknowledge) and false for notification (unacknowledge).
                mGattServer.notifyCharacteristicChanged(device, characteristic, indicate);
                break;
            }
        }
    }

    @ReactMethod
    public void isAdvertising(Promise promise){
        promise.resolve(this.advertising);
    }

    @ReactMethod
    public void getState(Promise promise) {
        String state = "off";

        if (mBluetoothAdapter != null) {
            switch (mBluetoothAdapter.getState()) {
                case BluetoothAdapter.STATE_ON:
                    state = "on";
                    break;
                case BluetoothAdapter.STATE_OFF:
                    state = "off";
            }
        }

        promise.resolve(state);
    }

    private void setCharacteristicValue(BluetoothGattCharacteristic characteristic, ReadableArray value) {
        byte[] decoded = new byte[value.size()];
        for (int i = 0; i < value.size(); i++) {
            decoded[i] = new Integer(value.getInt(i)).byteValue();
        }

        characteristic.setValue(decoded);
    }

    private void sendEvent(String eventName, @Nullable WritableMap params) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(eventName, params);
   }
}
