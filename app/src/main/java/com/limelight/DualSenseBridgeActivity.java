package com.limelight;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.usbbtonandroid.DualSenseInput;
import com.example.usbbtonandroid.hci.HciUsbController;
import com.limelight.dualsense.DualSenseBridge;
import com.limelight.dualsense.DualSenseAudioBridge;
import com.limelight.dualsense.DualSenseBluetoothMicrophoneTest;
import com.limelight.binding.input.driver.DualSenseController;
import com.limelight.utils.UiHelper;

import java.util.List;

/** Bluetooth-settings style UI for the process-wide experimental HCI bridge. */
public class DualSenseBridgeActivity extends AppCompatActivity {
    private static final int BG = 0xFF0F141A;
    private static final int CARD = 0xFF1B222B;
    private static final int CARD_BORDER = 0xFF35404C;
    private static final int PRIMARY = 0xFF4D9DFF;
    private static final int TEXT_SECONDARY = 0xFFADB8C5;
    private static final int CONNECTED = 0xFF62D995;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout deviceContainer;
    private LinearLayout diagnosticsPanel;
    private LinearLayout toolsPanel;
    private LinearLayout microphoneTestPanel;
    private TextView statusTitle;
    private TextView statusDetails;
    private TextView batteryView;
    private TextView inputView;
    private TextView logView;
    private ScrollView pageScroll;
    private ScrollView logScroll;
    private TextView microphoneTestStatus;
    private Button microphoneRecordButton;
    private Button microphonePlaybackButton;
    private Button microphoneMonitorButton;
    private int red;
    private int green = 80;
    private int blue = 255;
    private String lastDeviceSignature = "";
    private final Runnable refreshRunnable = this::refresh;

    private final DualSenseBridge.StateListener stateListener =
            () -> {
                mainHandler.removeCallbacks(refreshRunnable);
                mainHandler.postDelayed(refreshRunnable, 250);
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiHelper.setLocale(this);
        setTitle(R.string.dualsense_bridge_title);
        setContentView(buildContent());
        DualSenseBridge.initialize(this);
        DualSenseBridge.addStateListener(stateListener);
        refresh();
    }

    @Override
    protected void onDestroy() {
        DualSenseBridge.removeStateListener(stateListener);
        mainHandler.removeCallbacks(refreshRunnable);
        DualSenseBluetoothMicrophoneTest.stop();
        super.onDestroy();
    }

    private View buildContent() {
        pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(18), dp(20), dp(32));
        root.setBackgroundColor(BG);
        pageScroll.addView(root);

