package com.dinh.robotcarai;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioBridgeClient {
    interface Listener {
        void onStatus(String text, boolean connected);
    }

    private static final int MAGIC = 0x52434149; // "RCAI"
    private static final int VERSION = 1;
    private static final int TYPE_HELLO = 1;
    private static final int TYPE_MICROPHONE_PCM = 2;
    private static final int TYPE_SPEAKER_PCM = 3;
    private static final int TYPE_CONTROL = 4;
    private static final int MAX_PAYLOAD = 4096;
    private static final int TCP_PORT = 8765;

    static final int COMMAND_TOGGLE_CONVERSATION = 1;
    static final int COMMAND_START_LISTENING = 2;
    static final int COMMAND_STOP_LISTENING = 3;

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Socket socket;
    private OutputStream output;
    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    AudioBridgeClient(Listener listener) {
        this.listener = listener;
    }

    boolean isConnected() {
        return running.get() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    void connect(String host) {
        if (running.get()) {
            return;
        }
        new Thread(() -> connectBlocking(host), "robot-connect").start();
    }

    private void connectBlocking(String host) {
        notifyStatus("Đang kết nối " + host + "...", false);
        try {
            Socket newSocket = new Socket();
            newSocket.connect(new InetSocketAddress(host, TCP_PORT), 5000);
            newSocket.setTcpNoDelay(true);
            newSocket.setKeepAlive(true);
            socket = newSocket;
            output = newSocket.getOutputStream();
            running.set(true);
            notifyStatus("Đã kết nối " + host, true);

            Thread microphoneThread = new Thread(this::microphoneLoop, "robot-microphone");
            microphoneThread.start();
            receiveLoop(newSocket);
        } catch (Exception exception) {
            notifyStatus("Kết nối lỗi: " + exception.getMessage(), false);
        } finally {
            disconnect();
        }
    }

    @SuppressLint("MissingPermission")
    private void microphoneLoop() {
        final int sampleRate = 16000;
        int minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            notifyStatus("Điện thoại không hỗ trợ thu âm 16 kHz", false);
            disconnect();
            return;
        }

        AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minimum * 2, sampleRate));
        audioRecord = recorder;
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            notifyStatus("Không khởi tạo được microphone", false);
            disconnect();
            return;
        }

        AcousticEchoCanceler echoCanceler = null;
        NoiseSuppressor noiseSuppressor = null;
        AutomaticGainControl gainControl = null;
        int sessionId = recorder.getAudioSessionId();
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId);
            if (echoCanceler != null) echoCanceler.setEnabled(true);
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId);
            if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = AutomaticGainControl.create(sessionId);
            if (gainControl != null) gainControl.setEnabled(true);
        }

        short[] samples = new short[160]; // 10 ms at 16 kHz
        byte[] pcm = new byte[samples.length * 2];
        try {
            recorder.startRecording();
            while (running.get()) {
                int filled = 0;
                while (filled < samples.length && running.get()) {
                    int count = recorder.read(samples, filled, samples.length - filled,
                            AudioRecord.READ_BLOCKING);
                    if (count < 0) {
                        throw new IOException("AudioRecord error " + count);
                    }
                    filled += count;
                }
                ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples);
                sendFrame(TYPE_MICROPHONE_PCM, pcm);
            }
        } catch (Exception exception) {
            if (running.get()) {
                notifyStatus("Lỗi microphone: " + exception.getMessage(), false);
                disconnect();
            }
        } finally {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
            recorder.release();
            if (echoCanceler != null) echoCanceler.release();
            if (noiseSuppressor != null) noiseSuppressor.release();
            if (gainControl != null) gainControl.release();
            audioRecord = null;
        }
    }

    private void receiveLoop(Socket activeSocket) throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(activeSocket.getInputStream()));
        while (running.get()) {
            int magic = input.readInt();
            int version = input.readUnsignedByte();
            int type = input.readUnsignedByte();
            input.readUnsignedShort(); // flags, reserved
            int size = input.readInt();
            if (magic != MAGIC || version != VERSION || size < 0 || size > MAX_PAYLOAD) {
                throw new IOException("Khung dữ liệu không hợp lệ");
            }
            byte[] payload = new byte[size];
            input.readFully(payload);
            if (type == TYPE_SPEAKER_PCM) {
                playPcm(payload);
            } else if (type == TYPE_HELLO) {
                notifyStatus("ESP32 sẵn sàng - mic 16 kHz / loa 24 kHz", true);
            }
        }
    }

    private void playPcm(byte[] pcm) throws IOException {
        if ((pcm.length & 1) != 0) {
            throw new IOException("PCM loa có số byte lẻ");
        }
        AudioTrack player = ensureAudioTrack();
        short[] samples = new short[pcm.length / 2];
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        int offset = 0;
        while (offset < samples.length && running.get()) {
            int written = player.write(samples, offset, samples.length - offset,
                    AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IOException("AudioTrack error " + written);
            }
            offset += written;
        }
    }

    private AudioTrack ensureAudioTrack() throws IOException {
        if (audioTrack != null) {
            return audioTrack;
        }
        final int sampleRate = 24000;
        int minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            throw new IOException("Điện thoại không hỗ trợ phát âm 24 kHz");
        }
        AudioTrack player = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(minimum * 2, sampleRate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (player.getState() != AudioTrack.STATE_INITIALIZED) {
            player.release();
            throw new IOException("Không khởi tạo được loa");
        }
        player.play();
        audioTrack = player;
        return player;
    }

    void sendControl(int command) {
        if (!running.get()) {
            notifyStatus("Chưa kết nối ESP32", false);
            return;
        }
        new Thread(() -> {
            try {
                sendFrame(TYPE_CONTROL, new byte[]{(byte) command});
            } catch (IOException exception) {
                notifyStatus("Gửi lệnh lỗi: " + exception.getMessage(), false);
                disconnect();
            }
        }, "robot-control").start();
    }

    private synchronized void sendFrame(int type, byte[] payload) throws IOException {
        if (!running.get() || output == null) {
            throw new IOException("Mất kết nối");
        }
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC);
        header.put((byte) VERSION);
        header.put((byte) type);
        header.putShort((short) 0);
        header.putInt(payload.length);
        output.write(header.array());
        output.write(payload);
    }

    void disconnect() {
        if (!running.getAndSet(false) && socket == null) {
            return;
        }
        Socket oldSocket = socket;
        socket = null;
        output = null;
        if (oldSocket != null) {
            try {
                oldSocket.close();
            } catch (IOException ignored) {
            }
        }
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (Exception ignored) {
            }
        }
        AudioTrack player = audioTrack;
        audioTrack = null;
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            player.release();
        }
        notifyStatus("Đã ngắt kết nối", false);
    }

    private void notifyStatus(String text, boolean connected) {
        listener.onStatus(text, connected);
    }
}
