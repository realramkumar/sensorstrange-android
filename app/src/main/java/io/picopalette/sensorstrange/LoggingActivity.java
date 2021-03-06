package io.picopalette.sensorstrange;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.AppCompatButton;
import android.util.Log;
import android.util.Patterns;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import net.gotev.uploadservice.MultipartUploadRequest;
import net.gotev.uploadservice.UploadNotificationConfig;
import net.lingala.zip4j.core.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.Calendar;
import java.util.Random;
import java.util.regex.Pattern;

import io.picopalette.sensorstrange.helpers.Logger;
import io.picopalette.sensorstrange.views.KeyLogTextView;

public class LoggingActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "MainActivity";
    private KeyLogTextView mTextMessage;
    private Button mControlButton;
    //private EditText mSesEditText;
    private boolean isSensorLogging = false;
    private SensorManager mSensorManager;
    private Sensor mAccSensor;
    private Sensor mGyroSensor;
    private Logger sLogger;
    private Logger kLogger;
    private static final float NS2MS = 1.0f / 1000000.0f;
    private int dotCount=0;

    private String[] trainSentences ={
            "There was once a boy whose father told him that he is old enough to look after the sheep.",
            "One day a wolf was chased away from a farm for trying to steal food.",
            "A boy was once very hungry and went in search of some food.",
            "A kid was upset because he had done poorly in his english test.",
            "Two best friends were walking a dangerous path through a jungle."
    };
    private String[] testSentences ={
            "How are you doing. ",
            "People were very excited to see him. ",
            "He instills confidence in every soul. ",
            "The landscape was so beautiful. "
    };
    public static final String FILE_NAME="StatePref";
    SharedPreferences sharedPreferences;
    SharedPreferences.Editor editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_logging);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(LoggingActivity.this, new String[]{Manifest.permission.READ_CONTACTS}, 1);
        }

        sharedPreferences = getApplicationContext().getSharedPreferences(FILE_NAME, MODE_PRIVATE);
        editor = sharedPreferences.edit();

        mControlButton = findViewById(R.id.controlButton);
        mTextMessage = findViewById(R.id.message);
        try {
            mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            mAccSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            mGyroSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        } catch (NullPointerException e) {
            Log.d(TAG, e.getMessage());
        }

        Boolean uploading = sharedPreferences.getBoolean("uploading", false);
        if(uploading) {
            mControlButton.setText("Uploading");
        } else {
            mControlButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if(!isSensorLogging) {
                        startSensorLogging();
                    } else {
                        stopSensorLogging();
                    }
                }
            });
        }

    }

    private void startSensorLogging() {
        Log.i(TAG, "Starting sensor log");
        isSensorLogging = true;
        mControlButton.setText(getResources().getString(R.string.stop));
        mTextMessage.setVisibility(View.VISIBLE);
        mTextMessage.requestFocus();

        mSensorManager.registerListener((SensorEventListener) this, mAccSensor, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener((SensorEventListener) this, mGyroSensor, SensorManager.SENSOR_DELAY_GAME);


        String ses = String.valueOf(SystemClock.elapsedRealtimeNanos() * NS2MS).replace(".","");
        File filesDir = new File(getFilesDir(), ses);
        filesDir.mkdir();
        editor.putString("session", ses);
        editor.commit();

        sLogger = new Logger(getApplicationContext(), ses, Logger.LOG_SENSOR);
        kLogger = new Logger(getApplicationContext(), ses, Logger.LOG_KEY);
        mTextMessage.setLogger(kLogger);

        setSentence();

        mTextMessage.showKeyboard();
        //mSesEditText.setEnabled(false);
    }

    private void setSentence() {
        Random random = new Random();
        int senIndex = random.nextInt(trainSentences.length);
        //String sentence = trainSentences[senIndex] + trainSentences[senIndex] + testSentences[senIndex];
        mTextMessage.setText(trainSentences[senIndex]);
    }


    private void stopSensorLogging() {
        Log.i(TAG, "Stopping sensor log");
        isSensorLogging = false;
        mControlButton.setText(getResources().getString(R.string.start));
        mTextMessage.setVisibility(View.GONE);
        mTextMessage.hideKeyboard();
        mSensorManager.unregisterListener((SensorEventListener) this);
        //mSesEditText.setText("");
        //mSesEditText.setEnabled(true);
        sLogger.close();
        kLogger.close();
        prepareZip();
        share();
    }

    private void prepareZip() {
        String ses = sharedPreferences.getString("session", "NULL");
        JSONObject metaData = new JSONObject();
        StringBuilder accountNames = new StringBuilder();

        String email = "";
        Account[] accounts = AccountManager.get(this).getAccountsByType("com.google");
        if(accounts.length >= 1) {
            email = accounts[0].name;
        }

        try {
            metaData.put("Manufacturer", Build.MANUFACTURER);
            metaData.put("Model", Build.MODEL);
            metaData.put("Timestamp", Calendar.getInstance().getTime().toString());
            metaData.put("Identity", email);
            String android_id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            metaData.put("AndroidID", android_id);

        } catch (JSONException e) {
            e.printStackTrace();
        }

        FileOutputStream outputStream;
        try {
            outputStream = new FileOutputStream(new File(getFilesDir().getPath() + "/" + ses + "/metadata.json"));
            outputStream.write(metaData.toString().getBytes());
            outputStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }


        try {
            ZipFile zipFile= new ZipFile(new File(getFilesDir(), ses + ".zip"));
            ZipParameters parameters = new ZipParameters();
            parameters.setCompressionMethod(Zip4jConstants.COMP_DEFLATE);
            parameters.setCompressionLevel(Zip4jConstants.DEFLATE_LEVEL_NORMAL);
            zipFile.addFolder(new File(getFilesDir(), ses), parameters);
        } catch (ZipException e) {
            e.printStackTrace();
        }

    }

    public void share() {
        String ses = sharedPreferences.getString("session", "NULL");
        File zfile = new File(getFilesDir(), ses + ".zip");
        if(zfile.exists()) {
            //FirebaseStorage storage = FirebaseStorage.getInstance();
            //StorageReference storageRef = storage.getReference(ses + ".zip");
            //Uri file = Uri.fromFile(zfile);
//            StorageReference fileRef = storage.getReference(file.getLastPathSegment());
//            UploadTask uploadTask = fileRef.putFile(file);
//            Toast.makeText(getApplicationContext(), "Starting Upload", Toast.LENGTH_LONG).show();
//            editor.putBoolean("uploading", true);
//            editor.commit();
//            mControlButton.setVisibility(View.INVISIBLE);
//            // Register observers to listen for when the download is done or if it fails
//            uploadTask.addOnFailureListener(new OnFailureListener() {
//                @Override
//                public void onFailure(@NonNull Exception exception) {
//                    // Handle unsuccessful uploads
//                    Toast.makeText(getApplicationContext(), "Error Uploading", Toast.LENGTH_LONG).show();
//                    editor.putBoolean("uploading", false);
//                    editor.commit();
//                    mControlButton.setVisibility(View.VISIBLE);
//                }
//            }).addOnSuccessListener(new OnSuccessListener<UploadTask.TaskSnapshot>() {
//                @Override
//                public void onSuccess(UploadTask.TaskSnapshot taskSnapshot) {
//                    // taskSnapshot.getMetadata() contains file metadata such as size, content-type, etc.
//                    // ...
//                    Toast.makeText(getApplicationContext(), "Uploading Successful", Toast.LENGTH_LONG).show();
//                    editor.putBoolean("uploading", false);
//                    editor.commit();
//                    mControlButton.setVisibility(View.VISIBLE);
//                }
//            });
            uploadMultipart(getApplicationContext(), zfile.getAbsolutePath());
        } else {
            Toast.makeText(getApplicationContext(), "Error Uploading", Toast.LENGTH_LONG).show();
        }
    }

    public void uploadMultipart(final Context context, String path) {
        try {
            String uploadId =
                    new MultipartUploadRequest(context, "http://192.168.43.251:8000/upload")
                            // starting from 3.1+, you can also use content:// URI string instead of absolute file
                            .addFileToUpload(path, "file")
                            .setNotificationConfig(new UploadNotificationConfig())
                            .setMaxRetries(1)
                            .startUpload();
        } catch (Exception exc) {
            Log.e("AndroidUploadService", exc.getMessage(), exc);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(isSensorLogging) {
            stopSensorLogging();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        String log =  (long)(event.timestamp*NS2MS) + ","+ event.sensor.getName().substring(7) + "," + event.values[0] + "," + event.values[1] + "," + event.values[2];
        //Log.d(TAG, log);
        sLogger.appendLog(log);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }
}
