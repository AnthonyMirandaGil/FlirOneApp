/*
 * ******************************************************************
 * @title FLIR THERMAL SDK
 * @file MainActivity.java
 * @Author FLIR Systems AB
 *
 * @brief  Main UI of test application
 *
 * Copyright 2019:    FLIR Systems
 * ******************************************************************/
package com.samples.flironecamera;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.flir.thermalsdk.ErrorCode;
import com.flir.thermalsdk.androidsdk.ThermalSdkAndroid;
import com.flir.thermalsdk.androidsdk.live.connectivity.UsbPermissionHandler;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.image.fusion.ThermalFusion;
import com.flir.thermalsdk.image.palettes.Palette;
import com.flir.thermalsdk.image.palettes.PaletteManager;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.remote.PaletteController;
import com.flir.thermalsdk.log.ThermalLog;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.libdc1394.Log_handler_int_BytePointer_Pointer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Sample application for scanning a FLIR ONE or a built in emulator
 * <p>
 * See the {@link CameraHandler} for how to preform discovery of a FLIR ONE camera, connecting to it and start streaming images
 * <p>
 * The MainActivity is primarily focused to "glue" different helper classes together and updating the UI components
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    //Handles Android permission for eg Network
    private PermissionHandler permissionHandler;

    //Handles network camera operations
    private CameraHandler cameraHandler;

    private Identity connectedIdentity = null;
    private TextView connectionStatus;
    private TextView discoveryStatus;
    private TextView deviceInfo;

    private ImageView msxImage;
    private Button btnTakePicture;
    private Button recordButton;

    private final LinkedBlockingQueue<FrameDataHolder> framesBuffer = new LinkedBlockingQueue<>(21);
    private final UsbPermissionHandler usbPermissionHandler = new UsbPermissionHandler();
    private static VideoRecorder videoRecorder;
    private Spinner spinner;
    private final  int REQUEST_PERMISSION_WRITE_EXTERNAL_STORE = 101;
    private ArrayList<String> palettes;
    private ArrayAdapter<String> palletesAdapter;
    private Spinner spinnerFusion;
    private ArrayList<String> fusionModes;
    private ArrayAdapter<String> fusionModeAdapter;

    /**
     * Show message on the screen
     */
    public interface ShowMessage {
        void show(String message);
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setupViews();
        ThermalLog.LogLevel enableLoggingInDebug = BuildConfig.DEBUG ? ThermalLog.LogLevel.DEBUG : ThermalLog.LogLevel.NONE;

        //ThermalSdkAndroid has to be initiated from a Activity with the Application Context to prevent leaking Context,
        // and before ANY    using any ThermalSdkAndroid functions
        //ThermalLog will show log from the Thermal SDK in standards android log framework
        ThermalSdkAndroid.init(getApplicationContext(), enableLoggingInDebug);

        permissionHandler = new PermissionHandler(showMessage, MainActivity.this);

        //permissionHandler.checkForRecordAudioPermission();

        cameraHandler = new CameraHandler();


        /*showSDKversion(ThermalSdkAndroid.getVersion());
        showSDKCommitHash(ThermalSdkAndroid.getCommitHash());
        */

        // Set spinner palettes
        palettes = new ArrayList<String>();
        palettes.add("Seleccionar");

        palletesAdapter = new ArrayAdapter<String>(this, R.layout.support_simple_spinner_dropdown_item, palettes);
        spinner.setAdapter(palletesAdapter);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if(!palettes.get(position).equals("Seleccionar")){
                    cameraHandler.setPalette(PaletteManager.getDefaultPalettes().get(position));
                }

            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        fusionModes = new ArrayList<String>(Arrays.asList(new String[]{
                "Seleccionar",
                "Thermal Only",
                "Visual Only",
                "Blending",
                "MSX",
                "Thermal Fusion",
                "Pinture in picture",
                "Color nigth vision"}));

        fusionModeAdapter = new ArrayAdapter<String>(this, R.layout.support_simple_spinner_dropdown_item, fusionModes);
        spinnerFusion.setAdapter(fusionModeAdapter);

        spinnerFusion.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                FusionMode fusionMode = FusionMode.THERMAL_ONLY;
                switch (position){
                    case 0:
                        fusionMode = FusionMode.THERMAL_ONLY;
                        break;
                    case 1:
                        fusionMode = FusionMode.THERMAL_ONLY;
                        break;
                    case 2:
                        fusionMode = FusionMode.VISUAL_ONLY;
                        break;
                    case 3:
                        fusionMode = FusionMode.BLENDING;
                        break;
                    case 4:
                        fusionMode = FusionMode.MSX;
                        break;
                    case 5:
                        fusionMode = FusionMode.THERMAL_FUSION;
                        break;
                    case 6:
                        fusionMode = FusionMode.PICTURE_IN_PICTURE;
                        break;
                    case 7:
                        fusionMode = FusionMode.COLOR_NIGHT_VISION;
                        break;
                    default:
                        fusionMode = FusionMode.THERMAL_ONLY;
                        break;
                }
                cameraHandler.setFusionMode(fusionMode);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });




        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)!= PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO)!= PackageManager.PERMISSION_GRANTED ){
                    String [] permissions = new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO};

                    ActivityCompat.requestPermissions(MainActivity.this, permissions, REQUEST_PERMISSION_WRITE_EXTERNAL_STORE);
                    return;
                }

                if(videoRecorder == null || videoRecorder.recording == false) {
                    Toast.makeText(getApplicationContext(),"Start Video", Toast.LENGTH_SHORT).show();
                    videoRecorder  = new VideoRecorder(MainActivity.this);
                    // Store Data Temperatures

                    try {
                        videoRecorder.start();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    recordButton.setText("Stop Video");
                }else {
                    Toast.makeText(getApplicationContext(),"Stop Video", Toast.LENGTH_SHORT).show();
                    videoRecorder.stop();
                    videoRecorder = null;
                    recordButton.setText("Start Video");

                }
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        //Always close the connection with a connected FLIR ONE when going into background
        disconnect();
    }

    public void startDiscovery(View view) {
        startDiscovery();
    }

    public void stopDiscovery(View view) {
        stopDiscovery();
    }

    public void connectFlirOne(View view) {
        connect(cameraHandler.getFlirOne());
    }

    public void connectSimulatorOne(View view) {
        connect(cameraHandler.getCppEmulator());
    }

    public void connectSimulatorTwo(View view) {
        connect(cameraHandler.getFlirOneEmulator());
    }

    public void disconnect(View view) {
        disconnect();
    }

    /*public void performNuc(View view) {
        cameraHandler.performNuc();
    }*/

    /**
     * Handle Android permission request response for Bluetooth permissions
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String [] permissions, int [] grantResults) {
        Log.d(TAG, "onRequestPermissionsResult() called with: requestCode = [" + requestCode + "], permissions = ["
                + Arrays.toString(permissions) + "], grantResults = [" + Arrays.toString(grantResults) + "]");
        permissionHandler.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_WRITE_EXTERNAL_STORE){
            if(grantResults[0] == PackageManager.PERMISSION_DENIED){
                Toast.makeText(getApplicationContext(), "Sorry write store permision is necesary", Toast.LENGTH_LONG).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * Connect to a Camera
     */
    private void connect(Identity identity) {
        //We don't have to stop a discovery but it's nice to do if we have found the camera that we are looking for
        cameraHandler.stopDiscovery(discoveryStatusListener);

        if (connectedIdentity != null) {
            Log.d(TAG, "connect(), in *this* code sample we only support one camera connection at the time");
            showMessage.show("connect(), in *this* code sample we only support one camera connection at the time");
            return;
        }

        if (identity == null) {
            Log.d(TAG, "connect(), can't connect, no camera available");
            showMessage.show("connect(), can't connect, no camera available");
            return;
        }

        connectedIdentity = identity;

        updateConnectionText(identity, "CONNECTING");
        //IF your using "USB_DEVICE_ATTACHED" and "usb-device vendor-id" in the Android Manifest
        // you don't need to request permission, see documentation for more information
        if (UsbPermissionHandler.isFlirOne(identity)) {
            usbPermissionHandler.requestFlirOnePermisson(identity, this, permissionListener);
        } else {
            doConnect(identity);
        }
    }

    private final UsbPermissionHandler.UsbPermissionListener permissionListener = new UsbPermissionHandler.UsbPermissionListener() {
        @Override
        public void permissionGranted(Identity identity) {
            doConnect(identity);
        }

        @Override
        public void permissionDenied(Identity identity) {
            MainActivity.this.showMessage.show("Permission was denied for identity ");
        }

        @Override
        public void error(UsbPermissionHandler.UsbPermissionListener.ErrorType errorType, final Identity identity) {
            MainActivity.this.showMessage.show("Error when asking for permission for FLIR ONE, error:" + errorType + " identity:" + identity);
        }
    };

    private void doConnect(Identity identity) {
        new Thread(() -> {
            try {
                cameraHandler.connect(identity, connectionStatusListener);
                runOnUiThread(() -> {
                    updateConnectionText(identity, "CONNECTED");

                    int numPalettes = PaletteManager.getDefaultPalettes().size();

                    for (int i = 0; i < numPalettes; i++ ){
                        String paletteName = PaletteManager.getDefaultPalettes().get(i).name.toLowerCase(Locale.ROOT);
                        palettes.add(paletteName);
                    }

                    Log.v("Main",  "Num Paletes: "  + numPalettes);

                    palletesAdapter.notifyDataSetChanged();
                    //deviceInfo.setText(cameraHandler.getDeviceInfo());
                    cameraHandler.setCapturePicture(captureImageListener);
                    //cameraHandler.setVideoRecord(recordVideoListener);
                });

                cameraHandler.startStream(streamDataListener);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    Log.d(TAG, "Could not connect: " + e);
                    updateConnectionText(identity, "DISCONNECTED");
                });
            }
        }).start();
    }

    /**
     * Disconnect to a camera
     */
    private void disconnect() {
        updateConnectionText(connectedIdentity, "DISCONNECTING");
        Log.d(TAG, "disconnect() called with: connectedIdentity = [" + connectedIdentity + "]");
        connectedIdentity = null;
        new Thread(() -> {
            cameraHandler.disconnect();
            runOnUiThread(() -> {
                updateConnectionText(null, "DISCONNECTED");
            });
        }).start();
    }

    /**
     * Update the UI text for connection status
     */
    private void updateConnectionText(Identity identity, String status) {
        String deviceId = identity != null ? identity.deviceId : "";
        //connectionStatus.setText(getString(R.string.connection_status_text, deviceId + " " + status));
    }

    /**
     * Start camera discovery
     */
    private void startDiscovery() {
        cameraHandler.startDiscovery(cameraDiscoveryListener, discoveryStatusListener);
    }

    /**
     * Stop camera discovery
     */
    private void stopDiscovery() {
        cameraHandler.stopDiscovery(discoveryStatusListener);
    }

    /**
     * Callback for discovery status, using it to update UI
     */
    private final CameraHandler.DiscoveryStatus discoveryStatusListener = new CameraHandler.DiscoveryStatus() {
        @Override
        public void started() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "discovering"));
        }

        @Override
        public void stopped() {
            discoveryStatus.setText(getString(R.string.connection_status_text, "not discovering"));
        }
    };

    /**
     * Camera connecting state thermalImageStreamListener, keeps track of if the camera is connected or not
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private final ConnectionStatusListener connectionStatusListener = new ConnectionStatusListener() {
        @Override
        public void onDisconnected(@org.jetbrains.annotations.Nullable ErrorCode errorCode) {
            Log.d(TAG, "onDisconnected errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    updateConnectionText(connectedIdentity, "DISCONNECTED");
                }
            });
        }
    };

    private final CameraHandler.CaptureImageListener captureImageListener = new CameraHandler.CaptureImageListener() {
        @Override
        public void captureImage(ThermalImage thermalImage) {
            btnTakePicture.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {

                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

                        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};

                        ActivityCompat.requestPermissions(MainActivity.this, permissions, REQUEST_PERMISSION_WRITE_EXTERNAL_STORE);
                        return;
                    }

                    Long timeSeconds = System.currentTimeMillis() / 1000;
                    String stringTs = timeSeconds.toString();
                    //String fileName = Environment.getExternalStorageDirectory() + "/" + stringTs + ".jpg";
                    String fileName = Environment.DIRECTORY_PICTURES + File.separator  + stringTs + ".jpg";
                    try {
                        thermalImage.saveAs(fileName);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    Toast.makeText(getApplicationContext(), "Imagen Guardado en: " + fileName, Toast.LENGTH_LONG).show();
                }
            });
        }


        @Override
        public void captureImage(Bitmap msxBitmap, double[] temperatures) {
            btnTakePicture.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        String[] permissions = new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE};

                        ActivityCompat.requestPermissions(MainActivity.this, permissions, REQUEST_PERMISSION_WRITE_EXTERNAL_STORE);
                        return;
                    }

                    Long timeSeconds = System.currentTimeMillis() / 1000;
                    String stringTs = timeSeconds.toString();
                    //String fileName = Environment.getExternalStorageDirectory() + "/" + stringTs+ ".jpg";
                    String fileName = "flir_" + stringTs;
                    String folderName = "flirOneImages";
                    File imageFile;
                    Uri imageUri = null;
                    OutputStream fos = null;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentResolver resolver = getApplicationContext().getContentResolver();
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
                        contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH,
                                Environment.DIRECTORY_PICTURES + File.separator + folderName);

                        imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues);

                        if (imageUri == null) {
                            MainActivity.this.showMessage.show("Error al crear new MediaStore record");
                            try {
                                throw new IOException("Failed to create new MediaStore record.");
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }

                        try {
                            fos = resolver.openOutputStream(imageUri);
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        }


                    } else {

                        try {
                            File imagesDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + File.separator + folderName);
                            if (!imagesDir.exists()) {
                                imagesDir.mkdir();
                            }
                            imageFile = new File(imagesDir, fileName + ".png");
                            fos = new FileOutputStream(imageFile);
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        }

                        //getExternalFilesDir(null);

                    }

                    if (!msxBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                        Toast.makeText(getApplicationContext(), "Error al Guardar image en: " + fileName, Toast.LENGTH_LONG).show();
                        try {
                            throw new IOException("Failed to save bitmap.");
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }

                    //final ExifInterface exif = new ExifInterface();

                    // save thermal information
                    new Thread(() -> {
                         try {
                             ThermalCSVWriter thermalCSVWriter = new ThermalCSVWriter(getBaseContext(), "image_" + fileName);
                             thermalCSVWriter.saveThermalValues(temperatures, 0);
                             thermalCSVWriter.close();
                             runOnUiThread(()-> {
                                 Toast.makeText(getApplicationContext(), "Informacion Termica Guardada Image: " + fileName, Toast.LENGTH_LONG).show();
                             });
                         } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }).start();
                    Toast.makeText(getApplicationContext(), "Imagen Guardado en: " + fileName, Toast.LENGTH_LONG).show();
                }
            });
        }
    };


    private final CameraHandler.StreamDataListener streamDataListener = new CameraHandler.StreamDataListener() {

        @Override
        public void images(FrameDataHolder dataHolder) {

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    msxImage.setImageBitmap(dataHolder.msxBitmap);
                }
            });
        }

        @Override
        public void images(Bitmap msxBitmap, Bitmap dcBitmap, double [] temperatures) {

            try {
                framesBuffer.put(new FrameDataHolder(msxBitmap, dcBitmap));
            } catch (InterruptedException e) {
                //if interrupted while waiting for adding a new item in the queue
                Log.e(TAG, "images(), unable to add incoming images to frames buffer, exception:" + e);
            }

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Log.d(TAG, "framebuffer size:" + framesBuffer.size());
                    FrameDataHolder poll = framesBuffer.poll();
                    msxImage.setImageBitmap(Objects.requireNonNull(poll).msxBitmap);

                    // start recorder
                    if(videoRecorder!= null && videoRecorder.recording == true){
                        try {
                            videoRecorder.recordImage(Objects.requireNonNull(poll).msxBitmap, temperatures);
                        } catch (FFmpegFrameRecorder.Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            });

        }
    };

    /**
     * Camera Discovery thermalImageStreamListener, is notified if a new camera was found during a active discovery phase
     * <p>
     * Note that callbacks are received on a non-ui thread so have to eg use {@link #runOnUiThread(Runnable)} to interact view UI components
     */
    private final DiscoveryEventListener cameraDiscoveryListener = new DiscoveryEventListener() {
        @Override
        public void onCameraFound(Identity identity) {
            Log.d(TAG, "onCameraFound identity:" + identity);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    cameraHandler.add(identity);
                }
            });
        }

        @Override
        public void onDiscoveryError(CommunicationInterface communicationInterface, ErrorCode errorCode) {
            Log.d(TAG, "onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    stopDiscovery();
                    MainActivity.this.showMessage.show("onDiscoveryError communicationInterface:" + communicationInterface + " errorCode:" + errorCode);
                }
            });
        }
    };

    private final ShowMessage showMessage = new ShowMessage() {
        @Override
        public void show(String message) {
            Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
        }
    };

    /*private void showSDKversion(String version) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_version);
        String sdkVersionText = getString(R.string.sdk_version_text, version);
        sdkVersionTextView.setText(sdkVersionText);
    }


    private void showSDKCommitHash(String version) {
        TextView sdkVersionTextView = findViewById(R.id.sdk_commit_hash);
        String sdkVersionText = getString(R.string.sdk_commit_hash_text, version);
        sdkVersionTextView.setText(sdkVersionText);
    }*/

    private void setupViews() {
        // connectionStatus = findViewById(R.id.connection_status_text);
        discoveryStatus = findViewById(R.id.discovery_status);
        // deviceInfo = findViewById(R.id.device_info_text);

        msxImage = findViewById(R.id.msx_image);
        btnTakePicture = findViewById(R.id.btnTakePicture);
        recordButton = findViewById(R.id.recordButton);
        spinner = findViewById(R.id.spinner);
        spinnerFusion = findViewById(R.id.spinnerFusion);
    }
}
