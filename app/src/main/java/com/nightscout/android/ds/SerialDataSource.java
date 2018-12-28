package com.nightscout.android.ds;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import com.physicaloid.lib.Physicaloid;
import com.physicaloid.lib.usb.driver.uart.ReadLisener;
import com.physicaloid.lib.usb.driver.uart.UartConfig;

public class SerialDataSource implements DataSource {

    private static final String TAG = SerialDataSource.class.getSimpleName();

    private Context mContext;
    private Physicaloid mSerial;

    public SerialDataSource(final Context context) {
        mContext = context;
        mSerial = new Physicaloid(context);
    }

    @Override
    public boolean open() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        Boolean enableSerialBridge = prefs.getBoolean("EnableSerialPort", false);
        if (!enableSerialBridge) {
            return false;
        }

        Log.d(TAG, "Opening serial data source");
        if (mSerial.open()) {
            mSerial.setConfig(new UartConfig(57600, 8, 1, 0, true, false));
            return true;
        }

        return false;
    }

    @Override
    public boolean close() {
        Log.d(TAG, "Closing serial data source");
        return mSerial.close();
    }

    @Override
    public boolean isOpened() {
        return mSerial.isOpened();
    }

    @Override
    public int read(byte[] buffer) {
        Log.v(TAG, "Reading from serial data source");
        return mSerial.read(buffer);
    }

    @Override
    public int write(byte[] buffer) {
        Log.v(TAG, "Writing to serial data source");
        return mSerial.write(buffer);
    }

    @Override
    public void setReadListener(final ReadListener readListener) {
        if (readListener == null) {
            clearReadListener();
        } else {
            Log.v(TAG, "Setting read listener on serial data source");
            mSerial.addReadListener(new ReadLisener() {
                @Override
                public void onRead(int size) {
                    readListener.onRead(size);
                }
            });
        }
    }

    @Override
    public void clearReadListener() {
        Log.v(TAG, "Clearing read listener from serial data source");
        mSerial.clearReadListener();
    }

    @Override
    public String getName() {
        return "Serial";
    }
}
