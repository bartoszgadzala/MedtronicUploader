package com.nightscout.android.ds;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public class SerialBridgeDataSource implements DataSource {

    private static final String TAG = SerialBridgeDataSource.class.getSimpleName();

    private Context mContext;
    private boolean mOpened;
    private ReadListener mReadListener;
    private Thread mDataCheckThread;

    private Socket mSocket;
    private InputStream mInput;
    private OutputStream mOutput;

    public SerialBridgeDataSource(final Context context) {
        mContext = context;
    }

    @Override
    public boolean open() {
        closeSocket();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        Boolean enableSerialBridge = prefs.getBoolean("EnableSerialBridge", false);
        if (!enableSerialBridge) {
            return false;
        }

        String serialBridgeHost = prefs.getString("serialBridgeHost", "");
        int serialBridgePort = Integer.valueOf(prefs.getString("serialBridgePort", "0"));
        if (serialBridgeHost == null || serialBridgeHost.trim().equals("") || serialBridgePort <= 0) {
            return false;
        }

        try {
            Log.d(TAG, "Opening Serial Bridge data source");
            mSocket = new Socket(serialBridgeHost, serialBridgePort);
            mInput = new BufferedInputStream(mSocket.getInputStream());
            mOutput = new BufferedOutputStream(mSocket.getOutputStream());

            mOpened = true;

            startDataCheckThread();
        } catch (Exception ex) {
            mOpened = false;
            Log.e(TAG, "Error while opening connection to the serial bridge", ex);
        }

        return mOpened;
    }


    @Override
    public boolean close() {
        Log.d(TAG, "Closing Serial Bridge data source");

        closeSocket();
        mOpened = false;
        return mOpened;
    }

    @Override
    public boolean isOpened() {
        return mOpened && mSocket != null && mSocket.isConnected();
    }

    @Override
    public int read(byte[] buffer) {
        Log.v(TAG, "Reading from Serial Bridge data source");

        try {
            return mInput.read(buffer);
        } catch (Exception ex) {
            Log.e(TAG, "Error while reading", ex);
            return 0;
        }
    }

    @Override
    public int write(byte[] buffer) {
        Log.v(TAG, "Writing to Serial Bridge data source");

        try {
            mOutput.write(buffer);
            return buffer.length;
        } catch (Exception ex) {
            Log.e(TAG, "Error while writing", ex);
            return 0;
        }
    }

    @Override
    public void setReadListener(final ReadListener readListener) {
        if (readListener == null) {
            clearReadListener();
        } else {
            Log.v(TAG, "Setting read listener on Serial Bridge data source");
            mReadListener = readListener;
        }
    }

    @Override
    public void clearReadListener() {
        Log.v(TAG, "Clearing read listener from Serial Bridge data source");

        mReadListener = null;
    }

    @Override
    public String getName() {
        return "Serial Bridge";
    }

    private void startDataCheckThread() {
        mDataCheckThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (mOpened) {
                    try {
                        int available = mInput.available();
                        if (available > 0 && mReadListener != null) {
                            Log.d(TAG, available + " bytes available to read");
                            mReadListener.onRead(available);
                        }

                        Thread.sleep(1000);
                    } catch (IOException ex) {
                        Log.e(TAG, "Error while checking number of available bytes");
                    } catch (InterruptedException ex) {
                        Log.e(TAG, "Interrupted", ex);
                        break;
                    }
                }

                Log.d(TAG, "Closing start data check thread");
            }
        });
        mDataCheckThread.start();
    }

    private synchronized void closeSocket() {
        try {
            if (mInput != null) {
                mInput.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "Error while closing reader");
        }
        mInput = null;

        try {
            if (mOutput != null) {
                mOutput.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "Error while closing writer");
        }
        mOutput = null;

        try {


            if (mSocket != null) {
                mSocket.close();
            }
        } catch (Exception ex) {
            Log.w(TAG, "Error while closing socket");
        }
        mSocket = null;
    }
}
