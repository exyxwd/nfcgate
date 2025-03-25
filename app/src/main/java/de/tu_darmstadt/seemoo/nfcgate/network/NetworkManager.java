package de.tu_darmstadt.seemoo.nfcgate.network;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import de.tu_darmstadt.seemoo.nfcgate.gui.MainActivity;
import de.tu_darmstadt.seemoo.nfcgate.network.c2s.C2S;
import de.tu_darmstadt.seemoo.nfcgate.network.data.NetworkStatus;
import de.tu_darmstadt.seemoo.nfcgate.util.NfcComm;

import static de.tu_darmstadt.seemoo.nfcgate.network.c2s.C2S.ServerData.Opcode;


import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;
import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.util.concurrent.CompletableFuture;

public class NetworkManager implements ServerConnection.Callback {
    private static final String TAG = "NetworkManager";

    public interface Callback {
        void onReceive(NfcComm data);
        void onNetworkStatus(NetworkStatus status);
    }

    // references
    private final MainActivity mActivity;
    private ServerConnection mConnection;
    private final Callback mCallback;

    // preference data
    private String mHostname;
    private int mPort, mSessionNumber;
    private long timeOffset = 0;

    public NetworkManager(MainActivity activity, Callback cb) {
        mActivity = activity;
        mCallback = cb;
    }

    public void connect() {
        loadPreferenceData();

        if (mConnection != null)
            disconnect();

        boolean tlsEnabled = PreferenceManager.getDefaultSharedPreferences(mActivity)
                .getBoolean("tls", false);
        mConnection = new ServerConnection(mHostname, mPort, tlsEnabled)
                .setCallback(this)
                .connect();

        sendServer(Opcode.OP_SYN, null);

        // Synchronize time
        syncTimeWithServer();
    }


    public void disconnect() {
        if (mConnection != null) {
            sendServer(Opcode.OP_FIN, null);
            mConnection.sync();
            mConnection.disconnect();
        }
    }

    public void send(NfcComm data) {
        // queue data message
        sendServer(Opcode.OP_PSH, data.toByteArray());
    }

    @Override
    public void onReceive(byte[] data) {
        final C2S.ServerData serverData;
        try {
            serverData = C2S.ServerData.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Message parsing failed", e);
            return;
        }

        Log.v(TAG, "Got message "+serverData.getOpcode().toString());
        switch (serverData.getOpcode()) {
            case OP_SYN:
                // empty syn message indicates our peer has just connected
                onNetworkStatus(NetworkStatus.PARTNER_CONNECT);
                // return ack
                sendServer(Opcode.OP_ACK, null);

                break;
            case OP_ACK:
                // empty ack message indicates our peer was already connected
                onNetworkStatus(NetworkStatus.PARTNER_CONNECT);

                break;
            case OP_FIN:
                // our peer has disconnected
                onNetworkStatus(NetworkStatus.PARTNER_LEFT);
                mConnection.disconnect();

                break;
            case OP_PSH:
                // pass data to callback
                mCallback.onReceive(new NfcComm(serverData.getData().toByteArray()));

                break;
        }
    }

    @Override
    public void onNetworkStatus(NetworkStatus status) {
        mCallback.onNetworkStatus(status);
    }

    private void loadPreferenceData() {
        // read data from shared prefs
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mHostname = prefs.getString("host", null);
        mPort = Integer.parseInt(prefs.getString("port", "0"));
        mSessionNumber = Integer.parseInt(prefs.getString("session", "0"));
    }

    private void sendServer(Opcode opcode, byte[] data) {
        mConnection.send(mSessionNumber,
                C2S.ServerData.newBuilder()
                    .setOpcode(opcode)
                    .setData(data == null ? ByteString.EMPTY : ByteString.copyFrom(data))
                    .build()
                    .toByteArray());
    }

    public void syncTimeWithServer() {
        final String TIME_SERVER = "3.hu.pool.ntp.org";
        final int REQUEST_COUNT = 3;
        final long[] timeDifferences = new long[REQUEST_COUNT];

        // Create a HandlerThread for managing background threads
        HandlerThread handlerThread = new HandlerThread("NTPRequestThread");
        handlerThread.start();

        // Use the Handler from the HandlerThread to execute tasks on the background thread
        Handler handler = new Handler(handlerThread.getLooper());

        for (int i = 0; i < REQUEST_COUNT; i++) {
            final int index = i;

            // Asynchronously send each request
            handler.post(() -> {
                try {
                    NTPUDPClient client = new NTPUDPClient();
                    client.setDefaultTimeout(3000);
                    client.open();

                    InetAddress hostAddr = InetAddress.getByName(TIME_SERVER);
                    TimeInfo timeInfo = client.getTime(hostAddr);
                    timeInfo.computeDetails();
                    long ntpTime = timeInfo.getMessage().getTransmitTimeStamp().getTime(); // Accurate NTP time
                    long systemTime = System.currentTimeMillis();

                    // Corrected system time difference
                    long timeDifference = systemTime - ntpTime;
                    timeDifferences[index] = timeDifference;

                    Log.d(TAG, "Request " + (index + 1) + " - Time difference (System - NTP): " + timeDifference + " ms");

                    client.close();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to synchronize time", e);
                }
            });
        }

        // Once all requests are completed, calculate the average time difference
        handler.postDelayed(() -> {
            long totalDifference = 0;
            for (long diff : timeDifferences) {
                totalDifference += diff;
            }
            long averageTimeDifference = totalDifference / REQUEST_COUNT;
            timeOffset = averageTimeDifference;

            Log.d(TAG, "Average time difference (System - NTP) over " + REQUEST_COUNT + " requests: " + averageTimeDifference + " ms, System time: " + System.currentTimeMillis());

            // Stop the HandlerThread after all requests are completed
            handlerThread.quitSafely();
        }, 5000);
    }
    public long getTimeOffset() {
        return timeOffset;
    }
}
