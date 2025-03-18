package de.tu_darmstadt.seemoo.nfcgate.nfc.modes;

import android.util.Log;

import de.tu_darmstadt.seemoo.nfcgate.network.data.NetworkStatus;
import de.tu_darmstadt.seemoo.nfcgate.util.NfcComm;

import java.io.*;
import java.util.Date;
import java.io.IOException;
import java.text.SimpleDateFormat;

public class RelayMode extends BaseMode {
    private final boolean mReader;
    protected boolean mOnline = true;
    private static final String LOG_FILE_NAME = "relay_mode_log.txt";
    private static final String LOG_DIRECTORY = "/storage/emulated/0/Documents/";

    public RelayMode(boolean reader) {
        mReader = reader;
    }

    @Override
    public void onEnable() {
        // reset polling on start
        mManager.resetConfig();
        // enable or disable reader mode
        mManager.setReaderMode(mReader);

        // connect to the network
        if (mOnline)
            mManager.getNetwork().connect();
    }

    @Override
    public void onDisable() {
        // reset polling and config after mode ends
        mManager.resetConfig();
        // disable reader mode
        mManager.setReaderMode(false);

        // disconnect from the network
        if (mOnline)
            mManager.getNetwork().disconnect();
    }

    @Override
    public void onNetworkStatus(NetworkStatus status) {
        // no-op: override in UI
    }

    @Override
    public void onData(boolean isForeign, NfcComm data) {
        String mode = mReader ? "reader" : "tag";
        // accept only foreign data of other type than we are
        if (isForeign && data.isCard() != mReader) {
            // apply foreign data
            mManager.applyData(data);
            logMessage("RECEIVED", data.toString(), mode);
        } else if (!isForeign && data.isCard() == mReader) {
            // send own data over network
            toNetwork(data);
            logMessage("SENT", data.toString(), mode);
        }
    }

    protected void toNetwork(NfcComm data) {
        // default action is to send to network
        mManager.getNetwork().send(data);
    }

    private void logMessage(String direction, String message, String mode) {
        long timestamp = System.currentTimeMillis();
        String formattedTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date(timestamp));
        String logEntry = String.format("%s | %s | %s | %s%n\n", formattedTime, direction, mode, message);

        File logfile = new File(LOG_DIRECTORY, LOG_FILE_NAME);

        try (FileWriter writer = new FileWriter(logfile, true)) {
            writer.write(logEntry);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
