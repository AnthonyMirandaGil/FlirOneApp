package com.samples.flironecamera;

//import static org.opencv.core.CvType.CV_8UC3;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.AndroidFrameConverter;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Queue;

public class VideoRecorder {
    private final static String LOG_TAG = "VideoRecorder";
    boolean recording;
    File dir;
    private volatile FFmpegFrameRecorder recorder;
    private int imageWidth = 1080;
    private int imageHeight = 1440;
    private int frameRate = 30;
    private Context context;
    private String TAG_LOG = "VideoWriter";
    private String ffmpeg_path;
    long startTime = 0;
    private FileOutputStream outVideo;
    private ContentValues valuesVideos;
    private Uri uriSavedVideo;
    private ParcelFileDescriptor pfd;
    private File videoFile;
    private int sampleAudioRateInHz = 44100;
    /* audio data getting thread */
    private AudioRecord audioRecord;
    private AudioRecordRunnable audioRecordRunnable;
    private Thread audioThread;
    final int RECORD_LENGTH = 0;
    int imagesIndex, samplesIndex;
    ShortBuffer[] samples;
    volatile boolean runAudioThread = true;
    private String videoFileName;
    private int numFrame = 0;
    private Queue<double []> bufferTemperatures;
    private ThermalCSVWriter thermalCSVWriter;
    private ThermalWriterRunnable thermalWriterRunnable;
    private Thread csvThread;

    public VideoRecorder(Context context) {
        //this.dir = dir;
        this.context = context;
        bufferTemperatures = new LinkedList<double []>();
    }

    public String getVideoFileName(){
        return this.videoFileName;
    }

    public int getNumFrame(){
        return this.numFrame;
    }

    public void start() throws IOException {

        ContentResolver resolver = context.getContentResolver();
        Long ts = System.currentTimeMillis() / 1000;
        videoFileName = "stream_"+ ts + ".mkv";

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            try {
                valuesVideos = new ContentValues();
                valuesVideos.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies");
                valuesVideos.put(MediaStore.Video.Media.TITLE, videoFileName);
                valuesVideos.put(MediaStore.Video.Media.DISPLAY_NAME, videoFileName);
                valuesVideos.put(MediaStore.Video.Media.MIME_TYPE, "video/mkv");
                valuesVideos.put(
                        MediaStore.Video.Media.DATE_ADDED,
                        System.currentTimeMillis() /100
                );
                Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                valuesVideos.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
                valuesVideos.put(MediaStore.Video.Media.IS_PENDING, 1);
                uriSavedVideo = resolver.insert(collection, valuesVideos);
                pfd = context.getContentResolver().openFileDescriptor(uriSavedVideo, "w");
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            outVideo = new FileOutputStream(pfd.getFileDescriptor());
            recorder = new FFmpegFrameRecorder(outVideo, imageWidth, imageHeight, 1);
        } else {
            videoFile = new File(context.getExternalFilesDir(null), videoFileName );
            Log.w(LOG_TAG, "init recorder");
            boolean mk = videoFile.getParentFile().mkdirs();
            Log.v(TAG_LOG, "Create File Video");
            Log.v(TAG_LOG, "Mkdir: " + mk);
            boolean del = videoFile.delete();
            Log.v(TAG_LOG, "Del: " + del);

            boolean created = videoFile.createNewFile();
            Log.v(TAG_LOG, "Created: " + created);
            ffmpeg_path = videoFile.getAbsolutePath();
            recorder = new FFmpegFrameRecorder(ffmpeg_path, imageWidth, imageHeight, 1);
        }

        recorder.setFormat("matroska");
        Log.v(TAG_LOG, "setFormat: matroska" );

        recorder.setFrameRate(frameRate);

        Log.v(TAG_LOG, "frameRate: 30" );

        recorder.setSampleRate(sampleAudioRateInHz);

        recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
        recorder.setVideoQuality(1);
        Log.v(TAG_LOG, "PixelFormat: 0" );

        audioRecordRunnable = new AudioRecordRunnable();
        audioThread = new Thread(audioRecordRunnable);

        runAudioThread = true;
        Log.i(LOG_TAG, "recorder initialize success");

        startTime = System.currentTimeMillis();
        recorder.start();
        // set recording true
        recording = true;
        audioThread.start();
        Log.i(TAG_LOG, "Started Recording");

        // csv writer
        String filename = this.videoFileName.split("\\.")[0];
        thermalCSVWriter = new ThermalCSVWriter(this.context, filename);

        //thermalWriterRunnable = new ThermalWriterRunnable();
        //csvThread = new Thread(thermalWriterRunnable);
        //csvThread.start();
        //File file = new File(dir, "flir_video_" + ts.toString() + ".mp4");
        //encoder = new SequenceEncoder(file);*/
    }

