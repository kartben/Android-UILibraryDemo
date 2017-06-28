package com.dji.uilibrarydemo;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.Toast;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.DisconnectedBufferOptions;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import dji.common.error.DJIError;
import dji.common.error.DJISDKError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.LocationCoordinate3D;
import dji.common.util.CommonCallbacks;
import dji.sdk.base.BaseComponent;
import dji.sdk.base.BaseProduct;
import dji.sdk.products.Aircraft;
import dji.sdk.sdkmanager.DJISDKManager;

public class DemoApplication extends Application {
    private static final String TAG = DemoApplication.class.getSimpleName();

    public static final String FLAG_CONNECTION_CHANGE = "uilibrary_demo_connection_change";

    private static BaseProduct mProduct;

    private Handler mHandler;
    private MqttAndroidClient mMqttAndroidClient;
    private Handler mHandlerMqtt;

    private static final String MQTT_SERVER_URI = "tcp://iot.eclipse.org:1883";
    private static final long PUBLISH_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);
    private final String mTopic = "djidrone";

    private FlightControllerState mLatestFlightControllerState;


    /**
     * This function is used to get the instance of DJIBaseProduct.
     * If no product is connected, it returns null.
     */
    public static synchronized BaseProduct getProductInstance() {
        if (null == mProduct) {
            mProduct = DJISDKManager.getInstance().getProduct();
        }
        return mProduct;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        //This is used to start SDK services and initiate SDK.
        DJISDKManager.getInstance().registerApp(this, mDJISDKManagerCallback);

        HandlerThread mHandlerThread = new HandlerThread("mqttPublisherThread");
        mHandlerThread.start();
        mHandlerMqtt = new Handler(mHandlerThread.getLooper());

        mMqttAndroidClient = new MqttAndroidClient(getApplicationContext(), MQTT_SERVER_URI, MqttClient.generateClientId());
        mMqttAndroidClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Log.d(TAG, "MQTT connection complete");
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.d(TAG, "MQTT connection lost");
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                Log.d(TAG, "MQTT message arrived");
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                Log.d(TAG, "MQTT delivery complete");
            }
        });

        final MqttConnectOptions mqttConnectOptions = new MqttConnectOptions();
        mqttConnectOptions.setAutomaticReconnect(true);
        mqttConnectOptions.setCleanSession(false);

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Connect to the broker
                try {
                    mMqttAndroidClient.connect(mqttConnectOptions, null, new IMqttActionListener() {
                        @Override
                        public void onSuccess(IMqttToken asyncActionToken) {
                            DisconnectedBufferOptions disconnectedBufferOptions = new DisconnectedBufferOptions();
                            disconnectedBufferOptions.setBufferEnabled(true);
                            disconnectedBufferOptions.setBufferSize(100);
                            disconnectedBufferOptions.setPersistBuffer(false);
                            disconnectedBufferOptions.setDeleteOldestMessages(false);
                            mMqttAndroidClient.setBufferOpts(disconnectedBufferOptions);
                        }

                        @Override
                        public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                            Log.d(TAG, "MQTT connection failure", exception);
                        }
                    });
                } catch (MqttException e) {
                    Log.d(TAG, "MQTT connection failure", e);
                }

            }
        });

        mHandlerMqtt.postDelayed(mPublishRunnable, 500);

    }

    private FlightControllerState.Callback mAirCraftStateCallback = new FlightControllerState.Callback() {
        @Override
        public void onUpdate(@NonNull FlightControllerState flightControllerState) {
            mLatestFlightControllerState = flightControllerState;
        }
    };

    private Runnable mPublishRunnable = new Runnable() {
        @Override
        public void run() {
            ConnectivityManager connectivityManager =
                    (ConnectivityManager) getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            if (activeNetwork == null || !activeNetwork.isConnectedOrConnecting()) {
                Log.e(TAG, "no active network");
                return;
            }

            try {
                JSONObject messagePayload = createMessagePayload();
                Log.d(TAG, "publishing message: " + messagePayload);
                MqttMessage m = new MqttMessage();
                m.setPayload(messagePayload.toString().getBytes());
                m.setQos(1);
                if (mMqttAndroidClient != null && mMqttAndroidClient.isConnected()) {
                    mMqttAndroidClient.publish(mTopic, m);
                }
            } catch (JSONException | MqttException e) {
                Log.e(TAG, "Error publishing message", e);
            } finally {
                mHandler.postDelayed(mPublishRunnable, PUBLISH_INTERVAL_MS);
            }
        }

        private JSONObject createMessagePayload()
                throws JSONException {
            JSONObject messagePayload = new JSONObject();
            if (getProductInstance() != null) {
                messagePayload.put("model", getProductInstance().getModel().getDisplayName());
                if (getProductInstance() instanceof Aircraft && getProductInstance().isConnected()) {
                    Aircraft aircraft = (Aircraft) getProductInstance();
                    messagePayload.put("heading", mLatestFlightControllerState.getAircraftHeadDirection());
                    messagePayload.put("motors_state", mLatestFlightControllerState.areMotorsOn());
                    messagePayload.put("flying_state", mLatestFlightControllerState.isFlying());

                    messagePayload.put("pitch", mLatestFlightControllerState.getAttitude().pitch);
                    messagePayload.put("roll", mLatestFlightControllerState.getAttitude().roll);
                    messagePayload.put("yaw", mLatestFlightControllerState.getAttitude().yaw);

                    messagePayload.put("gps_signal", mLatestFlightControllerState.getGPSSignalLevel().value());

                    if (mLatestFlightControllerState.getAircraftLocation() != null) {
                        LocationCoordinate3D location = mLatestFlightControllerState.getAircraftLocation();
                        if (!Double.isNaN(location.getLatitude())) {
                            messagePayload.put("latitude", mLatestFlightControllerState.getAircraftLocation().getLatitude());
                        }
                        if (!Double.isNaN(location.getLongitude())) {
                            messagePayload.put("longitude", mLatestFlightControllerState.getAircraftLocation().getLongitude());
                        }
                        if (!Double.isNaN(location.getAltitude())) {
                            messagePayload.put("altitude",  mLatestFlightControllerState.getAircraftLocation().getAltitude());
                        }
                    }

                    messagePayload.put("velocity_x", mLatestFlightControllerState.getVelocityX());
                    messagePayload.put("velocity_y", mLatestFlightControllerState.getVelocityY());
                    messagePayload.put("velocity_z", mLatestFlightControllerState.getVelocityZ());

                }
            }
            return messagePayload;
        }
    };

    /**
     * When starting SDK services, an instance of interface DJISDKManager.DJISDKManagerCallback will be used to listen to
     * the SDK Registration result and the product changing.
     */

    private DJISDKManager.SDKManagerCallback mDJISDKManagerCallback = new DJISDKManager.SDKManagerCallback() {

        //Listens to the SDK registration result
        @Override
        public void onRegister(DJIError error) {
            if (error == DJISDKError.REGISTRATION_SUCCESS) {
                DJISDKManager.getInstance().startConnectionToProduct();
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Register Success", Toast.LENGTH_LONG).show();
                    }
                });
            } else {
                Handler handler = new Handler(Looper.getMainLooper());
                handler.post(new Runnable() {

                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), "Register Failed, check network is available", Toast.LENGTH_LONG).show();
                    }
                });

            }
            Log.e("TAG", error.toString());
        }

        //Listens to the connected product changing, including two parts, component changing or product connection changing.
        @Override
        public void onProductChange(BaseProduct oldProduct, BaseProduct newProduct) {

            mProduct = newProduct;
            if (mProduct != null) {
                mProduct.setBaseProductListener(mDJIBaseProductListener);
                if (mProduct instanceof Aircraft) {
                    ((Aircraft) mProduct).getFlightController().setStateCallback(mAirCraftStateCallback);
                }

                notifyStatusChange();
            }
        }

        private BaseProduct.BaseProductListener mDJIBaseProductListener = new BaseProduct.BaseProductListener() {

            @Override
            public void onComponentChange(BaseProduct.ComponentKey key, BaseComponent oldComponent, BaseComponent newComponent) {

                if (newComponent != null) {
                    newComponent.setComponentListener(mDJIComponentListener);
                }
                notifyStatusChange();
            }

            @Override
            public void onConnectivityChange(boolean isConnected) {

                notifyStatusChange();
            }

        };

        private BaseComponent.ComponentListener mDJIComponentListener = new BaseComponent.ComponentListener() {

            @Override
            public void onConnectivityChange(boolean isConnected) {
                notifyStatusChange();
            }

        };

        private void notifyStatusChange() {
            Log.d(TAG, "NOTIFY STATUS CHANGE");
            mHandler.removeCallbacks(updateRunnable);
            mHandlerMqtt.removeCallbacks(mPublishRunnable);

            mHandler.postDelayed(updateRunnable, 500);
            mHandlerMqtt.postDelayed(mPublishRunnable, 500);
        }

        private Runnable updateRunnable = new Runnable() {

            @Override
            public void run() {
                Intent intent = new Intent(FLAG_CONNECTION_CHANGE);
                sendBroadcast(intent);
            }
        };


    };

}