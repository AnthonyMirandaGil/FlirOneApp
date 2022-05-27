/*******************************************************************
 * @title FLIR THERMAL SDK
 * @file CameraHandler.java
 * @Author FLIR Systems AB
 *
 * @brief Helper class that encapsulates *most* interactions with a FLIR ONE camera
 *
 * Copyright 2019:    FLIR Systems
 ********************************************************************/
package com.samples.flironecamera;



import android.graphics.Bitmap;
import android.util.Log;

import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.Rectangle;
import com.flir.thermalsdk.image.TemperatureUnit;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.ThermalValue;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.image.palettes.Palette;
import com.flir.thermalsdk.image.palettes.PaletteManager;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.CameraInformation;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.ConnectParameters;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.remote.Calibration;
import com.flir.thermalsdk.live.remote.PaletteController;
import com.flir.thermalsdk.live.remote.Property;
import com.flir.thermalsdk.live.remote.RemoteControl;
import com.flir.thermalsdk.live.streaming.ThermalImageStreamListener;
import com.flir.thermalsdk.utils.Consumer;

import org.bytedeco.javacv.AndroidFrameConverter;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;

import static org.bytedeco.opencv.global.opencv_imgproc.*;

import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Encapsulates the handling of a FLIR ONE camera or built in emulator, discovery, connecting and start receiving images.
 * All listeners are called from Thermal SDK on a non-ui thread
 * <p/>
 * Usage:
 * <pre>
 * Start discovery of FLIR FLIR ONE cameras or built in FLIR ONE cameras emulators
 * {@linkplain #startDiscovery(DiscoveryEventListener, DiscoveryStatus)}
 * Use a discovered Camera {@linkplain Identity} and connect to the Camera
 * (note that calling connect is blocking and it is mandatory to call this function from a background thread):
 * {@linkplain #connect(Identity, ConnectionStatusListener)}
 * Once connected to a camera
 * {@linkplain #startStream(StreamDataListener)}
 * </pre>
 * <p/>
 * You don't *have* to specify your application to listen or USB intents but it might be beneficial for you application,
 * we are enumerating the USB devices during the discovery process which eliminates the need to listen for USB intents.
 * See the Android documentation about USB Host mode for more information
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";

    private StreamDataListener streamDataListener;
    private Palette palette;
    public final String LOG_TAG = "CameraHandler";
    public FusionMode fusionMode = FusionMode.THERMAL_ONLY;

    public interface StreamDataListener {
        void images(FrameDataHolder dataHolder);

        void images(Bitmap msxBitmap, Bitmap dcBitmap, double [] temperatures);
    }

    private CaptureImageListener captureImageListener;

    public interface CaptureImageListener {
        void captureImage(ThermalImage thermalImage);
        void captureImage(Bitmap msxBitmap, double [] temperatures);
    }


    //Discovered FLIR cameras
    LinkedList<Identity> foundCameraIdentities = new LinkedList<>();

    //A FLIR Camera
    private Camera camera;


    public interface DiscoveryStatus {
        void started();

        void stopped();
    }

    public CameraHandler() {
        Log.d(TAG, "CameraHandler constr");
    }

    /**
     * Start discovery of USB and Emulators
     */
    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener, CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    /**
     * Stop discovery of USB and Emulators
     */
    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    public void setCapturePicture(CaptureImageListener listener){
        this.captureImageListener = listener;
        if (camera == null || !camera.isConnected()) {
            Log.w(TAG, "setCapturePicture, failed, camera was null");
            return;
        }
    }

    public void setPalette(Palette palette){
        this.palette = palette;
    }
    public void setFusionMode(FusionMode fusionMode){
        this.fusionMode = fusionMode;
    }

    public synchronized void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        Log.d(TAG, "connect identity: " + identity);
        camera = new Camera();
        camera.connect(identity, connectionStatusListener, new ConnectParameters());
    }

    public synchronized void disconnect() {
        Log.d(TAG, "disconnect");
        if (camera == null) {
            return;
        }
        if (camera.isGrabbing()) {
            camera.unsubscribeAllStreams();
        }
        camera.disconnect();
        camera = null;
    }

    /*public synchronized void performNuc() {
        Log.d(TAG, "performNuc");
        if (camera == null) {
            return;
        }
        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) {
            return;
        }
        Calibration calib = rc.getCalibration();
        if (calib == null) {
            return;
        }
        calib.nuc().executeSync();
    }*/

    /**
     * Start a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public synchronized void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;
        if (camera == null || !camera.isConnected()) {
            Log.w(TAG, "startStream, failed, camera was null");
            return;
        }
        camera.subscribeStream(thermalImageStreamListener);
    }


    /**
     * Stop a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public synchronized void stopStream(ThermalImageStreamListener listener) {
        if (camera == null) {
            Log.w(TAG, "stopStream, failed, camera was null");
            return;
        }
        camera.unsubscribeStream(listener);

    }


    /**
     * Add a found camera to the list of known cameras
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    @Nullable
    public Identity get(int i) {
        return foundCameraIdentities.get(i);
    }

    /**
     * Get a read only list of all found cameras
     */
    @Nullable
    public List<Identity> getCameraList() {
        return Collections.unmodifiableList(foundCameraIdentities);
    }

    /**
     * Clear all known network cameras
     */
    public void clear() {
        foundCameraIdentities.clear();
    }

    @Nullable
    public Identity getCppEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("C++ Emulator")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOneEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOne() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            boolean isFlirOneEmulator = foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE");
            boolean isCppEmulator = foundCameraIdentity.deviceId.contains("C++ Emulator");
            if (!isFlirOneEmulator && !isCppEmulator) {
                return foundCameraIdentity;
            }
        }

        return null;
    }

    private synchronized void withImage(ThermalImageStreamListener listener, Consumer<ThermalImage> functionToRun) {
        if (camera == null) {
            return;
        }
        camera.withImage(listener, functionToRun);
    }


    /**
     * Called whenever there is a new Thermal Image available, should be used in conjunction with {@link Consumer}
     */
    private final ThermalImageStreamListener thermalImageStreamListener = new ThermalImageStreamListener() {
        @Override
        public void onImageReceived() {
            //Will be called on a non-ui thread
            Log.d(TAG, "onImageReceived(), we got another ThermalImage");
            withImage(this, handleIncomingImage);
        }
    };


    /**
     * Function to process a Thermal Image and update UI
     */
    private final Consumer<ThermalImage> handleIncomingImage = new Consumer<ThermalImage>() {
        @Override
        public void accept(ThermalImage thermalImage) {
            Log.d(TAG, "accept() called with: thermalImage = [" + thermalImage.getDescription() + "]");
            //Will be called on a non-ui thread,
            // extract information on the background thread and send the specific information to the UI thread

            //Get a bitmap with only IR data

            if(palette == null){
                palette = PaletteManager.getDefaultPalettes().get(0);
            }
            //new Palette('as', true);

            Bitmap msxBitmap;
            {   thermalImage.setPalette(palette);
                thermalImage.getFusion().setFusionMode(fusionMode);
                msxBitmap = BitmapAndroid.createBitmap(thermalImage.getImage()).getBitMap();
            }

            //Get a bitmap with the visual image, it might have different dimensions then the bitmap from THERMAL_ONLY
            Bitmap dcBitmap = BitmapAndroid.createBitmap(thermalImage.getFusion().getPhoto()).getBitMap();
            // aqui  debe ir el guardar

            thermalImage.setTemperatureUnit(TemperatureUnit.CELSIUS);

            double [] temperatures = thermalImage.getValues(new Rectangle(0, 0, thermalImage.getWidth(), thermalImage.getHeight()));
            ThermalValue centerTemp = thermalImage.getValueAt(new com.flir.thermalsdk.image.Point(thermalImage.getWidth()/ 2, thermalImage.getHeight()/2));

            Log.d(TAG, "adding images to cache");
            Log.d(TAG, "Thermal Image Size: (" + thermalImage.getWidth() + "," + thermalImage.getHeight() + ")");
            Log.d(TAG, "MsxBitMap Image Size: (" + msxBitmap.getWidth() + "," + msxBitmap.getHeight() + ")");
            int centerY = msxBitmap.getHeight() / 2;
            int centerX  = msxBitmap.getWidth() / 2;

            AndroidFrameConverter converterToFrame = new AndroidFrameConverter();
            Frame frame = converterToFrame.convert(msxBitmap);
            // Umbral para detectar objetos


            OpenCVFrameConverter.ToMat converterToMat = new OpenCVFrameConverter.ToMat();
            Mat matImage = converterToMat.convert(frame);

            putText(matImage,centerTemp.asCelsius() + "", new Point(centerX + 20, centerY), FONT_HERSHEY_COMPLEX, 1.0, new Scalar(0, 255, 0, 0.2));
            circle(matImage,  new Point(centerX,centerY), 4, new Scalar(255,0,0, 0.2), 4, LINE_AA,0);

            float thershold = 30;
            // detect hot object

            //Mat m = temperatures >= thershold;

            //Mat matBinary = new Mat();

            //Frame newFrame = converterToMat.convert(matImage);
            Log.v(LOG_TAG, "Writing Frame");
            frame = converterToMat.convert(matImage);
            msxBitmap = converterToFrame.convert(frame);

            streamDataListener.images(msxBitmap, dcBitmap, temperatures);
            //double [] temperatures = {};

            // captureImageListener.captureImage(msxBitmap, temperatures);
            captureImageListener.captureImage(thermalImage);
            //captureImageListener.captureImage(thermalImage);
        }
    };

    public String getDeviceInfo() {
        if (camera == null) {
            return "N/A";
        }
        RemoteControl rc = camera.getRemoteControl();
        if (rc == null) {
            return "N/A";
        }
        CameraInformation ci = rc.cameraInformation().getSync();
        if (ci == null) {
            return "N/A";
        }
        return ci.displayName + ", SN: " + ci.serialNumber;
    }

}