    public void recordImage(Bitmap bitmap, double [] temperatures) throws FFmpegFrameRecorder.Exception {
        //long videoTimestamp = 1000 * (System.currentTimeMillis() - startTime);
        long t = 1000 * (System.currentTimeMillis() - startTime);
        //recorder.setTimestamp(t);
        if(t > recorder.getTimestamp()) {
            recorder.setTimestamp(t);
        }
        //recorder.setTimestamp(videoTimestamp);
        //Frame frame = Frame.Buil
        // int depth = CV_8UC3;
        Frame frame = new AndroidFrameConverter().convert(bitmap);

        recorder.record(frame);

        //bufferTemperatures.add(temperatures);

        this.numFrame++;
        //recorder.recordImage(imageWidth, imageHeigth, CV_8UC3, 3, 0, -1, bitmap);
    }

    public void stop(){
        runAudioThread = false;
        try {
            audioThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        audioRecordRunnable = null;
        audioThread = null;

        recording = false;
        /*try {
            csvThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        thermalCSVWriter = null;
        csvThread = null;*/

        if (recorder != null && recording){
            Log.v(TAG_LOG, "Finish recording");
            try {
                recorder.stop();
                recorder.release();
                outVideo.close();
                pfd.close();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    valuesVideos.clear();
                    valuesVideos.put(MediaStore.Video.Media.IS_PENDING, 0);
                    context.getContentResolver().update(uriSavedVideo, valuesVideos, null, null);
                }
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            recorder = null;
        }
    }

    //---------------------------------------------
    // audio thread, gets and encodes audio data
    //---------------------------------------------
    class AudioRecordRunnable implements Runnable {

        @SuppressLint("MissingPermission")
        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            ShortBuffer audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleAudioRateInHz,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

            if(RECORD_LENGTH > 0) {
                samplesIndex = 0;
                samples = new ShortBuffer[RECORD_LENGTH * sampleAudioRateInHz * 2 / bufferSize + 1];
                for(int i = 0; i < samples.length; i++) {
                    samples[i] = ShortBuffer.allocate(bufferSize);
                }
            } else {
                audioData = ShortBuffer.allocate(bufferSize);
            }

            Log.d(LOG_TAG, "audioRecord.startRecording()");
            audioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while(runAudioThread) {
                if(RECORD_LENGTH > 0) {
                    audioData = samples[samplesIndex++ % samples.length];
                    audioData.position(0).limit(0);
                }
                //Log.v(LOG_TAG,"recording? " + recording);
                bufferReadResult = audioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
                if(bufferReadResult > 0) {
                    Log.v(LOG_TAG, "bufferReadResult: " + bufferReadResult);
                    // If "recording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if(recording) {
                        if(RECORD_LENGTH <= 0) {
                            try {
                                recorder.recordSamples(audioData);
                                //Log.v(LOG_TAG,"recording " + 1024*i + " to " + 1024*i+1024);
                            } catch(FFmpegFrameRecorder.Exception e) {
                                Log.v(LOG_TAG, e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
            Log.v(LOG_TAG, "AudioThread Finished, release audioRecord");
            /* encoding finish, release recorder */
            if(audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
                Log.v(LOG_TAG, "audioRecord released");
            }
        }
    }


    class ThermalWriterRunnable implements Runnable {

        @Override
        public void run() {
            while (recording == true || !bufferTemperatures.isEmpty()){
                if(thermalCSVWriter != null && !bufferTemperatures.isEmpty()) {
                    double [] temperatures = bufferTemperatures.poll();
                    thermalCSVWriter.saveThermalValues(temperatures);
                }

            }
            Log.d(TAG_LOG, "Csv Completed");
            try {
                thermalCSVWriter.close();
                thermalCSVWriter = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

