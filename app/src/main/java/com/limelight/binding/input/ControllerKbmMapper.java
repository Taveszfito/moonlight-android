package com.limelight.binding.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import androidx.preference.PreferenceManager;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ControllerKbmMapper {
    public static final String SOURCE_A = "button_a";
    public static final String SOURCE_B = "button_b";
    public static final String SOURCE_X = "button_x";
    public static final String SOURCE_Y = "button_y";
    public static final String SOURCE_DPAD_UP = "dpad_up";
    public static final String SOURCE_DPAD_DOWN = "dpad_down";
    public static final String SOURCE_DPAD_LEFT = "dpad_left";
    public static final String SOURCE_DPAD_RIGHT = "dpad_right";
    public static final String SOURCE_LB = "button_lb";
    public static final String SOURCE_RB = "button_rb";
    public static final String SOURCE_L3 = "button_l3";
    public static final String SOURCE_R3 = "button_r3";
    public static final String SOURCE_START = "button_start";
    public static final String SOURCE_SELECT = "button_select";
    public static final String SOURCE_GUIDE = "button_guide";
    public static final String SOURCE_SHARE = "button_share";
    public static final String SOURCE_TOUCHPAD = "button_touchpad";
    public static final String SOURCE_PADDLE_1 = "button_paddle_1";
    public static final String SOURCE_PADDLE_2 = "button_paddle_2";
    public static final String SOURCE_PADDLE_3 = "button_paddle_3";
    public static final String SOURCE_PADDLE_4 = "button_paddle_4";
    public static final String SOURCE_LT = "trigger_left";
    public static final String SOURCE_RT = "trigger_right";
    public static final String SOURCE_LEFT_STICK = "stick_left";
    public static final String SOURCE_RIGHT_STICK = "stick_right";

    public static final String ACTION_UNASSIGNED = "";
    public static final String ACTION_KEY_PREFIX = "key:";
    public static final String ACTION_MOUSE_LEFT = "mouse_left";
    public static final String ACTION_MOUSE_RIGHT = "mouse_right";
    public static final String ACTION_MOUSE_MIDDLE = "mouse_middle";
    public static final String ACTION_MOUSE_BACK = "mouse_back";
    public static final String ACTION_MOUSE_FORWARD = "mouse_forward";
    public static final String ACTION_WHEEL_UP = "wheel_up";
    public static final String ACTION_WHEEL_DOWN = "wheel_down";
    public static final String ACTION_MOUSE_MOVE = "mouse_move";
    public static final String ACTION_SCROLL = "scroll";
    public static final String ACTION_BASIC_WASD = "basic_wasd";
    public static final String ACTION_BASIC_ARROWS = "basic_arrows";
    public static final String ACTION_DIRECTED_FLICK_PREFIX = "directed_flick:";
    public static final String FLICK_LEFT = "left";
    public static final String FLICK_RIGHT = "right";
    public static final String FLICK_UP = "up";
    public static final String FLICK_DOWN = "down";
    public static final int DEFAULT_FLICK_DISTANCE = 900;
    public static final int MIN_FLICK_DISTANCE = 50;
    public static final int MAX_FLICK_DISTANCE = 4000;

    public static final String PREF_STICK_SPEED = "controller_kbm_stick_speed";
    public static final String PREF_TRIGGER_THRESHOLD = "controller_kbm_trigger_threshold";
    public static final String PREF_GYRO_ENABLED = "controller_kbm_gyro_enabled";
    public static final String PREF_GYRO_SENSITIVITY = "controller_kbm_gyro_sensitivity";
    public static final String PREF_GYRO_INVERT_X = "controller_kbm_gyro_invert_x";
    public static final String PREF_GYRO_INVERT_Y = "controller_kbm_gyro_invert_y";
    public static final String PREF_GYRO_INVERT_Z = "controller_kbm_gyro_invert_z";
    public static final String PREF_GYRO_SMOOTHING = "controller_kbm_gyro_smoothing";
    public static final String PREF_GYRO_STATUS_OVERLAY = "controller_kbm_gyro_status_overlay";
    public static final String PREF_GYRO_HOLD_ACTIVATION = "controller_kbm_gyro_hold_activation";
    public static final String PREF_GYRO_ACTIVATION_SOURCE = "controller_kbm_gyro_activation_source";
    public static final String PREF_GYRO_ACTIVATION_MODE = "controller_kbm_gyro_activation_mode";
    public static final String PREF_GYRO_ACTIVATION_SOURCES = "controller_kbm_gyro_activation_sources";
    public static final String PREF_CONTINUOUS_STICK_MOUSE = "controller_kbm_continuous_stick_mouse";
    public static final String PREF_TRIGGER_BEHAVIOR = "controller_kbm_trigger_behavior";
    public static final String PREF_TRIGGER_REPEAT_RATE = "controller_kbm_trigger_repeat_rate";
    public static final String TRIGGER_BEHAVIOR_HOLD = "hold";
    public static final String TRIGGER_BEHAVIOR_SINGLE = "single";
    public static final String TRIGGER_BEHAVIOR_REPEAT = "repeat";
    public static final String GYRO_ACTIVATION_OFF = "off";
    public static final String GYRO_ACTIVATION_ALWAYS = "always";
    public static final String GYRO_ACTIVATION_HELD = "held";
    private static final String PREF_MAPPING_PREFIX = "controller_kbm_mapping_";
    private static final String PREF_CUSTOM_BUTTONS = "controller_kbm_custom_buttons";
    private static final String PREF_PRESETS = "controller_kbm_presets";
    private static final String PRESET_FILE_FORMAT = "artemis-controller-kbm-preset";
    private static final int PRESET_FILE_VERSION = 1;

    public static final int DEFAULT_STICK_SPEED = 100;
    public static final int DEFAULT_TRIGGER_THRESHOLD = 15;
    public static final int DEFAULT_GYRO_SENSITIVITY = 100;
    public static final boolean DEFAULT_GYRO_INVERT_X = true;
    public static final boolean DEFAULT_GYRO_SMOOTHING = true;
    public static final boolean DEFAULT_GYRO_STATUS_OVERLAY = true;
    public static final int DEFAULT_TRIGGER_REPEAT_RATE = 8;
    public static final int CONTINUOUS_INPUT_POLL_INTERVAL_MS = 8;
    private static final float LEGACY_CONTINUOUS_INPUT_POLL_INTERVAL_MS = 16.0f;

    private static final List<String> STANDARD_SOURCES = Arrays.asList(
            SOURCE_A, SOURCE_B, SOURCE_X, SOURCE_Y,
            SOURCE_DPAD_UP, SOURCE_DPAD_DOWN, SOURCE_DPAD_LEFT, SOURCE_DPAD_RIGHT,
            SOURCE_LB, SOURCE_RB, SOURCE_L3, SOURCE_R3,
            SOURCE_START, SOURCE_SELECT, SOURCE_GUIDE, SOURCE_SHARE,
            SOURCE_TOUCHPAD, SOURCE_PADDLE_1, SOURCE_PADDLE_2, SOURCE_PADDLE_3, SOURCE_PADDLE_4,
            SOURCE_LT, SOURCE_RT, SOURCE_LEFT_STICK, SOURCE_RIGHT_STICK
    );

    private final NvConnection connection;
    private final SharedPreferences preferences;
    private final KeyboardTranslator keyboardTranslator;
    private final Handler flickHandler = new Handler(Looper.getMainLooper());
    private byte modifierMask;

    public static final class Preset {
        public final String id;
        public final String baseName;
        public final int duplicateIndex;
        public final LinkedHashMap<String, String> mappings;

        private Preset(String id, String baseName, int duplicateIndex,
                       LinkedHashMap<String, String> mappings) {
            this.id = id;
            this.baseName = baseName;
            this.duplicateIndex = duplicateIndex;
            this.mappings = mappings;
        }

        public String getDisplayName() {
            return duplicateIndex == 0 ? baseName : baseName + " (" + duplicateIndex + ")";
        }
    }

    public ControllerKbmMapper(Context context, NvConnection connection,
                               PreferenceConfiguration preferenceConfiguration) {
        this.connection = connection;
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.keyboardTranslator = new KeyboardTranslator(preferenceConfiguration);
    }

    public List<String> getSources() {
        List<String> sources = new ArrayList<>(STANDARD_SOURCES);
        Set<String> custom = preferences.getStringSet(PREF_CUSTOM_BUTTONS, new LinkedHashSet<>());
        sources.addAll(custom);
        return sources;
    }

    public List<String> getGyroActivationSources(boolean mappedOnly) {
        List<String> sources = new ArrayList<>();
        for (String source : getSources()) {
            if (!isAxisSource(source) &&
                    !SOURCE_SHARE.equals(source) &&
                    !SOURCE_SELECT.equals(source) &&
                    (!mappedOnly || !getAction(source).isEmpty())) {
                sources.add(source);
            }
        }
        return sources;
    }

    public List<String> getStandardGyroActivationSources() {
        List<String> sources = new ArrayList<>();
        for (String source : STANDARD_SOURCES) {
            if (!isAxisSource(source) &&
                    !SOURCE_SHARE.equals(source) &&
                    !SOURCE_SELECT.equals(source)) {
                sources.add(source);
            }
        }
        return sources;
    }

    public boolean addCustomSource(String source) {
        Set<String> sources = new LinkedHashSet<>(
                preferences.getStringSet(PREF_CUSTOM_BUTTONS, new LinkedHashSet<>()));
        if (STANDARD_SOURCES.contains(source) || !sources.add(source)) {
            return false;
        }
        preferences.edit().putStringSet(PREF_CUSTOM_BUTTONS, sources).apply();
        return true;
    }

    public String getAction(String source) {
        return preferences.getString(PREF_MAPPING_PREFIX + source, ACTION_UNASSIGNED);
    }

    public void setAction(String source, String action) {
        preferences.edit().putString(PREF_MAPPING_PREFIX + source, action).apply();
    }

    public Preset savePreset(String requestedName) {
        String baseName = requestedName.trim();
        if (baseName.isEmpty()) {
            return null;
        }
        List<Preset> presets = getPresets();
        int duplicateIndex = 0;
        for (Preset preset : presets) {
            if (preset.baseName.equals(baseName)) {
                duplicateIndex = Math.max(duplicateIndex, preset.duplicateIndex + 1);
            }
        }

        LinkedHashMap<String, String> mappings = captureCurrentMappings();
        Preset preset = new Preset(UUID.randomUUID().toString(), baseName,
                duplicateIndex, mappings);
        presets.add(preset);
        writePresets(presets);
        return preset;
    }

    public String exportPreset(Preset preset) throws JSONException {
        JSONObject mappings = new JSONObject();
        for (Map.Entry<String, String> entry : preset.mappings.entrySet()) {
            mappings.put(entry.getKey(), entry.getValue());
        }

        JSONObject root = new JSONObject();
        root.put("format", PRESET_FILE_FORMAT);
        root.put("version", PRESET_FILE_VERSION);
        root.put("name", preset.baseName);
        root.put("mappings", mappings);
        return root.toString(2);
    }

    public Preset importPreset(String serialized) throws JSONException {
        JSONObject root = new JSONObject(serialized);
        if (!PRESET_FILE_FORMAT.equals(root.optString("format")) ||
                root.optInt("version", -1) != PRESET_FILE_VERSION) {
            throw new JSONException("Unsupported controller preset format");
        }

        String baseName = root.getString("name").trim();
        if (baseName.isEmpty()) {
            throw new JSONException("Preset name is empty");
        }
        JSONObject mappingObject = root.getJSONObject("mappings");
        LinkedHashMap<String, String> mappings = new LinkedHashMap<>();
        JSONArray names = mappingObject.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String source = names.getString(i);
                String action = mappingObject.getString(source);
                if (!source.isEmpty()) {
                    mappings.put(source, action);
                }
            }
        }

        List<Preset> presets = getPresets();
        int duplicateIndex = 0;
        for (Preset preset : presets) {
            if (preset.baseName.equals(baseName)) {
                duplicateIndex = Math.max(duplicateIndex, preset.duplicateIndex + 1);
            }
        }
        Preset imported = new Preset(UUID.randomUUID().toString(), baseName,
                duplicateIndex, mappings);
        presets.add(imported);
        writePresets(presets);
        return imported;
    }

    public List<Preset> getPresets() {
        List<Preset> presets = new ArrayList<>();
        String serialized = preferences.getString(PREF_PRESETS, "[]");
        try {
            JSONArray array = new JSONArray(serialized);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.getJSONObject(i);
                LinkedHashMap<String, String> mappings = new LinkedHashMap<>();
                JSONObject mappingObject = object.getJSONObject("mappings");
                JSONArray names = mappingObject.names();
                if (names != null) {
                    for (int j = 0; j < names.length(); j++) {
                        String source = names.getString(j);
                        mappings.put(source, mappingObject.optString(source, ACTION_UNASSIGNED));
                    }
                }
                presets.add(new Preset(
                        object.getString("id"),
                        object.getString("baseName"),
                        object.optInt("duplicateIndex", 0),
                        mappings));
            }
        }
        catch (JSONException ignored) {
            // Treat malformed preset storage as empty without affecting the live mapping.
        }
        return presets;
    }

    public void loadPreset(Preset preset) {
        SharedPreferences.Editor editor = preferences.edit();
        removeAllMappingEntries(editor);

        Set<String> customSources = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : preset.mappings.entrySet()) {
            editor.putString(PREF_MAPPING_PREFIX + entry.getKey(), entry.getValue());
            if (!STANDARD_SOURCES.contains(entry.getKey())) {
                customSources.add(entry.getKey());
            }
        }
        editor.putStringSet(PREF_CUSTOM_BUTTONS, customSources).apply();
    }

    public void deletePreset(String presetId) {
        List<Preset> presets = getPresets();
        presets.removeIf(preset -> preset.id.equals(presetId));
        writePresets(presets);
    }

    public void resetMappings() {
        SharedPreferences.Editor editor = preferences.edit();
        removeAllMappingEntries(editor);
        editor.apply();
    }

    public Preset getMatchingPreset() {
        LinkedHashMap<String, String> current = captureCurrentMappings();
        List<Preset> presets = getPresets();
        for (int i = presets.size() - 1; i >= 0; i--) {
            Preset preset = presets.get(i);
            if (preset.mappings.equals(current)) {
                return preset;
            }
        }
        return null;
    }

    private LinkedHashMap<String, String> captureCurrentMappings() {
        LinkedHashMap<String, String> mappings = new LinkedHashMap<>();
        for (String source : getSources()) {
            mappings.put(source, getAction(source));
        }
        return mappings;
    }

    private void removeAllMappingEntries(SharedPreferences.Editor editor) {
        for (String key : preferences.getAll().keySet()) {
            if (key.startsWith(PREF_MAPPING_PREFIX)) {
                editor.remove(key);
            }
        }
    }

    private void writePresets(List<Preset> presets) {
        JSONArray array = new JSONArray();
        try {
            for (Preset preset : presets) {
                JSONObject mappingObject = new JSONObject();
                for (Map.Entry<String, String> entry : preset.mappings.entrySet()) {
                    mappingObject.put(entry.getKey(), entry.getValue());
                }
                JSONObject object = new JSONObject();
                object.put("id", preset.id);
                object.put("baseName", preset.baseName);
                object.put("duplicateIndex", preset.duplicateIndex);
                object.put("mappings", mappingObject);
                array.put(object);
            }
            preferences.edit().putString(PREF_PRESETS, array.toString()).apply();
        }
        catch (JSONException ignored) {
            // All values above are JSON-safe primitives, so this is defensive only.
        }
    }

    public boolean isAxisSource(String source) {
        return SOURCE_LEFT_STICK.equals(source) || SOURCE_RIGHT_STICK.equals(source);
    }

    public boolean isGyroEnabled() {
        return preferences.getBoolean(PREF_GYRO_ENABLED, false) &&
                !GYRO_ACTIVATION_OFF.equals(getGyroActivationMode());
    }

    public String getGyroActivationMode() {
        if (preferences.contains(PREF_GYRO_ACTIVATION_MODE)) {
            return preferences.getString(PREF_GYRO_ACTIVATION_MODE,
                    GYRO_ACTIVATION_ALWAYS);
        }
        return preferences.getBoolean(PREF_GYRO_HOLD_ACTIVATION, false) ?
                GYRO_ACTIVATION_HELD : GYRO_ACTIVATION_ALWAYS;
    }

    public Set<String> getSelectedGyroActivationSources() {
        Set<String> sources = new LinkedHashSet<>(
                preferences.getStringSet(PREF_GYRO_ACTIVATION_SOURCES,
                        new LinkedHashSet<>()));
        if (!preferences.contains(PREF_GYRO_ACTIVATION_SOURCES) &&
                preferences.getBoolean(PREF_GYRO_HOLD_ACTIVATION, false)) {
            String legacySource = preferences.getString(PREF_GYRO_ACTIVATION_SOURCE, "");
            if (!legacySource.isEmpty()) {
                sources.add(legacySource);
            }
        }
        return sources;
    }

    public int getTriggerThreshold() {
        return preferences.getInt(PREF_TRIGGER_THRESHOLD, DEFAULT_TRIGGER_THRESHOLD);
    }

    public boolean isContinuousStickMouseEnabled() {
        return preferences.getBoolean(PREF_CONTINUOUS_STICK_MOUSE, true);
    }

    public String sourceForKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return SOURCE_A;
            case KeyEvent.KEYCODE_BUTTON_B: return SOURCE_B;
            case KeyEvent.KEYCODE_BUTTON_X: return SOURCE_X;
            case KeyEvent.KEYCODE_BUTTON_Y: return SOURCE_Y;
            case KeyEvent.KEYCODE_DPAD_UP: return SOURCE_DPAD_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return SOURCE_DPAD_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return SOURCE_DPAD_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return SOURCE_DPAD_RIGHT;
            case KeyEvent.KEYCODE_BUTTON_L1: return SOURCE_LB;
            case KeyEvent.KEYCODE_BUTTON_R1: return SOURCE_RB;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return SOURCE_L3;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return SOURCE_R3;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU: return SOURCE_START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK: return SOURCE_SELECT;
            case KeyEvent.KEYCODE_BUTTON_MODE: return SOURCE_GUIDE;
            case KeyEvent.KEYCODE_MEDIA_RECORD: return SOURCE_SHARE;
            case KeyEvent.KEYCODE_BUTTON_1: return SOURCE_TOUCHPAD;
            case KeyEvent.KEYCODE_BUTTON_L2: return SOURCE_LT;
            case KeyEvent.KEYCODE_BUTTON_R2: return SOURCE_RT;
            default: return "keycode_" + keyCode;
        }
    }

    public String sourceForKeyEvent(KeyEvent event) {
        String source = sourceForKeyCode(event.getKeyCode());
        if (source.startsWith("keycode_") && event.getScanCode() != 0) {
            return "scancode_" + event.getScanCode();
        }
        return source;
    }

    public boolean handleButton(ControllerHandler.GenericControllerContext context,
                                String source, boolean pressed) {
        if (pressed) {
            if (!context.kbmPressedSources.add(source)) {
                return true;
            }
        }
        else if (!context.kbmPressedSources.remove(source)) {
            return true;
        }

        String action = getAction(source);
        if (action.isEmpty()) {
            return true;
        }

        if (action.startsWith(ACTION_DIRECTED_FLICK_PREFIX)) {
            if (pressed) {
                sendDirectedFlick(action);
            }
            return true;
        }

        if (action.startsWith(ACTION_KEY_PREFIX)) {
            int androidKeyCode;
            try {
                androidKeyCode = Integer.parseInt(action.substring(ACTION_KEY_PREFIX.length()));
            }
            catch (NumberFormatException e) {
                return true;
            }

            short translated = keyboardTranslator.translate(androidKeyCode, 0, -1);
            if (translated == 0) {
                return true;
            }

            byte modifier = KeyboardTranslator.getModifier((short) (translated & 0xFF));
            if (pressed) {
                modifierMask |= modifier;
                connection.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN, modifierMask, (byte) 0);
            }
            else {
                connection.sendKeyboardInput(translated, KeyboardPacket.KEY_UP, modifierMask, (byte) 0);
                modifierMask &= ~modifier;
            }
            return true;
        }

        byte mouseButton = mouseButtonForAction(action);
        if (mouseButton != 0) {
            if (pressed) {
                connection.sendMouseButtonDown(mouseButton);
            }
            else {
                connection.sendMouseButtonUp(mouseButton);
            }
            return true;
        }

        if (pressed && ACTION_WHEEL_UP.equals(action)) {
            connection.sendMouseScroll((byte) 1);
        }
        else if (pressed && ACTION_WHEEL_DOWN.equals(action)) {
            connection.sendMouseScroll((byte) -1);
        }
        return true;
    }

    public static String createDirectedFlickAction(String direction, int distance) {
        return ACTION_DIRECTED_FLICK_PREFIX + normalizeFlickDirection(direction) + ":" +
                Math.max(MIN_FLICK_DISTANCE, Math.min(MAX_FLICK_DISTANCE, distance));
    }

    public static String getDirectedFlickDirection(String action) {
        if (action != null && action.startsWith(ACTION_DIRECTED_FLICK_PREFIX)) {
            String[] parts = action.substring(ACTION_DIRECTED_FLICK_PREFIX.length())
                    .split(":", 2);
            if (parts.length > 0) {
                return normalizeFlickDirection(parts[0]);
            }
        }
        return FLICK_RIGHT;
    }

    public static int getDirectedFlickDistance(String action) {
        if (action != null && action.startsWith(ACTION_DIRECTED_FLICK_PREFIX)) {
            String[] parts = action.substring(ACTION_DIRECTED_FLICK_PREFIX.length())
                    .split(":", 2);
            if (parts.length == 2) {
                try {
                    return Math.max(MIN_FLICK_DISTANCE, Math.min(
                            MAX_FLICK_DISTANCE, Integer.parseInt(parts[1])));
                }
                catch (NumberFormatException ignored) {}
            }
        }
        return DEFAULT_FLICK_DISTANCE;
    }

    private static String normalizeFlickDirection(String direction) {
        if (FLICK_LEFT.equals(direction) || FLICK_UP.equals(direction) ||
                FLICK_DOWN.equals(direction)) {
            return direction;
        }
        return FLICK_RIGHT;
    }

    private void sendDirectedFlick(String action) {
        String direction = getDirectedFlickDirection(action);
        int distance = getDirectedFlickDistance(action);
        final int packetCount = 4;
        for (int i = 0; i < packetCount; i++) {
            final int packetIndex = i;
            flickHandler.postDelayed(() -> {
                int start = (distance * packetIndex) / packetCount;
                int end = (distance * (packetIndex + 1)) / packetCount;
                short delta = (short) (end - start);
                short x = 0;
                short y = 0;
                if (FLICK_LEFT.equals(direction)) {
                    x = (short) -delta;
                }
                else if (FLICK_RIGHT.equals(direction)) {
                    x = delta;
                }
                else if (FLICK_UP.equals(direction)) {
                    y = (short) -delta;
                }
                else {
                    y = delta;
                }
                connection.sendMouseMove(x, y);
            }, i * 6L);
        }
    }

    public void releaseAll(ControllerHandler.GenericControllerContext context) {
        releaseAllDirectionalStickKeys(context, SOURCE_LEFT_STICK);
        releaseAllDirectionalStickKeys(context, SOURCE_RIGHT_STICK);
        for (String source : new ArrayList<>(context.kbmPressedSources)) {
            handleButton(context, source, false);
        }
    }

    private byte mouseButtonForAction(String action) {
        switch (action) {
            case ACTION_MOUSE_LEFT: return MouseButtonPacket.BUTTON_LEFT;
            case ACTION_MOUSE_RIGHT: return MouseButtonPacket.BUTTON_RIGHT;
            case ACTION_MOUSE_MIDDLE: return MouseButtonPacket.BUTTON_MIDDLE;
            case ACTION_MOUSE_BACK: return MouseButtonPacket.BUTTON_X1;
            case ACTION_MOUSE_FORWARD: return MouseButtonPacket.BUTTON_X2;
            default: return 0;
        }
    }

    public void handleStick(String source, float x, float y,
                            ControllerHandler.GenericControllerContext context) {
        String action = getAction(source);
        float magnitude = (float) Math.sqrt((x * x) + (y * y));
        if (magnitude <= 0.08f) {
            x = 0.0f;
            y = 0.0f;
        }
        else {
            float normalizedMagnitude = Math.min(1.0f, (magnitude - 0.08f) / 0.92f);
            float normalizationScale = normalizedMagnitude / magnitude;
            x *= normalizationScale;
            y *= normalizationScale;
        }

        if (ACTION_BASIC_WASD.equals(action)) {
            releaseDirectionalStick(context, source, true);
            handleDirectionalStick(context, source, x, y,
                    KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D,
                    KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, false);
            return;
        }
        else if (ACTION_BASIC_ARROWS.equals(action)) {
            releaseDirectionalStick(context, source, false);
            handleDirectionalStick(context, source, x, y,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, true);
            return;
        }
        else {
            releaseAllDirectionalStickKeys(context, source);
        }

        if (SOURCE_LEFT_STICK.equals(source)) {
            context.kbmLeftStickX = x;
            context.kbmLeftStickY = y;
        }
        else {
            context.kbmRightStickX = x;
            context.kbmRightStickY = y;
        }

        if (!isContinuousStickMouseEnabled()) {
            sendStickOutput(source, x, y, context);
        }
    }

    public void pollContinuousInput(ControllerHandler.GenericControllerContext context) {
        if (isContinuousStickMouseEnabled()) {
            float intervalScale = CONTINUOUS_INPUT_POLL_INTERVAL_MS /
                    LEGACY_CONTINUOUS_INPUT_POLL_INTERVAL_MS;
            sendStickOutput(SOURCE_LEFT_STICK,
                    context.kbmLeftStickX, context.kbmLeftStickY, context, intervalScale);
            sendStickOutput(SOURCE_RIGHT_STICK,
                    context.kbmRightStickX, context.kbmRightStickY, context, intervalScale);
        }
        pollTriggerRepeat(context, SOURCE_LT, context.kbmRepeatLeftTrigger);
        pollTriggerRepeat(context, SOURCE_RT, context.kbmRepeatRightTrigger);
    }

    private void sendStickOutput(String source, float x, float y,
                                 ControllerHandler.GenericControllerContext context) {
        sendStickOutput(source, x, y, context, 1.0f);
    }

    private void sendStickOutput(String source, float x, float y,
                                 ControllerHandler.GenericControllerContext context,
                                 float intervalScale) {
        String action = getAction(source);
        float speed = preferences.getInt(PREF_STICK_SPEED, DEFAULT_STICK_SPEED) / 100.0f;
        // Quadratic response gives small deflections fine control while preserving fast
        // movement near the edge. Scale by the polling interval so increasing the report
        // rate improves smoothness without changing cursor speed.
        float curvedX = Math.copySign(x * x, x) * speed * 18.0f * intervalScale;
        float curvedY = Math.copySign(y * y, y) * speed * 18.0f * intervalScale;

        if (ACTION_MOUSE_MOVE.equals(action)) {
            context.kbmMouseRemainderX += curvedX;
            context.kbmMouseRemainderY += curvedY;
            short deltaX = consumeWholePixelsX(context);
            short deltaY = consumeWholePixelsY(context);
            if (deltaX != 0 || deltaY != 0) {
                connection.sendMouseMove(deltaX, deltaY);
            }
        }
        else if (ACTION_SCROLL.equals(action)) {
            context.kbmScrollRemainderX += curvedX * 6.0f;
            context.kbmScrollRemainderY += curvedY * 6.0f;
            short scrollX = consumeWholeScrollX(context);
            short scrollY = consumeWholeScrollY(context);
            if (scrollY != 0) {
                connection.sendMouseHighResScroll((short) -scrollY);
            }
            if (scrollX != 0) {
                connection.sendMouseHighResHScroll(scrollX);
            }
        }
    }

    public void handleTrigger(ControllerHandler.GenericControllerContext context,
                              String source, boolean pressed) {
        String behavior = preferences.getString(PREF_TRIGGER_BEHAVIOR,
                TRIGGER_BEHAVIOR_HOLD);
        if (TRIGGER_BEHAVIOR_SINGLE.equals(behavior)) {
            if (pressed) {
                sendButtonClick(context, source);
            }
        }
        else if (TRIGGER_BEHAVIOR_REPEAT.equals(behavior)) {
            if (SOURCE_LT.equals(source)) {
                context.kbmRepeatLeftTrigger = pressed;
                context.kbmLastLeftTriggerRepeatNs = 0;
            }
            else {
                context.kbmRepeatRightTrigger = pressed;
                context.kbmLastRightTriggerRepeatNs = 0;
            }
            if (pressed) {
                sendButtonClick(context, source);
            }
        }
        else {
            handleButton(context, source, pressed);
        }
    }

    private void pollTriggerRepeat(ControllerHandler.GenericControllerContext context,
                                   String source, boolean active) {
        if (!active || !TRIGGER_BEHAVIOR_REPEAT.equals(
                preferences.getString(PREF_TRIGGER_BEHAVIOR, TRIGGER_BEHAVIOR_HOLD))) {
            return;
        }
        long now = System.nanoTime();
        long last = SOURCE_LT.equals(source) ?
                context.kbmLastLeftTriggerRepeatNs : context.kbmLastRightTriggerRepeatNs;
        int rate = Math.max(1, preferences.getInt(PREF_TRIGGER_REPEAT_RATE,
                DEFAULT_TRIGGER_REPEAT_RATE));
        if (last == 0) {
            if (SOURCE_LT.equals(source)) {
                context.kbmLastLeftTriggerRepeatNs = now;
            }
            else {
                context.kbmLastRightTriggerRepeatNs = now;
            }
            return;
        }
        if (now - last >= 1_000_000_000L / rate) {
            sendButtonClick(context, source);
            if (SOURCE_LT.equals(source)) {
                context.kbmLastLeftTriggerRepeatNs = now;
            }
            else {
                context.kbmLastRightTriggerRepeatNs = now;
            }
        }
    }

    private void sendButtonClick(ControllerHandler.GenericControllerContext context,
                                 String source) {
        handleButton(context, source, true);
        handleButton(context, source, false);
    }

    private void handleDirectionalStick(ControllerHandler.GenericControllerContext context,
                                        String source, float x, float y,
                                        int leftKey, int rightKey, int upKey, int downKey,
                                        boolean arrows) {
        String prefix = "stick_key_" + source + "_" + (arrows ? "arrows_" : "wasd_");
        updateDirectionalKey(context, prefix + "left", leftKey, x, -1);
        updateDirectionalKey(context, prefix + "right", rightKey, x, 1);
        updateDirectionalKey(context, prefix + "up", upKey, y, -1);
        updateDirectionalKey(context, prefix + "down", downKey, y, 1);
    }

    private void updateDirectionalKey(ControllerHandler.GenericControllerContext context,
                                      String stateKey, int androidKeyCode,
                                      float axisValue, int direction) {
        boolean wasPressed = context.kbmPressedSources.contains(stateKey);
        float directedValue = axisValue * direction;
        boolean pressed = directedValue >= (wasPressed ? 0.30f : 0.45f);
        if (pressed == wasPressed) {
            return;
        }

        if (pressed) {
            context.kbmPressedSources.add(stateKey);
        }
        else {
            context.kbmPressedSources.remove(stateKey);
        }
        sendKeyboardKey(androidKeyCode, pressed);
    }

    private void releaseDirectionalStick(ControllerHandler.GenericControllerContext context,
                                         String source, boolean releaseArrows) {
        String prefix = "stick_key_" + source + "_" + (releaseArrows ? "arrows_" : "wasd_");
        int[] keys = releaseArrows ?
                new int[] { KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN } :
                new int[] { KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D,
                        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S };
        String[] directions = { "left", "right", "up", "down" };
        for (int i = 0; i < directions.length; i++) {
            String stateKey = prefix + directions[i];
            if (context.kbmPressedSources.remove(stateKey)) {
                sendKeyboardKey(keys[i], false);
            }
        }
    }

    private void releaseAllDirectionalStickKeys(
            ControllerHandler.GenericControllerContext context, String source) {
        releaseDirectionalStick(context, source, false);
        releaseDirectionalStick(context, source, true);
    }

    private void sendKeyboardKey(int androidKeyCode, boolean pressed) {
        short translated = keyboardTranslator.translate(androidKeyCode, 0, -1);
        if (translated == 0) {
            return;
        }
        byte modifier = KeyboardTranslator.getModifier((short) (translated & 0xFF));
        if (pressed) {
            modifierMask |= modifier;
            connection.sendKeyboardInput(translated, KeyboardPacket.KEY_DOWN,
                    modifierMask, (byte) 0);
        }
        else {
            connection.sendKeyboardInput(translated, KeyboardPacket.KEY_UP,
                    modifierMask, (byte) 0);
            modifierMask &= ~modifier;
        }
    }

    public void handleGyro(float x, float y, float z, float inputScale,
                           ControllerHandler.GenericControllerContext context) {
        if (!isGyroEnabled()) {
            return;
        }
        if (GYRO_ACTIVATION_HELD.equals(getGyroActivationMode())) {
            boolean anyHeld = false;
            for (String source : getSelectedGyroActivationSources()) {
                if (SOURCE_LT.equals(source) ? context.kbmLeftTriggerPressed :
                        SOURCE_RT.equals(source) ? context.kbmRightTriggerPressed :
                                context.kbmPressedSources.contains(source)) {
                    anyHeld = true;
                    break;
                }
            }
            if (!anyHeld) {
                context.kbmLastGyroSampleNs = 0;
                context.kbmGyroFilteredX = 0.0f;
                context.kbmGyroFilteredY = 0.0f;
                context.kbmGyroRemainderX = 0.0f;
                context.kbmGyroRemainderY = 0.0f;
                return;
            }
        }
        if (preferences.getBoolean(PREF_GYRO_INVERT_X, DEFAULT_GYRO_INVERT_X)) {
            x = -x;
        }
        if (preferences.getBoolean(PREF_GYRO_INVERT_Y, false)) {
            y = -y;
        }
        if (preferences.getBoolean(PREF_GYRO_INVERT_Z, false)) {
            z = -z;
        }
        float sensitivity = preferences.getInt(PREF_GYRO_SENSITIVITY,
                DEFAULT_GYRO_SENSITIVITY) / 100.0f;
        long now = System.nanoTime();
        if (context.kbmLastGyroSampleNs == 0 ||
                now - context.kbmLastGyroSampleNs > 100_000_000L) {
            context.kbmLastGyroSampleNs = now;
            context.kbmGyroFilteredX = 0.0f;
            context.kbmGyroFilteredY = 0.0f;
            return;
        }
        float deltaSeconds = (now - context.kbmLastGyroSampleNs) / 1_000_000_000.0f;
        context.kbmLastGyroSampleNs = now;

        // Use the dominant side-rotation axis instead of adding both axes together. Adding
        // them makes the same physical turn vary with controller/device orientation.
        float yaw = Math.abs(y) >= Math.abs(z) ? y : z;
        float velocityX = yaw * inputScale;
        float velocityY = x * inputScale;
        if (Math.abs(velocityX) < 0.5f) velocityX = 0.0f;
        if (Math.abs(velocityY) < 0.5f) velocityY = 0.0f;
        float smoothingAlpha = preferences.getBoolean(PREF_GYRO_SMOOTHING,
                DEFAULT_GYRO_SMOOTHING) ? 0.35f : 1.0f;
        context.kbmGyroFilteredX +=
                (velocityX - context.kbmGyroFilteredX) * smoothingAlpha;
        context.kbmGyroFilteredY +=
                (velocityY - context.kbmGyroFilteredY) * smoothingAlpha;

        context.kbmGyroRemainderX += context.kbmGyroFilteredX * deltaSeconds * sensitivity;
        context.kbmGyroRemainderY += context.kbmGyroFilteredY * deltaSeconds * sensitivity;
        short deltaX = consumeWholeGyroX(context);
        short deltaY = consumeWholeGyroY(context);
        if (deltaX != 0 || deltaY != 0) {
            connection.sendMouseMove(deltaX, deltaY);
        }
    }

    private short consumeWholePixelsX(ControllerHandler.GenericControllerContext context) {
        short value = clampShort((int) context.kbmMouseRemainderX);
        context.kbmMouseRemainderX -= value;
        return value;
    }

    private short consumeWholePixelsY(ControllerHandler.GenericControllerContext context) {
        short value = clampShort((int) context.kbmMouseRemainderY);
        context.kbmMouseRemainderY -= value;
        return value;
    }

    private short consumeWholeScrollX(ControllerHandler.GenericControllerContext context) {
        short value = clampShort((int) context.kbmScrollRemainderX);
        context.kbmScrollRemainderX -= value;
        return value;
    }

    private short consumeWholeScrollY(ControllerHandler.GenericControllerContext context) {
        short value = clampShort((int) context.kbmScrollRemainderY);
        context.kbmScrollRemainderY -= value;
        return value;
    }

    private short consumeWholeGyroX(ControllerHandler.GenericControllerContext context) {
        short value = clampShort((int) context.kbmGyroRemainderX);
        context.kbmGyroRemainderX -= value;
        return value;
    }

    private short consumeWholeGyroY(ControllerHandler.GenericControllerContext context) {
        short value = clampShort((int) context.kbmGyroRemainderY);
        context.kbmGyroRemainderY -= value;
        return value;
    }

    private short clampShort(int value) {
        return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, value));
    }
}
