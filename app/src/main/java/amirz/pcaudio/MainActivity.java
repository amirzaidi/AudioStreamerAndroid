package amirz.pcaudio;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.AlphaAnimation;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

import static java.lang.Thread.MAX_PRIORITY;

public class MainActivity extends Activity {

    private TextView status;
    private SeekBar buffers;
    private int bufCount;
    public static final int SERVERPORT = 1420;

    private Handler handler = new Handler();

    private ServerSocket serverSocket;
    private int framesPerBuffer;

    private AlphaAnimation hide;
    private AlphaAnimation show;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.server_status);
        buffers = findViewById(R.id.buffer_count);

        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        framesPerBuffer = Integer.parseInt(audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)) * 2;

        buffers.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                bufCount = ((Double)Math.pow(2, i + 1)).intValue();
                status.setText(bufCount + " buffers (" + (1d / 48 * framesPerBuffer * bufCount) + " ms)");
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

        hide = new AlphaAnimation(1f, 0f);
        hide.setDuration(200);
        hide.setFillAfter(true);

        show = new AlphaAnimation(0f, 1f);
        show.setDuration(200);
        show.setFillAfter(true);

        Thread fst = new Thread(new ServerThread());
        fst.setPriority(MAX_PRIORITY);
        fst.start();
    }

    public class ServerThread implements Runnable {
        public void run() {
            try {
                serverSocket = new ServerSocket(SERVERPORT);
                while (true) {
                    Socket client = serverSocket.accept();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            buffers.setEnabled(false);
                            status.startAnimation(hide);
                            buffers.startAnimation(hide);
                        }
                    });

                    client.setTcpNoDelay(true);
                    start(bufCount, framesPerBuffer * 4);
                    try {
                        InputStream in = client.getInputStream();

                        float[] floats = new float[framesPerBuffer];
                        byte[] bytes = new byte[framesPerBuffer * 4];
                        int i = 0;

                        while (true) {
                            int newRead = in.read(bytes, i, bytes.length - i);
                            if (newRead == -1) {
                                break;
                            } else if (newRead != 0) {
                                i += newRead;
                                if (i == bytes.length) {
                                    ByteBuffer.wrap(bytes).asFloatBuffer().get(floats);
                                    playAudio(floats, framesPerBuffer * 4);
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
                            status.startAnimation(show);
                            buffers.startAnimation(show);
                            buffers.setEnabled(true);
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
            serverSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static native void start(int bufCount, int bufSize);
    public static native void playAudio(float[] data, int count);
    public static native void shutdown();

    static {
        System.loadLibrary("native-audio-jni");
    }
}
