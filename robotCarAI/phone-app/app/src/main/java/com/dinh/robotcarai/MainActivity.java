package com.dinh.robotcarai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity implements AudioBridgeClient.Listener {
    private static final int MICROPHONE_PERMISSION_REQUEST = 10;
    private static final int DISCOVERY_PORT = 8764;
    private static final String DISCOVERY_REQUEST = "ROBOTCARAI_DISCOVER";

    private AudioBridgeClient client;
    private TextView statusView;
    private EditText hostView;
    private Button connectButton;
    private Button talkButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        client = new AudioBridgeClient(this);
        buildInterface();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST);
        }
    }

    private void buildInterface() {
        int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(245, 248, 252));

        TextView title = new TextView(this);
        title.setText("Robot Car AI");
        title.setTextSize(30);
        title.setTextColor(Color.rgb(13, 71, 161));
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        TextView explanation = new TextView(this);
        explanation.setText("Điện thoại là microphone và loa cho ESP32-S3");
        explanation.setTextSize(16);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(0, padding / 2, 0, padding);
        root.addView(explanation, matchWrap());

        statusView = new TextView(this);
        statusView.setText("Chưa kết nối");
        statusView.setTextSize(17);
        statusView.setGravity(Gravity.CENTER);
        root.addView(statusView, matchWrap());

        hostView = new EditText(this);
        hostView.setHint("IP của ESP32, ví dụ 192.168.1.50");
        hostView.setSingleLine(true);
        hostView.setText(getPreferences(MODE_PRIVATE).getString("last_host", ""));
        hostView.setPadding(padding / 2, padding, padding / 2, padding / 2);
        root.addView(hostView, matchWrap());

        Button discoverButton = new Button(this);
        discoverButton.setText("Tự tìm ESP32 trong Wi-Fi");
        discoverButton.setOnClickListener(view -> discoverRobot());
        root.addView(discoverButton, matchWrap());

        connectButton = new Button(this);
        connectButton.setText("Kết nối");
        connectButton.setOnClickListener(view -> toggleConnection());
        root.addView(connectButton, matchWrap());

        talkButton = new Button(this);
        talkButton.setText("Bắt đầu / dừng hội thoại");
        talkButton.setTextSize(18);
        talkButton.setEnabled(false);
        talkButton.setMinHeight(Math.round(80 * getResources().getDisplayMetrics().density));
        talkButton.setOnClickListener(view ->
                client.sendControl(AudioBridgeClient.COMMAND_TOGGLE_CONVERSATION));
        root.addView(talkButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = new TextView(this);
        hint.setText("Giữ màn hình ứng dụng mở. Điện thoại và ESP32 phải cùng Wi-Fi. " +
                "Bạn cũng có thể nhấn nút BOOT trên mạch để bật/tắt hội thoại.");
        hint.setTextSize(14);
        hint.setPadding(0, padding, 0, 0);
        root.addView(hint, matchWrap());

        setContentView(root);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void toggleConnection() {
        if (client.isConnected()) {
            client.disconnect();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST);
            return;
        }
        String host = hostView.getText().toString().trim();
        if (host.isEmpty()) {
            Toast.makeText(this, "Nhập IP hoặc bấm Tự tìm", Toast.LENGTH_SHORT).show();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("last_host", host).apply();
        client.connect(host);
    }

    private void discoverRobot() {
        statusView.setText("Đang tìm ESP32...");
        new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                socket.setSoTimeout(3000);
                byte[] request = DISCOVERY_REQUEST.getBytes(StandardCharsets.US_ASCII);
                DatagramPacket outgoing = new DatagramPacket(request, request.length,
                        InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                socket.send(outgoing);

                byte[] response = new byte[64];
                DatagramPacket incoming = new DatagramPacket(response, response.length);
                socket.receive(incoming);
                String text = new String(incoming.getData(), 0, incoming.getLength(),
                        StandardCharsets.US_ASCII);
                if (!"ROBOTCARAI/1".equals(text)) {
                    throw new IllegalStateException("Phản hồi không đúng");
                }
                String host = incoming.getAddress().getHostAddress();
                runOnUiThread(() -> {
                    hostView.setText(host);
                    statusView.setText("Đã tìm thấy ESP32: " + host);
                });
            } catch (Exception exception) {
                runOnUiThread(() -> statusView.setText(
                        "Không tìm thấy ESP32. Hãy nhập IP thủ công."));
            }
        }, "robot-discovery").start();
    }

    @Override
    public void onStatus(String text, boolean connected) {
        runOnUiThread(() -> {
            statusView.setText(text);
            connectButton.setText(connected ? "Ngắt kết nối" : "Kết nối");
            talkButton.setEnabled(connected);
        });
    }

    @Override
    protected void onDestroy() {
        client.disconnect();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST &&
                (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            statusView.setText("Cần quyền microphone để hoạt động");
        }
    }
}
