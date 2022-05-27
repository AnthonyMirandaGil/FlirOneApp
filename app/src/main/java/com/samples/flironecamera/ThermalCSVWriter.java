package com.samples.flironecamera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;

public class ThermalCSVWriter {
    private final Context context;
    private Writer writer;
    private int numframe = 0;

    ThermalCSVWriter(Context context, String filename){
        this.context = context;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q){
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            Long time = System.currentTimeMillis() / 100;
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename + "_data_temperatures.csv");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
            Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
            try {
                OutputStream outputStream = resolver.openOutputStream(uri);
                writer = new OutputStreamWriter(outputStream);
                // First column
                writer.append("X");
                writer.append(',');
                writer.append("Y");
                writer.append(',');
                writer.append("Temperature");
                writer.append(',');
                writer.append("Frame");
                writer.append(',');

                writer.append('\n');
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }
    }

    private void writeRow(int x,int y, double temperature, int frame ){
        try {
            this.writer.append(Integer.toString(x));
            this.writer.append(',');
            this.writer.append(Integer.toString(y));
            this.writer.append(',');
            this.writer.append(String.valueOf(temperature));
            this.writer.append(',');
            this.writer.append(Integer.toString(frame));
            this.writer.append(',');
            this.writer.append('\n');
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void saveThermalValues(double [] temperatures){
        int width = 640 ;
        int heigth = 480;
        int n;
        double valueTemp;
        for (int x =0 ; x < width ; x++){
            for(int y =0; y< heigth; y++) {
                n = x + (y * width);
                valueTemp = temperatures[n];
                this.writeRow(x, y, valueTemp, numframe);
            }
        }
        this.numframe++;
    }

    public void saveThermalValues(double [] temperatures, int frame ){
        int width = 640 ;
        int heigth = 480;
        int n;
        double valueTemp;
        for (int x =0 ; x < width ; x++){
            for(int y =0; y< heigth; y++) {
                n = x + (y * width);
                valueTemp = temperatures[n];
                this.writeRow(x, y, valueTemp, frame);
            }
        }

    }
    public void close() throws IOException {
        this.writer.close();
    }

}
