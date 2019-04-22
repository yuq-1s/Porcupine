/*
 * Copyright 2018 Picovoice Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.picovoice.porcupine.demo;


import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.picovoice.porcupinemanager.KeywordCallback;
import ai.picovoice.porcupinemanager.PorcupineManager;
import ai.picovoice.porcupinemanager.PorcupineManagerException;


public class MainActivity extends AppCompatActivity {
    private PorcupineManager porcupineManager = null;
    private MediaPlayer notificationPlayer;
    private RelativeLayout layout;
    private ToggleButton recordButton;

    private static final int SAMPLING_RATE_IN_HZ = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord recorder = null;
    private final AtomicBoolean recordingInProgress = new AtomicBoolean(false);
    private static final int BUFFER_SIZE_FACTOR = 2;
    private static final int BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLING_RATE_IN_HZ,
            CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR;
    private Thread recordingThread = null;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Utils.configurePorcupine(this);
        notificationPlayer = MediaPlayer.create(this, R.raw.notification);
        layout = findViewById(R.id.layout);
        recordButton = findViewById(R.id.record_button);

        // Make the footer linkable.
        TextView footer = findViewById(R.id.footer);
        footer.setMovementMethod(LinkMovementMethod.getInstance());
        // create the keyword spinner.
        configureKeywordSpinner();
    }

    /**
     * Handler for the record button. Processes the audio and uses Porcupine library to detect the
     * keyword. It increments a counter to indicate the occurrence of a keyword.
     * @param view ToggleButton used for recording audio.
     */
    public void process(View view) {
        try {
            if (recordButton.isChecked()) {
                // check if record permission was given.
                if (Utils.hasRecordPermission(this)) {
                    porcupineManager = initPorcupine();
                    porcupineManager.start();

                } else {
                    Utils.showRecordPermission(this);
                }
            } else {
                porcupineManager.stop();
            }
        } catch (PorcupineManagerException e) {
            Utils.showErrorToast(this);
        }
    }

    /**
     * Initialize the porcupineManager library.
     * @return Porcupine instance.
     */
    private PorcupineManager initPorcupine() throws PorcupineManagerException {
        Spinner mySpinner= findViewById(R.id.keyword_spinner);
        String kwd = mySpinner.getSelectedItem().toString();
        // It is assumed that the file name is all lower-case and spaces are replaced with "_".
        String filename = kwd.toLowerCase().replaceAll("\\s+", "_");
        // get the keyword file and model parameter file from internal storage.
        String keywordFilePath = new File(this.getFilesDir(), filename + ".ppn")
                .getAbsolutePath();
        String modelFilePath = new File(this.getFilesDir(), "params.pv").getAbsolutePath();
        final int detectedBackgroundColor = getResources()
                .getColor(R.color.colorAccent);
        return new PorcupineManager(modelFilePath, keywordFilePath, 0.5f, new KeywordCallback() {
            @Override
            public void run(int keyword_index) {
                startRecording();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!notificationPlayer.isPlaying()) {
                            notificationPlayer.start();
                        }
                        // change the background color for 3 second.
                        layout.setBackgroundColor(detectedBackgroundColor);
                        new CountDownTimer(3000, 100) {

                            @Override
                            public void onTick(long millisUntilFinished) {
                                if (!notificationPlayer.isPlaying()) {
                                    notificationPlayer.start();
                                }
                            }

                            @Override
                            public void onFinish() {
                                layout.setBackgroundColor(Color.TRANSPARENT);
                                stopRecording();
                            }
                        }.start();
                    }
                });
            }
        });
    }

    /**
     * Check the result of the record permission request.
     * @param requestCode request code of the permission request.
     * @param permissions requested permissions.
     * @param grantResults results of the permission requests.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[],
                                           @NonNull int[] grantResults) {
        // We only ask for record permission.
        if (grantResults.length == 0 || grantResults[0] == PackageManager.PERMISSION_DENIED) {
            ToggleButton tbtn = findViewById(R.id.record_button);
            tbtn.toggle();
        } else {
            try {
                porcupineManager = initPorcupine();
                porcupineManager.start();
            } catch (PorcupineManagerException e) {
                Utils.showErrorToast(this);
            }
        }
    }

    /**
     * Configure the style and behaviour of the keyword spinner.
     */
    private void configureKeywordSpinner(){
        Spinner spinner = findViewById(R.id.keyword_spinner);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this, R.array.keywords, R.layout.keyword_spinner_item);
        adapter.setDropDownViewResource(R.layout.keyword_spinner_item);
        spinner.setAdapter(adapter);

        // Make sure user stopped recording before changing the keyword.
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parentView, View selectedItemView,
                                       int position, long id) {
                if (recordButton.isChecked()) {
                    if (porcupineManager != null) {
                        try {
                            porcupineManager.stop();
                        } catch (PorcupineManagerException e) {
                            Utils.showErrorToast(getApplicationContext());
                        }
                    }
                    recordButton.toggle();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parentView) {
                // Do nothing.
            }
        });
    }

    private void startRecording() {
        recorder = new AudioRecord(MediaRecorder.AudioSource.DEFAULT, SAMPLING_RATE_IN_HZ,
                CHANNEL_CONFIG, AUDIO_FORMAT, BUFFER_SIZE);

        recorder.startRecording();

        recordingInProgress.set(true);

        recordingThread = new Thread(new RecordingRunnable(), "Recording Thread");
        recordingThread.start();
    }

    private void stopRecording() {
        if (null == recorder) {
            return;
        }

        recordingInProgress.set(false);
        recorder.stop();
        recorder.release();
        recorder = null;
        recordingThread = null;
    }

    private class RecordingRunnable implements Runnable {

        @Override
        public void run() {
            final File file = new File(Environment.getExternalStorageDirectory(), "recording.wav");
            final ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
            HttpURLConnection urlConnection = null;
            try (final ByteArrayOutputStream outStream = new ByteArrayOutputStream()) {
                writeWavHeader(outStream, CHANNEL_CONFIG, SAMPLING_RATE_IN_HZ, AUDIO_FORMAT);
                while (recordingInProgress.get()) {
                    int result = recorder.read(buffer, BUFFER_SIZE);
                    if (result < 0) {
                        throw new RuntimeException("Reading of audio buffer failed: " +
                                getBufferReadFailureReason(result));
                    }
                    outStream.write(buffer.array(), 0, BUFFER_SIZE);
                    buffer.clear();
                }
                byte[] wav = outStream.toByteArray();
                int len = wav.length;
                ByteBuffer buf = ByteBuffer.wrap(wav);
                buf.position(4);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                buf.putInt((int) len-8);
                buf.position(40);
                buf.putInt((int) len-44);
                wav = buf.array();
                String wav1 = Base64.getEncoder().encodeToString(wav);
                Log.w("MainActivity", wav1);

                URL url = new URL("http://192.168.1.114:8000");
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("POST");
                OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());

                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, "UTF-8"));
                writer.write(wav1);
                writer.flush();
                writer.close();
                out.close();

                urlConnection.connect();
                urlConnection.getResponseCode();
                urlConnection.disconnect();
            } catch (IOException e) {
                throw new RuntimeException("Writing of recorded audio failed", e);
            }
