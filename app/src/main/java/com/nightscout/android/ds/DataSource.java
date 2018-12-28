package com.nightscout.android.ds;

public interface DataSource {

    boolean open();

    boolean close();

    int read(byte[] buffer);

    int write(byte[] buffer);

    boolean isOpened();

    void setReadListener(ReadListener readListener);

    void clearReadListener();

    String getName();


}
