package amirz.pcaudio;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

import static java.lang.Thread.MAX_PRIORITY;

public class MainActivity extends Activity {

    private TextView status;
    public static final int SERVERPORT = 1420;

    private Handler handler = new Handler();

    private ServerSocket serverSocket;
    private int framesPerBuffer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.server_status);

        Thread fst = new Thread(new ServerThread());
        fst.setPriority(MAX_PRIORITY);
        fst.start();
    }

    public class ServerThread implements Runnable {
        public void run() {
            try {
                serverSocket = new ServerSocket(SERVERPORT);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        status.setText("Waiting for first connection..");
                    }
                });
                while (true) {
                    Socket client = serverSocket.accept();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("");
                        }
                    });

                    client.setTcpNoDelay(true);

                    AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    framesPerBuffer = Integer.parseInt(audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)) * 2;
                    Log.d("framesPerBuffer", framesPerBuffer + "");

                    start(32, framesPerBuffer * 4);

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
                            status.setText("Disconnected");
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

    /** Native methods, implemented in jni folder */
    public static native void start(int bufCount, int bufSize);
    public static native void playAudio(float[] data, int count);
    public static native void shutdown();

    /** Load jni .so on initialization */
    static {
        System.loadLibrary("native-audio-jni");
    }
}