//          finally {
//                if (recorder != null) {
//                    try {
//                        if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
//                            recorder.stop();
//                        }
//                    } catch (IllegalStateException ex) {
//                        ///
//                    }
//                    if (recorder.getState() == recorder.STATE_INITIALIZED) {
//                        recorder.release();
//                    }
//                }
//            }
        }

        private String getBufferReadFailureReason(int errorCode) {
            switch (errorCode) {
                case AudioRecord.ERROR_INVALID_OPERATION:
                    return "ERROR_INVALID_OPERATION";
                case AudioRecord.ERROR_BAD_VALUE:
                    return "ERROR_BAD_VALUE";
                case AudioRecord.ERROR_DEAD_OBJECT:
                    return "ERROR_DEAD_OBJECT";
                case AudioRecord.ERROR:
                    return "ERROR";
                default:
                    return "Unknown (" + errorCode + ")";
            }
        }
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out         The stream to write the header to
     * @param channelMask An AudioFormat.CHANNEL_* mask
     * @param sampleRate  The sample rate in hertz
     * @param encoding    An AudioFormat.ENCODING_PCM_* value
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, int channelMask, int sampleRate, int encoding) throws IOException {
        short channels;
        switch (channelMask) {
            case AudioFormat.CHANNEL_IN_MONO:
                channels = 1;
                break;
            case AudioFormat.CHANNEL_IN_STEREO:
                channels = 2;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable channel mask");
        }

        short bitDepth;
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT:
                bitDepth = 8;
                break;
            case AudioFormat.ENCODING_PCM_16BIT:
                bitDepth = 16;
                break;
            case AudioFormat.ENCODING_PCM_FLOAT:
                bitDepth = 32;
                break;
            default:
                throw new IllegalArgumentException("Unacceptable encoding");
        }

        writeWavHeader(out, channels, sampleRate, bitDepth);
    }

    /**
     * Writes the proper 44-byte RIFF/WAVE header to/for the given stream
     * Two size fields are left empty/null since we do not yet know the final stream size
     *
     * @param out        The stream to write the header to
     * @param channels   The number of channels
     * @param sampleRate The sample rate in hertz
     * @param bitDepth   The bit depth
     * @throws IOException
     */
    private static void writeWavHeader(OutputStream out, short channels, int sampleRate, short bitDepth) throws IOException {
        // Convert the multi-byte integers to raw bytes in little endian format as required by the spec
        byte[] littleBytes = ByteBuffer
                .allocate(14)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(channels)
                .putInt(sampleRate)
                .putInt(sampleRate * channels * (bitDepth / 8))
                .putShort((short) (channels * (bitDepth / 8)))
                .putShort(bitDepth)
                .array();

        // Not necessarily the best, but it's very easy to visualize this way
        out.write(new byte[]{
                // RIFF header
                'R', 'I', 'F', 'F', // ChunkID
                0, 0, 0, 0, // ChunkSize (must be updated later)
                'W', 'A', 'V', 'E', // Format
                // fmt subchunk
                'f', 'm', 't', ' ', // Subchunk1ID
                16, 0, 0, 0, // Subchunk1Size
                1, 0, // AudioFormat
                littleBytes[0], littleBytes[1], // NumChannels
                littleBytes[2], littleBytes[3], littleBytes[4], littleBytes[5], // SampleRate
                littleBytes[6], littleBytes[7], littleBytes[8], littleBytes[9], // ByteRate
                littleBytes[10], littleBytes[11], // BlockAlign
                littleBytes[12], littleBytes[13], // BitsPerSample
                // data subchunk
                'd', 'a', 't', 'a', // Subchunk2ID
                0, 0, 0, 0, // Subchunk2Size (must be updated later)
        });
    }
}