        LinearLayout header = horizontal();
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = vertical();
        TextView title = text(getString(R.string.dualsense_bridge_title), 25, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.addView(title);
        heading.addView(text(getString(R.string.dualsense_bridge_subtitle), 13, TEXT_SECONDARY));
        header.addView(heading, weighted());
        header.addView(iconButton(android.R.drawable.ic_menu_manage,
                getString(R.string.dualsense_bridge_tools), v -> togglePanel(toolsPanel)));
        header.addView(iconButton(android.R.drawable.ic_menu_info_details,
                getString(R.string.dualsense_bridge_diagnostics), v -> {
                    togglePanel(diagnosticsPanel);
                    if (diagnosticsPanel.getVisibility() == View.VISIBLE) {
                        logView.setText("DualSense audio / HD haptics\n" +
                                DualSenseAudioBridge.diagnostics() + "\n\n" +
                                DualSenseBridge.getLog());
                    }
                }));
        root.addView(header, match(dp(8)));

        TextView beta = pill("BETA");
        root.addView(beta, wrapStart());

        LinearLayout connection = card();
        LinearLayout stateRow = horizontal();
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView controllerIcon = text("🎮", 30, Color.WHITE);
        controllerIcon.setGravity(Gravity.CENTER);
        stateRow.addView(controllerIcon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout stateTexts = vertical();
        statusTitle = text(getString(R.string.dualsense_bridge_not_connected), 18, Color.WHITE);
        statusTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        statusDetails = text("", 13, TEXT_SECONDARY);
        statusDetails.setPadding(0, dp(3), 0, 0);
        stateTexts.addView(statusTitle);
        stateTexts.addView(statusDetails);
        stateRow.addView(stateTexts, weighted());
        batteryView = pill("--%");
        stateRow.addView(batteryView);
        connection.addView(stateRow);

        LinearLayout batteryLedRow = horizontal();
        batteryLedRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout batteryLedText = vertical();
        TextView batteryLedTitle = text(getString(R.string.dualsense_bridge_battery_led),
                15, Color.WHITE);
        batteryLedTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        batteryLedText.addView(batteryLedTitle);
        batteryLedText.addView(text(getString(R.string.dualsense_bridge_battery_led_summary),
                12, TEXT_SECONDARY));
        batteryLedRow.addView(batteryLedText, weighted());
        Switch batteryLedSwitch = new Switch(this);
        batteryLedSwitch.setContentDescription(getString(R.string.dualsense_bridge_battery_led));
        batteryLedSwitch.setChecked(DualSenseBridge.isBatteryLedEnabled(this));
        batteryLedSwitch.setOnCheckedChangeListener((button, checked) ->
                DualSenseBridge.setBatteryLedEnabled(checked));
        batteryLedRow.addView(batteryLedSwitch);
        batteryLedRow.setPadding(0, dp(12), 0, dp(2));
        connection.addView(batteryLedRow);

        LinearLayout lowBatteryRow = horizontal();
        lowBatteryRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout lowBatteryText = vertical();
        TextView lowBatteryTitle = text(getString(R.string.dualsense_bridge_low_battery_blink),
                15, Color.WHITE);
        lowBatteryTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        lowBatteryText.addView(lowBatteryTitle);
        lowBatteryText.addView(text(getString(R.string.dualsense_bridge_low_battery_blink_summary),
                12, TEXT_SECONDARY));
        lowBatteryRow.addView(lowBatteryText, weighted());
        Switch lowBatterySwitch = new Switch(this);
        lowBatterySwitch.setContentDescription(
                getString(R.string.dualsense_bridge_low_battery_blink));
        lowBatterySwitch.setChecked(DualSenseBridge.isLowBatteryBlinkEnabled(this));
        lowBatterySwitch.setOnCheckedChangeListener((button, checked) ->
                DualSenseBridge.setLowBatteryBlinkEnabled(checked));
        lowBatteryRow.addView(lowBatterySwitch);
        lowBatteryRow.setPadding(0, dp(12), 0, dp(2));
        connection.addView(lowBatteryRow);

        String[] hciProfiles = {
                "Auto (recommended)",
                "Profile 1 · Generic HCI",
                "Profile 2 · CSR HCI (0A12:0001)",
                "Profile 3 · Realtek HCI (0BDA:A760)"
        };
        Spinner hciProfile = spinner(hciProfiles);
        int requestedProfile = DualSenseBridge.requestedHciProfile();
        hciProfile.setSelection(requestedProfile >= 0 && requestedProfile < hciProfiles.length ?
                requestedProfile : DualSenseBridge.HCI_PROFILE_AUTO);
        final boolean[] bindingHciProfile = {true};
        hciProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                 int position, long id) {
                if (bindingHciProfile[0]) {
                    bindingHciProfile[0] = false;
                    return;
                }
                if (position != DualSenseBridge.requestedHciProfile()) {
                    DualSenseBridge.setHciProfile(DualSenseBridgeActivity.this, position);
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        connection.addView(labelled("Bluetooth adapter profile", hciProfile));
        TextView profileHint = text("Profiles 2 and 3 are isolated from the proven Generic HCI path. " +
                "Auto selects the matching profile only for its known adapter.", 12, TEXT_SECONDARY);
        profileHint.setPadding(0, dp(1), 0, dp(6));
        connection.addView(profileHint);

        Button scan = responsiveActionButton(getString(R.string.dualsense_bridge_scan), true,
                () -> DualSenseBridge.scan(this));
        scan.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        scan.setCompoundDrawablePadding(dp(9));
        connection.addView(scan, match(dp(14)));

        LinearLayout secondaryActions = horizontal();
        secondaryActions.addView(responsiveActionButton(
                getString(R.string.dualsense_bridge_reset_adapter), false,
                () -> DualSenseBridge.reset(this)), weightedWithMargin());
        secondaryActions.addView(responsiveActionButton(
                getString(R.string.dualsense_bridge_disconnect), false,
                DualSenseBridge::disconnectBridge), weightedWithMargin());
        connection.addView(secondaryActions);
        root.addView(connection, match(dp(12)));

        root.addView(sectionTitle(getString(R.string.dualsense_bridge_devices_title),
                getString(R.string.dualsense_bridge_devices_summary)));
        deviceContainer = vertical();
        root.addView(deviceContainer);

        LinearLayout microphoneTestLauncher = horizontal();
        microphoneTestLauncher.setGravity(Gravity.CENTER_VERTICAL);
        TextView microphoneTestHint = text("DualSense Bluetooth microphone diagnostic", 13,
                TEXT_SECONDARY);
        microphoneTestLauncher.addView(microphoneTestHint, weighted());
        microphoneTestLauncher.addView(iconButton(android.R.drawable.ic_btn_speak_now,
                "Open DualSense microphone test", v -> togglePanel(microphoneTestPanel)));
        root.addView(microphoneTestLauncher, match(dp(4)));

        microphoneTestPanel = buildMicrophoneTestPanel();
        microphoneTestPanel.setVisibility(View.GONE);
        root.addView(microphoneTestPanel, match(dp(8)));

        diagnosticsPanel = card();
        diagnosticsPanel.setVisibility(View.GONE);
        diagnosticsPanel.addView(panelHeader(getString(R.string.dualsense_bridge_diagnostics),
                getString(R.string.dualsense_bridge_diagnostics_summary), diagnosticsPanel));
        inputView = text("", 13, Color.WHITE);
        inputView.setPadding(0, dp(8), 0, dp(12));
        diagnosticsPanel.addView(inputView);
        logView = text("", 11, TEXT_SECONDARY);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setNestedScrollingEnabled(true);
        logScroll.addView(logView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        diagnosticsPanel.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        root.addView(diagnosticsPanel, match(dp(12)));

        toolsPanel = buildToolsPanel();
        toolsPanel.setVisibility(View.GONE);
        root.addView(toolsPanel, match(dp(12)));
        return pageScroll;
    }

    private LinearLayout buildMicrophoneTestPanel() {
        LinearLayout panel = card();
        panel.addView(panelHeader("Bluetooth microphone test",
                "Local recording only. It does not send audio to the host.", panel));
        TextView description = text("Records the raw DualSense Bluetooth microphone, " +
                "decodes it locally, then plays it through this phone's media output. " +
                "Live monitor bypasses recording and host forwarding.",
                13, TEXT_SECONDARY);
        description.setPadding(0, dp(8), 0, dp(5));
        panel.addView(description);

        microphoneTestStatus = text("Idle", 13, Color.WHITE);
        microphoneTestStatus.setPadding(0, dp(6), 0, dp(4));
        panel.addView(microphoneTestStatus);

        LinearLayout actions = horizontal();
        microphoneRecordButton = actionButton("Start recording", true, view -> {
            if (DualSenseBluetoothMicrophoneTest.isRecording()) {
                DualSenseBluetoothMicrophoneTest.stopRecording();
            } else {
                DualSenseBluetoothMicrophoneTest.startRecording();
            }
            refreshMicrophoneTest();
        });
        actions.addView(microphoneRecordButton, weightedWithMargin());
        microphonePlaybackButton = actionButton("Play on phone", false, view -> {
            DualSenseBluetoothMicrophoneTest.playRecording(this);
            refreshMicrophoneTest();
        });
        actions.addView(microphonePlaybackButton, weightedWithMargin());
        panel.addView(actions, match(dp(4)));
        microphoneMonitorButton = actionButton("Start live monitor", false, view -> {
            if (DualSenseBluetoothMicrophoneTest.isLiveMonitoring()) {
                DualSenseBluetoothMicrophoneTest.stopLiveMonitor();
            } else {
                DualSenseBluetoothMicrophoneTest.startLiveMonitor(this);
            }
            refreshMicrophoneTest();
        });
        panel.addView(microphoneMonitorButton, match(dp(4)));
        Button clear = actionButton("Clear recording", false, view -> {
            DualSenseBluetoothMicrophoneTest.clear();
            refreshMicrophoneTest();
        });
        panel.addView(clear, match(dp(4)));
        return panel;
    }

    private LinearLayout buildToolsPanel() {
        LinearLayout output = card();
        output.addView(panelHeader("Controller test tools",
                "LED, adaptive trigger and vibration tests", output));
        output.addView(colorSlider("Red", 0, value -> red = value));
        output.addView(colorSlider("Green", 80, value -> green = value));
        output.addView(colorSlider("Blue", 255, value -> blue = value));
        output.addView(actionButton("Apply light color", false, view ->
                DualSenseBridge.sendLed((byte) red, (byte) green, (byte) blue)), match(dp(8)));

        String[] modes = {"Off", "Resistance", "Vibration"};
        Spinner leftTrigger = spinner(modes);
        Spinner rightTrigger = spinner(modes);
        output.addView(labelled("Left adaptive trigger", leftTrigger));
        output.addView(labelled("Right adaptive trigger", rightTrigger));
        SeekBar strength = new SeekBar(this);
        strength.setMax(100);
        strength.setProgress(60);
        output.addView(labelled("Trigger strength", strength));
        output.addView(actionButton("Apply adaptive triggers", false, view ->
                DualSenseBridge.setAdaptiveTriggers(leftTrigger.getSelectedItemPosition(),
                        rightTrigger.getSelectedItemPosition(), strength.getProgress())), match(dp(8)));

        LinearLayout rumble = horizontal();
        rumble.addView(actionButton("Soft vibration", false,
                view -> testRumble(90, 30)), weightedWithMargin());
        rumble.addView(actionButton("Strong vibration", false,
                view -> testRumble(220, 150)), weightedWithMargin());
        output.addView(rumble, match(dp(8)));
        return output;
    }

    private void refresh() {
        if (statusTitle == null) return;
        refreshMicrophoneTest();
        if (DualSenseController.hasActiveController()) {
            long age = DualSenseController.getActiveInputAgeMs();
            int percent = DualSenseController.getActiveBatteryPercent();
            statusTitle.setText("DualSense connected via USB");
            statusTitle.setTextColor(CONNECTED);
            statusDetails.setText("Raw HID active · " +
                    (DualSenseController.getActiveHeadphonesConnected() ?
                            "headset jack" : "controller speaker") +
                    " · input age " + Math.max(0, age) + " ms\n" +
                    "Adapter profile: " + DualSenseBridge.activeHciProfileName());
            if (percent >= 0) {
                batteryView.setVisibility(View.VISIBLE);
                batteryView.setText(percent + "%");
            } else {
                batteryView.setVisibility(View.GONE);
            }
            inputView.setText("Transport: direct wired USB\nInput packets: " +
                    DualSenseController.getActiveInputPacketCount() +
                    "\nHID age: " + Math.max(0, age) + " ms" +
                    "\nRead errors: " + DualSenseController.getActiveInputReadErrors() +
                    "\nAudio/HD haptics: " + DualSenseAudioBridge.diagnostics());
        }
        boolean connected = DualSenseBridge.getControllerConnected();
        DualSenseInput input = DualSenseBridge.getLatestInput();
        if (!DualSenseController.hasActiveController()) {
            statusTitle.setText(connected ? R.string.dualsense_bridge_connected :
                    R.string.dualsense_bridge_not_connected);
            statusTitle.setTextColor(connected ? CONNECTED : Color.WHITE);
            statusDetails.setText(DualSenseBridge.getStatus() + "\nAdapter profile: " +
                    DualSenseBridge.activeHciProfileName());
            if (connected && input.getBatteryPercent() >= 0) {
                batteryView.setVisibility(View.VISIBLE);
                batteryView.setText(input.getBatteryPercent() + "%  " + input.getBatteryStatus());
            }
            else {
                batteryView.setVisibility(View.GONE);
            }

            inputView.setText("Input packets: " + DualSenseBridge.getInputPacketCount() +
                "\nSticks: " + input.getLeftX() + ", " + input.getLeftY() + "  |  " +
                input.getRightX() + ", " + input.getRightY() +
                "\nTriggers: " + input.getLeftTrigger() + " / " + input.getRightTrigger() +
                "\nButtons: " + input.getPressed() +
                "\nAudio/HD haptics: " + DualSenseAudioBridge.diagnostics());
        }
        List<HciUsbController.HciDevice> devices = DualSenseBridge.getDevices();
        StringBuilder signature = new StringBuilder();
        signature.append("connected=").append(connected).append(';');
        for (HciUsbController.HciDevice device : devices) {
            signature.append(device.getAddress()).append('|')
                    .append(device.getPaired()).append('|')
                    .append(device.getState()).append('|')
                    .append(device.getRssi()).append('|')
                    .append(device.getName()).append(';');
        }
        if (signature.toString().equals(lastDeviceSignature)) {
            scheduleMicrophoneTestRefresh();
            return;
        }
        lastDeviceSignature = signature.toString();
        deviceContainer.removeAllViews();
        if (devices.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text(getString(R.string.dualsense_bridge_no_controllers), 16, Color.WHITE));
            TextView hint = text(getString(R.string.dualsense_bridge_pairing_hint),
                    13, TEXT_SECONDARY);
            hint.setPadding(0, dp(5), 0, 0);
            empty.addView(hint);
            deviceContainer.addView(empty, match(dp(6)));
        }
        for (HciUsbController.HciDevice device : devices) {
            LinearLayout row = card();
            LinearLayout top = horizontal();
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView icon = text("🎮", 23, Color.WHITE);
            top.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout labels = vertical();
            TextView name = text(device.getName(), 16, Color.WHITE);
            name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            labels.addView(name);
            labels.addView(text(device.getAddress(), 12, TEXT_SECONDARY));
            top.addView(labels, weighted());
            row.addView(top);
            String detail = device.getState() +
                    (device.getRssi() != null ? "  •  Signal " + device.getRssi() + " dBm" : "");
            TextView deviceState = text(detail, 13, TEXT_SECONDARY);
            deviceState.setPadding(dp(42), dp(5), 0, 0);
            row.addView(deviceState);
            LinearLayout deviceActions = horizontal();
            deviceActions.setPadding(dp(42), dp(10), 0, 0);
            deviceActions.addView(responsiveActionButton(
                    getString(R.string.dualsense_bridge_connect), false,
                    () -> DualSenseBridge.reconnect(device.getAddress(), device.getName())),
                    weightedWithMargin());
            if (device.getPaired()) {
                deviceActions.addView(actionButton(getString(R.string.dualsense_bridge_forget), false,
                        view -> DualSenseBridge.forgetDevice(device.getAddress())),
                        weightedWithMargin());
            }
            row.addView(deviceActions);
            deviceContainer.addView(row, match(dp(6)));
        }
        scheduleMicrophoneTestRefresh();
    }

    private void scheduleMicrophoneTestRefresh() {
        if (microphoneTestPanel != null &&
                (microphoneTestPanel.getVisibility() == View.VISIBLE ||
                        (DualSenseBluetoothMicrophoneTest.isRecording() ||
                                DualSenseBluetoothMicrophoneTest.isLiveMonitoring()))) {
            mainHandler.removeCallbacks(refreshRunnable);
            mainHandler.postDelayed(refreshRunnable, 250L);
        }
    }

    private void refreshMicrophoneTest() {
        if (microphoneTestStatus == null) return;
        boolean recording = DualSenseBluetoothMicrophoneTest.isRecording();
        boolean monitoring = DualSenseBluetoothMicrophoneTest.isLiveMonitoring();
        microphoneTestStatus.setText(DualSenseBluetoothMicrophoneTest.status());
        microphoneRecordButton.setText(recording ? "Stop recording" : "Start recording");
        microphoneRecordButton.setEnabled(!monitoring);
        microphoneMonitorButton.setText(monitoring ? "Stop live monitor" : "Start live monitor");
        microphoneMonitorButton.setEnabled(!recording);
        microphonePlaybackButton.setEnabled(!recording && !monitoring &&
                DualSenseBluetoothMicrophoneTest.hasRecording());
    }

    private View panelHeader(String title, String subtitle, View panel) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout labels = vertical();
        TextView heading = text(title, 18, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        labels.addView(heading);
        labels.addView(text(subtitle, 12, TEXT_SECONDARY));
        row.addView(labels, weighted());
        row.addView(iconButton(android.R.drawable.ic_menu_close_clear_cancel,
                "Close", v -> panel.setVisibility(View.GONE)));
        return row;
    }

    private View sectionTitle(String title, String subtitle) {
        LinearLayout section = vertical();
        section.setPadding(dp(3), dp(14), dp(3), dp(8));
        TextView heading = text(title, 18, Color.WHITE);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.addView(heading);
        section.addView(text(subtitle, 12, TEXT_SECONDARY));
        return section;
    }

    private void togglePanel(View panel) {
        panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        if (panel.getVisibility() == View.VISIBLE) panel.requestFocus();
        if (panel == microphoneTestPanel) refresh();
    }

    private void testRumble(int low, int high) {
        DualSenseBridge.sendRumble((short) (low << 8), (short) (high << 8));
        mainHandler.postDelayed(() -> DualSenseBridge.sendRumble((short) 0, (short) 0), 500);
    }

    private interface ValueListener { void onValue(int value); }

    private View colorSlider(String name, int initial, ValueListener listener) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(255);
        bar.setProgress(initial);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                listener.onValue(progress);
            }
            public void onStartTrackingTouch(SeekBar seekBar) { }
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        return labelled(name, bar);
    }

