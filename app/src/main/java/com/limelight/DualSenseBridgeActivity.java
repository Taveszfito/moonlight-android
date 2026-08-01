package com.limelight;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
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
    private TextView statusTitle;
    private TextView statusDetails;
    private TextView batteryView;
    private TextView inputView;
    private TextView logView;
    private ScrollView pageScroll;
    private ScrollView logScroll;
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
                        logView.setText(DualSenseBridge.getLog());
                    }
                }));
        root.addView(header, match(dp(8)));

        TextView alpha = pill("ALPHA");
        root.addView(alpha, wrapStart());

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

        LinearLayout hostModeRow = horizontal();
        hostModeRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hostModeText = vertical();
        TextView hostModeTitle = text(getString(R.string.dualsense_bridge_host_mode), 15, Color.WHITE);
        hostModeTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        hostModeText.addView(hostModeTitle);
        hostModeText.addView(text(
                getString(R.string.dualsense_bridge_host_mode_summary),
                12, TEXT_SECONDARY));
        hostModeRow.addView(hostModeText, weighted());
        Switch hostModeSwitch = new Switch(this);
        hostModeSwitch.setContentDescription(getString(R.string.dualsense_bridge_host_mode));
        hostModeSwitch.setChecked(DualSenseBridge.HOST_MODE_PLAYSTATION.equals(
                DualSenseBridge.getHostControllerMode()));
        hostModeSwitch.setOnCheckedChangeListener((button, checked) ->
                DualSenseBridge.setHostControllerMode(checked ?
                        DualSenseBridge.HOST_MODE_PLAYSTATION :
                        DualSenseBridge.HOST_MODE_XBOX));
        hostModeRow.addView(hostModeSwitch);
        hostModeRow.setPadding(0, dp(12), 0, dp(2));
        connection.addView(hostModeRow);

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

        Button scan = actionButton(getString(R.string.dualsense_bridge_scan), true,
                v -> DualSenseBridge.scan(this));
        scan.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0);
        scan.setCompoundDrawablePadding(dp(9));
        connection.addView(scan, match(dp(14)));

        LinearLayout secondaryActions = horizontal();
        secondaryActions.addView(actionButton(getString(R.string.dualsense_bridge_reset_adapter), false,
                v -> DualSenseBridge.reset(this)), weightedWithMargin());
        secondaryActions.addView(actionButton(getString(R.string.dualsense_bridge_disconnect), false,
                v -> DualSenseBridge.disconnectBridge()), weightedWithMargin());
        connection.addView(secondaryActions);
        root.addView(connection, match(dp(12)));

        root.addView(sectionTitle(getString(R.string.dualsense_bridge_devices_title),
                getString(R.string.dualsense_bridge_devices_summary)));
        deviceContainer = vertical();
        root.addView(deviceContainer);

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
        boolean connected = DualSenseBridge.getControllerConnected();
        DualSenseInput input = DualSenseBridge.getLatestInput();
        statusTitle.setText(connected ? R.string.dualsense_bridge_connected :
                R.string.dualsense_bridge_not_connected);
        statusTitle.setTextColor(connected ? CONNECTED : Color.WHITE);
        statusDetails.setText(DualSenseBridge.getStatus());
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
                "\nButtons: " + input.getPressed());
        List<HciUsbController.HciDevice> devices = DualSenseBridge.getDevices();
        StringBuilder signature = new StringBuilder();
        signature.append("connected=").append(connected).append(';');
        for (HciUsbController.HciDevice device : devices) {
            signature.append(device.getAddress()).append('|')
                    .append(device.getPaired()).append(';');
        }
        if (signature.toString().equals(lastDeviceSignature)) {
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
            deviceActions.addView(actionButton(getString(R.string.dualsense_bridge_connect), false,
                    view -> DualSenseBridge.reconnect(device.getAddress(), device.getName())),
                    weightedWithMargin());
            if (device.getPaired()) {
                deviceActions.addView(actionButton(getString(R.string.dualsense_bridge_forget), false,
                        view -> DualSenseBridge.forgetDevice(device.getAddress())),
                        weightedWithMargin());
            }
            row.addView(deviceActions);
            deviceContainer.addView(row, match(dp(6)));
        }
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
        button.setBackground(rounded(primary ? PRIMARY : 0xFF27313C,
                primary ? PRIMARY : 0xFF465463, 13));
        button.setOnClickListener(action);
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
