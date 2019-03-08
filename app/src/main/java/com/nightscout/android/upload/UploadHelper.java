package com.nightscout.android.upload;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;

import com.nightscout.android.dexcom.DexcomG4Activity;
import com.nightscout.android.dexcom.EGVRecord;
import com.nightscout.android.medtronic.MedtronicConstants;
import com.nightscout.android.medtronic.MedtronicReader;

import org.apache.http.HttpVersion;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ch.qos.logback.classic.Logger;

public class UploadHelper extends AsyncTask<Record, Integer, Long> {

    private Logger log = (Logger) LoggerFactory.getLogger(MedtronicReader.class.getName());
    private static final String TAG = "DexcomUploadHelper";
    private SharedPreferences settings = null;// common application preferences
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss aa", Locale.getDefault());
    private static final int SOCKET_TIMEOUT = 60 * 1000;
    private static final int CONNECTION_TIMEOUT = 30 * 1000;

    Context context;
    private int cgmSelected = DexcomG4Activity.MEDTRONIC_CGM;
    private ArrayList<Messenger> mClients;
    private List<JSONObject> recordsNotUploadedList = new ArrayList<JSONObject>();
    private List<JSONObject> recordsNotUploadedListJson = new ArrayList<JSONObject>();
    public String dbURI = null;
    public String collectionName = null;
    public String dsCollectionName = null;
    public String gdCollectionName = null;
    public String devicesCollectionName = "devices";
    public static Boolean isModifyingRecords = false;
    public static Object isModifyingRecordsLock = new Object();

    public UploadHelper(Context context) {
        this(context, DexcomG4Activity.MEDTRONIC_CGM);
    }

    public UploadHelper(Context context, int cgmSelected) {
        this.context = context;
        this.cgmSelected = cgmSelected;
        this.mClients = null;
        settings = context.getSharedPreferences(MedtronicConstants.PREFS_NAME, 0);
        synchronized (isModifyingRecordsLock) {
            try {
                long currentTime = System.currentTimeMillis();
                long diff = currentTime - settings.getLong("lastDestroy", 0);
                if (diff != currentTime && diff > (6 * MedtronicConstants.TIME_60_MIN_IN_MS)) {
                    log.debug("Remove older records");
                    SharedPreferences.Editor editor = settings.edit();
                    if (settings.contains("recordsNotUploaded"))
                        editor.remove("recordsNotUploaded");
                    if (settings.contains("recordsNotUploadedJson"))
                        editor.remove("recordsNotUploadedJson");
                    editor.commit();
                }
                if (settings.contains("recordsNotUploaded")) {
                    JSONArray recordsNotUploaded = new JSONArray(settings.getString("recordsNotUploaded", "[]"));
                    for (int i = 0; i < recordsNotUploaded.length(); i++) {
                        recordsNotUploadedList.add(recordsNotUploaded.getJSONObject(i));
                    }
                    log.debug("retrieve older json records -->" + recordsNotUploaded.length());
                    SharedPreferences.Editor editor = settings.edit();
                    editor.remove("recordsNotUploaded");
                    editor.commit();
                }
                if (settings.contains("recordsNotUploadedJson")) {
                    JSONArray recordsNotUploadedJson = new JSONArray(settings.getString("recordsNotUploadedJson", "[]"));
                    for (int i = 0; i < recordsNotUploadedJson.length(); i++) {
                        recordsNotUploadedListJson.add(recordsNotUploadedJson.getJSONObject(i));
                    }
                    log.debug("retrieve older json records -->" + recordsNotUploadedJson.length());
                    SharedPreferences.Editor editor = settings.edit();
                    editor.remove("recordsNotUploadedJson");
                    editor.commit();
                }
            } catch (Exception e) {
                log.debug("ERROR Retrieving older list, I have lost them");
                recordsNotUploadedList = new ArrayList<JSONObject>();
                recordsNotUploadedListJson = new ArrayList<JSONObject>();
                SharedPreferences.Editor editor = settings.edit();
                if (settings.contains("recordsNotUploaded"))
                    editor.remove("recordsNotUploaded");
                if (settings.contains("recordsNotUploadedJson"))
                    editor.remove("recordsNotUploadedJson");
                editor.commit();
            }
        }
    }