    private View labelled(String label, View control) {
        LinearLayout group = vertical();
        group.setPadding(0, dp(7), 0, dp(4));
        group.addView(text(label, 13, Color.WHITE));
        group.addView(control, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return group;
    }

    private Spinner spinner(String[] values) {
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, values));
        return spinner;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout horizontal() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout card() {
        LinearLayout view = vertical();
        view.setPadding(dp(16), dp(15), dp(16), dp(15));
        view.setBackground(rounded(CARD, CARD_BORDER, 18));
        return view;
    }

    private Button actionButton(String value, boolean primary, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(primary ? 0xFF06111D : Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        GradientDrawable content = rounded(primary ? PRIMARY : 0xFF27313C,
                primary ? PRIMARY : 0xFF465463, 13);
        GradientDrawable mask = rounded(Color.WHITE, Color.WHITE, 13);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x5506111D : 0x66FFFFFF), content, mask));
        button.setOnClickListener(action);
        return button;
    }

    private Button responsiveActionButton(String value, boolean primary, Runnable action) {
        Button button = actionButton(value, primary, null);
        button.setOnClickListener(view -> {
            // Give immediate acknowledgement even when the USB/HCI operation
            // takes several seconds before it can publish its next state.
            button.setEnabled(false);
            button.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.65f)
                    .setDuration(90L)
                    .withEndAction(() -> button.animate().scaleX(1f).scaleY(1f)
                            .alpha(1f).setDuration(140L).start())
                    .start();
            action.run();
            mainHandler.postDelayed(() -> button.setEnabled(true), 700L);
        });
        return button;
    }

    private ImageButton iconButton(int icon, String description, View.OnClickListener action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setColorFilter(Color.WHITE);
        button.setContentDescription(description);
        button.setBackground(rounded(0xFF27313C, 0xFF465463, 13));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(48), dp(48));
        params.setMargins(dp(6), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private TextView pill(String value) {
        TextView view = text(value, 12, Color.WHITE);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(11), dp(5), dp(11), dp(5));
        view.setBackground(rounded(0xFF27313C, 0xFF465463, 20));
        return view;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams match(int verticalMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, verticalMargin, 0, verticalMargin);
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams weightedWithMargin() {
        LinearLayout.LayoutParams params = weighted();
        params.setMargins(dp(4), dp(8), dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams wrapStart() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, dp(4));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
