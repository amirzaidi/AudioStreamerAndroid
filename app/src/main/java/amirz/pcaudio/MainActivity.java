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

    private TextView serverStatus;
    public static final int SERVERPORT = 1420;

    private Handler handler = new Handler();

    private ServerSocket serverSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        serverStatus = findViewById(R.id.server_status);

        createEngine();
        createBufferQueueAudioPlayer();

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
                        serverStatus.setText("Waiting on 1420");
                    }
                });
                while (true) {
                    Socket client = serverSocket.accept();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            serverStatus.setText("Connected");
                        }
                    });

                    client.setTcpNoDelay(true);

                    try {
                        InputStream in = client.getInputStream();

                        AudioManager myAudioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        int bufSize = Integer.parseInt(myAudioMgr.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)) * 2;
                        Log.d("BufSize", bufSize + "");

                        float[] floats = new float[bufSize];
                        byte[] bytes = new byte[bufSize * 4];
                        int i;
                        setTopPriority();
                        while((i = in.read(bytes, 0, bytes.length)) != -1) {
                            ByteBuffer.wrap(bytes).asFloatBuffer().get(floats);
                            if (i != 0)
                                playAudio(floats, i);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            serverStatus.setText("Disconnected");
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
        shutdown();
    }

    /** Native methods, implemented in jni folder */
    public static native void createEngine();
    public static native void createBufferQueueAudioPlayer();
    public static native void setTopPriority();
    public static native void playAudio(float[] data, int count);
    public static native void shutdown();

    /** Load jni .so on initialization */
    static {
        System.loadLibrary("native-audio-jni");
    }
}