    public UploadHelper(Context context, int cgmSelected, ArrayList<Messenger> mClients) {
        this(context, cgmSelected);
        this.mClients = mClients;
    }

    /**
     * Sends an error message to be printed in the display (DEBUG) if it is repeated, It is not printed again. If UI is not visible, It will launch a pop-up message.
     *
     * @param valuetosend
     */
    private void sendErrorMessageToUI(String valuetosend) {
        Log.e("UploadHelper", valuetosend);
        log.error("Send Error Message to UI " + valuetosend);
        if (mClients != null && mClients.size() > 0) {
            for (int i = mClients.size() - 1; i >= 0; i--) {
                try {
                    Message mSend = null;
                    mSend = Message
                            .obtain(null,
                                    MedtronicConstants.MSG_MEDTRONIC_CGM_ERROR_RECEIVED);
                    Bundle b = new Bundle();
                    b.putString("data", valuetosend);
                    mSend.setData(b);
                    mClients.get(i).send(mSend);

                } catch (RemoteException e) {
                    // The client is dead. Remove it from the list; we are going
                    // through the list from back to front so this is safe to do
                    // inside the loop.
                    mClients.remove(i);
                }
            }
        } else {
            log.info("UploadHelper", valuetosend);
        }
    }


    /**
     * @return constant String to identify the selected Device
     */
    private String getSelectedDeviceName() {
        switch (cgmSelected) {
            case DexcomG4Activity.MEDTRONIC_CGM:
                return "Medtronic_CGM";
            default:
                return "dexcom";
        }
    }

