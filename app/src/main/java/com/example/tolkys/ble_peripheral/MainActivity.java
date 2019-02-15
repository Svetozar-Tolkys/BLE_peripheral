package com.example.tolkys.ble_peripheral;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.ParcelUuid;
import android.support.annotation.Nullable;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.erz.joysticklibrary.JoyStick;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private UUID SERVICE_UUID = UUID.fromString("795090c7-420d-4048-a24e-18e60180e23c");
    private UUID CHARACTERISTIC_COUNTER_UUID = UUID.fromString("31517c58-66bf-470c-b662-e352a6c80cba");
    private UUID DESCRIPTOR_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private SensorManager mSensorManager;

    // Accelerometer and magnetometer sensors, as retrieved from the
    // sensor manager.
    private Sensor mSensorAccelerometer;
    static private float pitch;
    static private float roll;
    static private float counter = 0;

    // ImageView drawables to display spots.
    private ImageView mSpotTop;
    private ImageView mSpotBottom;
    private ImageView mSpotLeft;
    private ImageView mSpotRight;
    private ImageView mRelease;

    static private float pitchReal;
    static private float pitchStart; //= -1.0f;
    static private float pitchOffset = 0.1f;
    static private float pitchMaxForward = 1.7f;
    static private float pitchMaxRevers = 1.7f;

    static private float rollStart = 0.00f;
    static private float rollOffset = 0.2f;
    static private float rollMaxRight = 1.7f;
    static private float rollMaxLeft = 1.7f;

    static private int point = 40;
    static private int peak = 12;

    private TextView textView;
    private TextView textViewR;

    private static int horizontalValue;
    private static int verticalValue;

    private static int outputMin = 0;
    private static int outputMax = 255;

    private Handler handler = new Handler();
    private boolean isAllowed = true;
    static private boolean isStarted = false;
    static private boolean isPressed = false;
    private int latency = 250;

    private BluetoothManager mBluetoothManager;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private BluetoothGattServer mBluetoothGattServer;

    private Set<BluetoothDevice> mRegisteredDevices = new HashSet<>();

    private static final String TAG = "mTAG"; //MainActivity.class.getSimpleName();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSpotTop = (ImageView) findViewById(R.id.spot_top);
        mSpotBottom = (ImageView) findViewById(R.id.spot_bottom);
        mSpotLeft = (ImageView) findViewById(R.id.spot_left);
        mSpotRight = (ImageView) findViewById(R.id.spot_right);
        mRelease = (ImageView) findViewById(R.id.release);

        textView = (TextView) findViewById(R.id.status);
        textViewR = (TextView) findViewById(R.id.stick);

        mRelease.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch(event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        mRelease.setAlpha(1f);
                        if (!isPressed) {
                            isPressed = true;
                            pitchStart = pitchReal;
                        }
                        isStarted = true;
                        notifyRegisteredDevices();
                        break;
                    case MotionEvent.ACTION_UP:
                        mRelease.setAlpha(0.05f);
                        isStarted = false;
                        isPressed = false;
                        notifyRegisteredDevices();
                        break;
                }
                return true;
            }
        });

        mSensorManager = (SensorManager) getSystemService(
                Context.SENSOR_SERVICE);
        mSensorAccelerometer = mSensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER);

        mBluetoothManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = mBluetoothManager.getAdapter();

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID))
                .build();

        // Starts advertising.
        mBluetoothLeAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null) {
            Log.w(TAG, "Failed to create advertiser");
            return;
        }

        mBluetoothLeAdvertiser.startAdvertising(settings, data, mAdvertiseCallback);

        // Starts server.
        mBluetoothGattServer = mBluetoothManager.openGattServer(this, mGattServerCallback);
        if (mBluetoothGattServer == null) {
            Log.w(TAG, "Unable to create GATT server");
            return;
        }
        mBluetoothGattServer.addService(createService());
    }

    private AdvertiseCallback mAdvertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            Log.i(TAG, "LE Advertise Started.");
        }

        @Override
        public void onStartFailure(int errorCode) {
            Log.w(TAG, "LE Advertise Failed: " + errorCode);
        }
    };

    private BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BluetoothDevice CONNECTED: " + device);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText("Connected");
                    }
                });
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BluetoothDevice DISCONNECTED: " + device);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        textView.setText("Disconnected");
                    }
                });
                mRegisteredDevices.remove(device);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattCharacteristic characteristic) {
            if (CHARACTERISTIC_COUNTER_UUID.equals(characteristic.getUuid())) {
                Log.i(TAG, "Read counter");
                byte[] value = MainActivity.onReadDirection();
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value);
            } else {
                // Invalid characteristic
                Log.w(TAG, "Invalid Characteristic Read: " + characteristic.getUuid());
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId, int offset, BluetoothGattDescriptor descriptor) {
            if (DESCRIPTOR_CONFIG_UUID.equals(descriptor.getUuid())) {
                Log.d(TAG, "Config descriptor read request");
                byte[] returnValue;
                if (mRegisteredDevices.contains(device)) {
                    returnValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                } else {
                    returnValue = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                }
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, returnValue);
            } else {
                Log.w(TAG, "Unknown descriptor read request");
                mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (DESCRIPTOR_CONFIG_UUID.equals(descriptor.getUuid())) {
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Subscribe device to notifications: " + device);
                    mRegisteredDevices.add(device);
                } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                    Log.d(TAG, "Unsubscribe device from notifications: " + device);
                    mRegisteredDevices.remove(device);
                }

                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
            } else {
                Log.w(TAG, "Unknown descriptor write request");
                if (responseNeeded) {
                    mBluetoothGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
                }
            }
        }
    };

    private static byte[] onReadDirection() {
        byte[] data = new byte[3];
        data[0] = isStarted ? (byte) 0x01 : (byte) 0x00;
        data[1] = (byte) horizontalValue;
        data[2] = (byte) verticalValue;
        return data;
    }

    private void getAverage() {
        if (counter != 0) {
            pitch = pitch / counter;
            roll = roll / counter;
            pitchReal = pitch;
            textViewR.setText("Pitch: " + getResources().getString(
                    R.string.value_format, pitch) + " Roll: " + getResources().getString(
                    R.string.value_format, roll) + " count: " + counter);
            counter = 0;
            redraw();
        }
        return;
    }

    private void redraw() {
        mSpotTop.setAlpha(0f);
        mSpotBottom.setAlpha(0f);
        mSpotLeft.setAlpha(0f);
        mSpotRight.setAlpha(0f);

        int vertical = scaler(pitch,pitchStart,pitchOffset,pitchMaxForward,pitchMaxRevers,outputMin,outputMax);
        int horizontal = scaler(roll,rollStart,rollOffset,rollMaxRight,rollMaxLeft,outputMin,outputMax);

        int rangeZero = (outputMax - outputMin)/2;

        if (vertical > rangeZero) {
            mSpotTop.setAlpha((float) (vertical - rangeZero)/ (float) rangeZero);
        } else {
            mSpotBottom.setAlpha((float) (rangeZero - vertical) / (float) rangeZero );
        }
        if (horizontal > rangeZero/2) {
            mSpotRight.setAlpha((float) (horizontal - rangeZero)/ (float) rangeZero);
        } else {
            mSpotLeft.setAlpha((float) (rangeZero - horizontal) / (float) rangeZero);
        }

        verticalValue = vertical;
        horizontalValue = horizontal;

        pitch = 0;
        roll = 0;

        return;
    }

    private BluetoothGattService createService() {
        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);

        // Counter characteristic (read-only, supports subscriptions)
        BluetoothGattCharacteristic direction = new BluetoothGattCharacteristic(CHARACTERISTIC_COUNTER_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ);
        BluetoothGattDescriptor counterConfig = new BluetoothGattDescriptor(DESCRIPTOR_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE);
        direction.addDescriptor(counterConfig);

        service.addCharacteristic(direction);
        return service;
    }

    private void notifyRegisteredDevices() {
        if (isAllowed) {
            isAllowed = false;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    isAllowed = true;
                }
            }, latency);

            getAverage();

            if (mRegisteredDevices.isEmpty()) {
                Log.i(TAG, "No subscribers registered");
                return;
            }

            Log.i(TAG, "Sending update to " + mRegisteredDevices.size() + " subscribers");
            for (BluetoothDevice device : mRegisteredDevices) {
                BluetoothGattCharacteristic counterCharacteristic = mBluetoothGattServer
                        .getService(SERVICE_UUID)
                        .getCharacteristic(CHARACTERISTIC_COUNTER_UUID);
                byte[] value = MainActivity.onReadDirection();
                counterCharacteristic.setValue(value);
                mBluetoothGattServer.notifyCharacteristicChanged(device, counterCharacteristic, false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
        stopAdvertising();
    }

    private void stopServer() {
        if (mBluetoothGattServer == null) {
            return;
        }
        mBluetoothGattServer.close();
    }

    private void stopAdvertising() {
        if (mBluetoothLeAdvertiser == null) {
            return;
        }
        mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mSensorAccelerometer != null) {
            mSensorManager.registerListener(this, mSensorAccelerometer,
                    SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Unregister all sensor listeners in this callback so they don't
        // continue to use resources when the app is stopped.
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {

        if (sensorEvent.sensor != mSensorAccelerometer)
            return;

        float aX = sensorEvent.values[0];
        float aY= sensorEvent.values[1];
        float aZ = sensorEvent.values[2];

        pitch += (float) Math.atan2(aZ, aX);
        roll += (float) Math.atan2(aY, aZ);
        counter++;

        notifyRegisteredDevices();
    }

    /**
     * Must be implemented to satisfy the SensorEventListener interface;
     * unused in this app.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
    }

    public int scaler(float value, float zero, float offset, float positive, float negative, int min, int max) {
        value -= zero;
        float range = (float) max - min;
        if (value > positive) value = positive;
        if (value < -negative) value = -negative;
        if (value > offset) return Math.round((((value - offset)/(positive - offset) + 1)/2)* range);
        else if (value < -offset) return Math.round((1 - (value + offset)/(-negative + offset))*range/2);
        else return (max - min)/2;
    }

    public int amplifier(int input, int min, int max, int point, int peak) {
        int zero = (max-min) / 2;
        input -= zero;
        float k = (zero - peak) / (float) (zero - point);
        if (input > 0) {
            if (input < point) {
                input = Math.round((float) (input * peak) / (float) point);
            } else {
                input = Math.round(((float) input) * k  + ((float) zero) * (1 - k));
            }
            return input + zero;
        } else if (input < 0) {
            input = - input;
            if (input < point) {
                input = Math.round((float) (input * peak) / (float) point);
            } else {
                input = Math.round(((float) input) * k  + ((float) zero) * (1 - k));
            }
            return -input + zero;
        } else {
            return zero;
        }
    }
}
