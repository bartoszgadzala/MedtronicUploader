package com.nightscout.android.dexcom;

import com.nightscout.android.upload.Record;

import java.io.Serializable;

public class EGVRecord extends Record implements Serializable {
    public String bGValue = "---";
    public String trend = "---";
    public String trendArrow = "---";


    private static final long serialVersionUID = 4654897646L;


    public void setBGValue(String input) {
        this.bGValue = input;
    }

    public void setTrend(String input) {
        this.trend = input;
    }

    public void setTrendArrow(String input) {
        this.trendArrow = input;
    }


}

