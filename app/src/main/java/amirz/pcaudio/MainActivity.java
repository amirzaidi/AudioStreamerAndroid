package amirz.pcaudio;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.AlphaAnimation;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

import static java.lang.Thread.MAX_PRIORITY;

public class MainActivity extends Activity {

    private TextView status;
    private SeekBar buffers;
    private int bufCount;

    private Handler handler = new Handler();

    private ServerSocket serverSocket;
    private int sampleRate;
    private int framesPerBuffer;

    private AlphaAnimation hide;
    private AlphaAnimation show;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        sampleRate = Integer.parseInt(audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
        framesPerBuffer = Integer.parseInt(audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)) * 2; //Stereo

        setContentView(R.layout.activity_main);

        hide = new AlphaAnimation(1f, 0f);
        hide.setDuration(200);
        hide.setFillAfter(true);

        show = new AlphaAnimation(0f, 1f);
        show.setDuration(200);
        show.setFillAfter(true);

        status = findViewById(R.id.server_status);
        buffers = findViewById(R.id.buffer_count);
        buffers.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                bufCount = ((Double)Math.pow(2, i + 1)).intValue();
                String text = "Buffers: " + bufCount + " (" + (1000d / sampleRate * framesPerBuffer * bufCount) + " ms)\nSample rate: " + sampleRate + " Hz";

                WifiManager wifiMgr = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifiMgr.isWifiEnabled()) {
                    int ip = wifiMgr.getConnectionInfo().getIpAddress();
                    text += "\nWiFi IP: " + String.format("%d.%d.%d.%d",
                            (ip & 0xff),
                            (ip >> 8 & 0xff),
                            (ip >> 16 & 0xff),
                            (ip >> 24 & 0xff));
                }

                status.setText(text);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        buffers.setMax(6);
        buffers.setProgress(3);

        Thread fst = new Thread(new ServerThread());
        fst.setPriority(MAX_PRIORITY);
        fst.start();
    }

    private void changeState(boolean stateShow) {
        buffers.setEnabled(stateShow);
        status.startAnimation(stateShow ? show : hide);
        buffers.startAnimation(stateShow ? show : hide);
    }

    public class ServerThread implements Runnable {
        public void run() {
            try {
                int bytesPerBuffer = framesPerBuffer * 4; //4 bytes per 32-bit float frame
                serverSocket = new ServerSocket(1420);
                while (!serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            changeState(false);
                        }
                    });

                    client.setTcpNoDelay(true);
                    start(sampleRate, bufCount, bytesPerBuffer);
                    try {
                        InputStream in = client.getInputStream();
                        OutputStream out = client.getOutputStream();
                        out.write(1);
                        out.flush();

                        float[] floats = new float[framesPerBuffer];
                        byte[] bytes = new byte[bytesPerBuffer];
                        int i = 0;

                        while (true) {
                            int newRead = in.read(bytes, i, bytes.length - i);
                            if (newRead == -1) { //Disconnected
                                break;
                            } else if (newRead != 0) {
                                i += newRead;
                                if (i == bytes.length) { //Byte array full, play and continue
                                    ByteBuffer.wrap(bytes).asFloatBuffer().get(floats); //Convert to float, little-endian
                                    playAudio(floats, bytesPerBuffer);
                                    i = 0;
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    shutdown();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            changeState(true);
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            serverSocket.close(); //Stop background thread
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static native void start(int sampleRate, int bufCount, int bufSize);
    public static native void playAudio(float[] data, int count);
    public static native void shutdown();

    static {
        System.loadLibrary("native-audio-jni");
    }
}
