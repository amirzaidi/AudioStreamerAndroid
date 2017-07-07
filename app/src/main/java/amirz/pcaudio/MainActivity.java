package amirz.pcaudio;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

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

        Thread fst = new Thread(new ServerThread());
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
                        int sampleRate = 192000;
                        int channels = AudioFormat.CHANNEL_OUT_STEREO;
                        int format = AudioFormat.ENCODING_PCM_FLOAT;

                        AudioTrack audioTrack = new AudioTrack.Builder()
                                .setAudioAttributes(new AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build())
                                .setAudioFormat(new AudioFormat.Builder()
                                        .setSampleRate(sampleRate)
                                        .setEncoding(format)
                                        .build())
                                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(sampleRate, channels, format))
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                                .build();

                        InputStream in = client.getInputStream();

                        int buffSize = 512;
                        byte[] bytes = new byte[buffSize];
                        float[] f = new float[buffSize / 4];
                        int i;

                        audioTrack.play();
                        while((i = in.read(bytes, 0, buffSize)) != -1) {
                            ByteBuffer.wrap(bytes).asFloatBuffer().get(f);
                            for (int j = 0; j < f.length; j++) {
                                if (f[j] > 1 || f[j] < -1) {
                                    f[j] = 0;
                                }
                            }
                            audioTrack.write(f, 0, i / 4, AudioTrack.WRITE_BLOCKING);
                        }
                        audioTrack.stop();
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
    }
}