    /**
     * doInBackground
     */
    protected Long doInBackground(Record... records) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this.context);
        Boolean enableRESTUpload = prefs.getBoolean("EnableRESTUpload", false);
        try {
            if (enableRESTUpload) {
                long start = System.currentTimeMillis();
                Log.i(TAG, String.format("Starting upload of %s record using a REST API", records.length));
                log.info(String.format("Starting upload of %s record using a REST API", records.length));
                doRESTUpload(prefs, records);
                Log.i(TAG, String.format("Finished upload of %s record using a REST API in %s ms", records.length, System.currentTimeMillis() - start));
                log.info(String.format("Finished upload of %s record using a REST API in %s ms", records.length, System.currentTimeMillis() - start));
            }
        } catch (Exception e) {
            sendErrorMessageToUI("ERROR uploading data!!!!! " + e.toString());
        }


        return 1L;
    }

    protected void onPostExecute(Long result) {
        super.onPostExecute(result);
        Log.i(TAG, "Post execute, Result: " + result + ", Status: FINISHED");
        log.info("Post execute, Result: " + result + ", Status: FINISHED");

    }

    private void doRESTUpload(SharedPreferences prefs, Record... records) {
        String baseURLSettings = prefs.getString("API Base URL", "");
        ArrayList<String> baseURIs = new ArrayList<String>();

        try {
            for (String baseURLSetting : baseURLSettings.split(" ")) {
                String baseURL = baseURLSetting.trim();
                if (baseURL.isEmpty()) continue;
                baseURIs.add(baseURL + (baseURL.endsWith("/") ? "" : "/"));
            }
        } catch (Exception e) {
            sendErrorMessageToUI("Unable to process API Base URL setting: " + baseURLSettings + ", " + e.toString());
            Log.e(TAG, "Unable to process API Base URL setting: " + baseURLSettings, e);
            log.error("Unable to process API Base URL setting: " + baseURLSettings, e);
            return;
        }

        for (String baseURI : baseURIs) {
            try {
                doRESTUploadTo(baseURI, records);
            } catch (Exception e) {
                sendErrorMessageToUI("Unable to do REST API Upload to: " + baseURI + ", " + e.toString());
                Log.e(TAG, "Unable to do REST API Upload to: " + baseURI, e);
                log.error("Unable to do REST API Upload to: " + baseURI, e);
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void doRESTUploadTo(String baseURI, Record[] records) {
        Integer typeSaved = null;
        try {
            int apiVersion = 0;
            if (baseURI.endsWith("/v1/")) apiVersion = 1;

            String baseURL = null;
            String secret = null;
            String[] uriParts = baseURI.split("@");

            if (uriParts.length == 1 && apiVersion == 0) {
                baseURL = uriParts[0];
            } else if (uriParts.length == 1 && apiVersion > 0) {
                if (recordsNotUploadedListJson.size() > 0) {
                    JSONArray jsonArray = new JSONArray(recordsNotUploadedListJson);
                    SharedPreferences.Editor editor = settings.edit();
                    editor.putString("recordsNotUploaded", jsonArray.toString());
                    editor.commit();
                }
                throw new Exception("Starting with API v1, a pass phase is required");
            } else if (uriParts.length == 2 && apiVersion > 0) {
                secret = uriParts[0];
                baseURL = uriParts[1];

                // new format URL!

                if (secret.contains("http")) {
                    String b = "http://";
                    if (secret.contains("https")) {
                        baseURL = "https://" + baseURL;
                    } else {
                        baseURL = "http://" + baseURL;
                    }
                    String[] uriParts2 = secret.split("//");
                    secret = uriParts2[1];
                }


            } else {
                if (recordsNotUploadedListJson.size() > 0) {
                    JSONArray jsonArray = new JSONArray(recordsNotUploadedListJson);
                    SharedPreferences.Editor editor = settings.edit();
                    editor.putString("recordsNotUploadedJson", jsonArray.toString());
                    editor.commit();
                }
                throw new Exception(String.format("Unexpected baseURI: %s, uriParts.length: %s, apiVersion: %s", baseURI, uriParts.length, apiVersion));
            }

            DefaultHttpClient httpclient = getHttpclient();

            postDeviceStatus(baseURL, httpclient);

            if (recordsNotUploadedListJson.size() > 0) {
                List<JSONObject> auxList = new ArrayList<JSONObject>(recordsNotUploadedListJson);
                recordsNotUploadedListJson.clear();
                for (int i = 0; i < auxList.size(); i++) {
                    JSONObject json = auxList.get(i);
                    String postURL = baseURL;
                    postURL += "entries";
                    Log.i(TAG, "postURL: " + postURL);

                    HttpPost post = new HttpPost(postURL);

                    if (apiVersion > 0) {
                        if (secret == null || secret.isEmpty()) {
                            if (auxList.size() > 0) {
                                JSONArray jsonArray = new JSONArray(auxList);
                                SharedPreferences.Editor editor = settings.edit();
                                editor.putString("recordsNotUploaded", jsonArray.toString());
                                editor.commit();
                            }
                            throw new Exception("Starting with API v1, a pass phase is required");
                        } else {
                            MessageDigest digest = MessageDigest.getInstance("SHA-1");
                            byte[] bytes = secret.getBytes("UTF-8");
                            digest.update(bytes, 0, bytes.length);
                            bytes = digest.digest();
                            StringBuilder sb = new StringBuilder(bytes.length * 2);
                            for (byte b : bytes) {
                                sb.append(String.format("%02x", b & 0xff));
                            }
                            String token = sb.toString();
                            post.setHeader("api-secret", token);
                        }
                    }

                    String jsonString = json.toString();

                    Log.i(TAG, "DEXCOM JSON: " + jsonString);
                    log.debug("JSON to Upload " + jsonString);

                    try {
                        StringEntity se = new StringEntity(jsonString);
                        post.setEntity(se);
                        post.setHeader("Accept", "application/json");
                        post.setHeader("Content-type", "application/json");

                        ResponseHandler responseHandler = new BasicResponseHandler();
                        httpclient.execute(post, responseHandler);
                    } catch (Exception e) {
                        sendErrorMessageToUI("Unable to post data to: '" + post.getURI().toString() + "': " + e.toString());
                        Log.w(TAG, "Unable to post data to: '" + post.getURI().toString() + "'", e);
                        log.warn("Unable to post data to: '" + post.getURI().toString() + "'", e);
                    }
                }
            }

            for (Record record : records) {
                String postURL = baseURL;
                if (record instanceof GlucometerRecord) {
                    typeSaved = 0;
                    postURL += "entries";
                } else if (record instanceof MedtronicPumpRecord) {
                    typeSaved = 3;
                    postURL += "deviceentries";
                } else {
                    typeSaved = 0;
                    postURL += "entries";
                }
                Log.i(TAG, "postURL: " + postURL);
                log.info("postURL: " + postURL);

                HttpPost post = new HttpPost(postURL);

                if (apiVersion > 0) {
                    if (secret == null || secret.isEmpty()) {
                        if (recordsNotUploadedListJson.size() > 0) {
                            JSONArray jsonArray = new JSONArray(recordsNotUploadedListJson);
                            SharedPreferences.Editor editor = settings.edit();
                            editor.putString("recordsNotUploadedJson", jsonArray.toString());
                            editor.commit();
                        }
                        throw new Exception("Starting with API v1, a pass phase is required");
                    } else {
                        MessageDigest digest = MessageDigest.getInstance("SHA-1");
                        byte[] bytes = secret.getBytes("UTF-8");
                        digest.update(bytes, 0, bytes.length);
                        bytes = digest.digest();
                        StringBuilder sb = new StringBuilder(bytes.length * 2);
                        for (byte b : bytes) {
                            sb.append(String.format("%02x", b & 0xff));
                        }
                        String token = sb.toString();
                        post.setHeader("api-secret", token);
                    }
                }

                JSONObject json = new JSONObject();

                try {
                    if (apiVersion >= 1)
                        populateV1APIEntry(json, record);
                    else
                        populateLegacyAPIEntry(json, record);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to populate entry, apiVersion: " + apiVersion, e);
                    log.warn("Unable to populate entry, apiVersion: " + apiVersion, e);
                    continue;
                }

                String jsonString = json.toString();

                Log.i(TAG, "DEXCOM JSON: " + jsonString);
                log.info("DEXCOM JSON: " + jsonString);

                try {
                    StringEntity se = new StringEntity(jsonString);
                    post.setEntity(se);
                    post.setHeader("Accept", "application/json");
                    post.setHeader("Content-type", "application/json");

                    ResponseHandler responseHandler = new BasicResponseHandler();
                    httpclient.execute(post, responseHandler);
                } catch (Exception e) {
                    if ((typeSaved != null) && (typeSaved == 0)) {//Only EGV records are important enough.
                        if (recordsNotUploadedListJson.size() > 49) {
                            recordsNotUploadedListJson.remove(0);
                            recordsNotUploadedListJson.add(49, json);
                        } else {
                            recordsNotUploadedListJson.add(json);
                        }
                    }

                    sendErrorMessageToUI("Unable to post data to: '" + post.getURI().toString() + "': " + e.toString());
                    Log.w(TAG, "Unable to post data to: '" + post.getURI().toString() + "'", e);
                    log.warn("Unable to post data to: '" + post.getURI().toString() + "'", e);
                }
            }
            postDeviceStatus(baseURL, httpclient);
        } catch (Exception e) {
            sendErrorMessageToUI("Unable to post data: " + e.toString());
            Log.e(TAG, "Unable to post data", e);
            log.error("Unable to post data", e);
        }
        if (recordsNotUploadedListJson.size() > 0) {
            synchronized (isModifyingRecordsLock) {
                try {
                    JSONArray recordsNotUploadedJson = new JSONArray(settings.getString("recordsNotUploadedJson", "[]"));
                    if (recordsNotUploadedJson.length() > 0 && recordsNotUploadedJson.length() < recordsNotUploadedListJson.size()) {
                        for (int i = 0; i < recordsNotUploadedJson.length(); i++) {
                            if (recordsNotUploadedListJson.size() > 49) {
                                recordsNotUploadedListJson.remove(0);
                                recordsNotUploadedListJson.add(49, recordsNotUploadedJson.getJSONObject(i));
                            } else {
                                recordsNotUploadedListJson.add(recordsNotUploadedJson.getJSONObject(i));
                            }
                        }
                    } else {
                        for (int i = 0; i < recordsNotUploadedListJson.size(); i++) {
                            recordsNotUploadedJson.put(recordsNotUploadedListJson.get(i));
                        }
                        int start = 0;
                        if (recordsNotUploadedJson.length() > 50) {
                            start = recordsNotUploadedJson.length() - 51;
                        }
                        recordsNotUploadedListJson.clear();
                        for (int i = start; i < recordsNotUploadedJson.length(); i++) {
                            recordsNotUploadedListJson.add(recordsNotUploadedJson.getJSONObject(i));
                        }
                    }
                    log.debug("retrieve older json records -->" + recordsNotUploadedJson.length());
                    SharedPreferences.Editor editor = settings.edit();
                    editor.remove("recordsNotUploadedJson");
                    editor.commit();
                } catch (Exception e) {
                    log.debug("ERROR RETRIEVING OLDER LISTs, I HAVE LOST THEM");
                    SharedPreferences.Editor editor = settings.edit();
                    if (settings.contains("recordsNotUploadedJson"))
                        editor.remove("recordsNotUploadedJson");
                    editor.commit();
                }
                JSONArray jsonArray = new JSONArray(recordsNotUploadedListJson);
                SharedPreferences.Editor editor = settings.edit();
                editor.putString("recordsNotUploadedJson", jsonArray.toString());
                editor.commit();
            }
        }
    }

    private DefaultHttpClient getHttpclient() throws Exception {
        // Setup a custom SSL Factory object which simply ignore the certificates validation and accept all type of self signed certificates
        SSLSocketFactory sslFactory = new SimpleSSLSocketFactory(null);
        sslFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        // Enable HTTP parameters
        HttpParams params = new BasicHttpParams();
        HttpConnectionParams.setSoTimeout(params, SOCKET_TIMEOUT);
        HttpConnectionParams.setConnectionTimeout(params, CONNECTION_TIMEOUT);
        HttpProtocolParams.setVersion(params, HttpVersion.HTTP_1_1);
        HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);

        // Register the HTTP and HTTPS Protocols. For HTTPS, register our custom SSL Factory object.
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        registry.register(new Scheme("https", sslFactory, 443));

        // Create a new connection manager using the newly created registry and then create a new HTTP client using this connection manager
        ClientConnectionManager ccm = new ThreadSafeClientConnManager(params, registry);
        return new DefaultHttpClient(ccm, params);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void postDeviceStatus(String baseURL, DefaultHttpClient httpclient) throws Exception {
        String devicestatusURL = baseURL + "devicestatus";
        Log.i(TAG, "devicestatusURL: " + devicestatusURL);
        log.info("devicestatusURL: " + devicestatusURL);

        JSONObject json = new JSONObject();
        json.put("uploaderBattery", DexcomG4Activity.batLevel);
        String jsonString = json.toString();

        HttpPost post = new HttpPost(devicestatusURL);
        StringEntity se = new StringEntity(jsonString);
        post.setEntity(se);
        post.setHeader("Accept", "application/json");
        post.setHeader("Content-type", "application/json");

        ResponseHandler responseHandler = new BasicResponseHandler();
        httpclient.execute(post, responseHandler);
    }

    private void populateV1APIEntry(JSONObject json, Record oRecord) throws Exception {
        Date date = DATE_FORMAT.parse(oRecord.displayTime);
        json.put("date", date.getTime());

        if (oRecord instanceof GlucometerRecord) {
            json.put("gdValue", ((GlucometerRecord) oRecord).numGlucometerValue);
            json.put("device", getSelectedDeviceName());
            json.put("type", "mbg");
            json.put("mbg", ((GlucometerRecord) oRecord).numGlucometerValue);
        } else if (oRecord instanceof EGVRecord) {
            EGVRecord record = (EGVRecord) oRecord;
            json.put("device", getSelectedDeviceName());
            json.put("sgv", Integer.parseInt(record.bGValue));
            json.put("direction", record.trend);
            if (cgmSelected == DexcomG4Activity.MEDTRONIC_CGM && (oRecord instanceof MedtronicSensorRecord)) {
                json.put("isig", ((MedtronicSensorRecord) record).isig);
                json.put("calibrationFactor", ((MedtronicSensorRecord) record).calibrationFactor);
                json.put("calibrationStatus", ((MedtronicSensorRecord) record).calibrationStatus);
                json.put("unfilteredGlucose", ((MedtronicSensorRecord) record).unfilteredGlucose);
                json.put("isCalibrating", ((MedtronicSensorRecord) record).isCalibrating);
            }
        } else if (oRecord instanceof MedtronicPumpRecord) {
            MedtronicPumpRecord pumpRecord = (MedtronicPumpRecord) oRecord;
            json.put("name", pumpRecord.getDeviceName());
            json.put("deviceId", pumpRecord.deviceId);
            json.put("insulinLeft", pumpRecord.insulinLeft);
            json.put("alarm", pumpRecord.alarm);
            json.put("status", pumpRecord.status);
            json.put("temporaryBasal", pumpRecord.temporaryBasal);
            json.put("batteryStatus", pumpRecord.batteryStatus);
            json.put("batteryVoltage", pumpRecord.batteryVoltage);
            json.put("isWarmingUp", pumpRecord.isWarmingUp);
        }

    }

    private void populateLegacyAPIEntry(JSONObject json, Record oRecord) throws Exception {
        Date date = DATE_FORMAT.parse(oRecord.displayTime);
        json.put("timestamp", date.getTime());

        if (oRecord instanceof GlucometerRecord) {
            json.put("gdValue", ((GlucometerRecord) oRecord).numGlucometerValue);
        } else if (oRecord instanceof EGVRecord) {
            EGVRecord record = (EGVRecord) oRecord;
            json.put("device", getSelectedDeviceName());
            json.put("sgv", Integer.parseInt(record.bGValue));
            json.put("direction", record.trend);
            if (cgmSelected == DexcomG4Activity.MEDTRONIC_CGM && (oRecord instanceof MedtronicSensorRecord)) {
                json.put("isig", ((MedtronicSensorRecord) record).isig);
                json.put("calibrationFactor", ((MedtronicSensorRecord) record).calibrationFactor);
                json.put("calibrationStatus", ((MedtronicSensorRecord) record).calibrationStatus);
                json.put("unfilteredGlucose", ((MedtronicSensorRecord) record).unfilteredGlucose);
                json.put("isCalibrating", ((MedtronicSensorRecord) record).isCalibrating);
            }
        } else if (oRecord instanceof MedtronicPumpRecord) {
            MedtronicPumpRecord pumpRecord = (MedtronicPumpRecord) oRecord;
            json.put("name", pumpRecord.getDeviceName());
            json.put("deviceId", pumpRecord.deviceId);
            json.put("insulinLeft", pumpRecord.insulinLeft);
            json.put("alarm", pumpRecord.alarm);
            json.put("status", pumpRecord.status);
            json.put("temporaryBasal", pumpRecord.temporaryBasal);
            json.put("batteryStatus", pumpRecord.batteryStatus);
            json.put("batteryVoltage", pumpRecord.batteryVoltage);
            json.put("isWarmingUp", pumpRecord.isWarmingUp);
        }
    }

    private static String convertStreamToString(InputStream is) {

        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();

        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line + "\n");
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }

}
