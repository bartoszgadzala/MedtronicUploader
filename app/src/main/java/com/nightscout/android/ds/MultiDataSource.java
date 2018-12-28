package com.nightscout.android.ds;

import android.content.Context;
import android.util.Log;

public class MultiDataSource implements DataSource {

    private static final String TAG = MultiDataSource.class.getSimpleName();

    private final DataSource[] mDataSources;

    public MultiDataSource(final Context context) {
        mDataSources = new DataSource[]{
                new SerialDataSource(context),
                new SerialBridgeDataSource(context),
        };
    }

    @Override
    public boolean open() {
        Log.d(TAG, "Opening");

        boolean anyOpened = false;

        for (final DataSource ds : mDataSources) {
            anyOpened |= ds.open();
        }

        return anyOpened;
    }

    @Override
    public boolean close() {
        Log.d(TAG, "Closing");

        boolean allClosed = true;

        for (final DataSource ds : mDataSources) {
            allClosed &= !ds.isOpened() || ds.close();
        }

        return allClosed;
    }

    @Override
    public boolean isOpened() {
        for (final DataSource ds : mDataSources) {
            if (ds.isOpened()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public int read(byte[] buffer) {
        for (final DataSource ds : mDataSources) {
            if (ds.isOpened()) {
                return ds.read(buffer);
            }
        }

        return 0;
    }

    @Override
    public int write(byte[] buffer) {
        for (final DataSource ds : mDataSources) {
            if (ds.isOpened()) {
                return ds.write(buffer);
            }
        }

        return 0;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        for (final DataSource ds : mDataSources) {
            ds.setReadListener(readListener);
        }
    }

    @Override
    public void clearReadListener() {
        for (final DataSource ds : mDataSources) {
            ds.clearReadListener();
        }
    }

    @Override
    public String getName() {
        for (final DataSource ds : mDataSources) {
            if (ds.isOpened()) {
                return ds.getName();
            }
        }

        return "None";
    }
}
