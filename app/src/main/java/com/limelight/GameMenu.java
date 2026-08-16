package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.ContextThemeWrapper;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.Window.Callback;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.util.TypedValue;

import androidx.preference.PreferenceManager;
import com.limelight.dualsense.DualSenseBridge;
import com.limelight.dualsense.DualSenseAudioBridge;
import com.limelight.dualsense.DualSenseMicrophoneBridge;
import com.limelight.binding.input.driver.DualSenseController;
import com.example.usbbtonandroid.DualSenseInput;
import androidx.appcompat.view.WindowCallbackWrapper;

import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.ControllerKbmMapper;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyConfigHelper;
import com.limelight.utils.KeyMapper;
import com.limelight.utils.MouseModeOption;

import java.lang.reflect.Field;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class GameMenu implements Game.GameMenuCallbacks {
    public static final int REQUEST_CODE_EXPORT_KBM_PRESET = 779;
    public static final int REQUEST_CODE_IMPORT_KBM_PRESET = 780;
    private static final int MAX_PRESET_FILE_SIZE = 1024 * 1024;

    public static final long KEY_UP_DELAY = 25;
    private static final long TEST_GAME_FOCUS_DELAY = 10;

    public static final String PREF_NAME = "specialPrefs";
    public static final String KEY_NAME = "special_key";

    private static final String QUICK_MENU_ORDER_KEY = "quick_menu_order";
    private static final String QUICK_MENU_HIDDEN_KEY = "quick_menu_hidden";
    private static final String SUBMENU_HIDDEN_PREFIX = "submenu_hidden_";

    private static final String MENU_DISCONNECT = "disconnect";
    private static final String MENU_QUIT_SESSION = "quit_session";
    private static final String MENU_UPLOAD_CLIPBOARD = "upload_clipboard";
    private static final String MENU_FETCH_CLIPBOARD = "fetch_clipboard";
    private static final String MENU_SERVER_CMD = "server_cmd";
    private static final String MENU_TOGGLE_KEYBOARD = "toggle_keyboard";
    private static final String MENU_ZOOM_MODE = "zoom_mode";
    private static final String MENU_ROTATE_SCREEN = "rotate_screen";
    private static final String MENU_ADVANCED = "advanced";
    private static final String MENU_DUALSENSE_BRIDGE = "dualsense_bridge";
    private static final String MENU_DUALSENSE_MICROPHONE = "dualsense_microphone";
    private static final String MENU_DUALSENSE_AUDIO_ROUTE = "dualsense_audio_route";
    private static final String MENU_GYRO_AXIS_MAPPING = "gyro_axis_mapping";
    private static final String MENU_CANCEL = "cancel";

    private static final String ADV_MOUSE_MODE = "advanced_mouse_mode";
    private static final String ADV_HUD = "advanced_hud";
    private static final String ADV_FLOATING_BUTTON = "advanced_floating_button";
    private static final String ADV_SPECIAL_KEYS_TOGGLE = "advanced_special_keys_toggle";
    private static final String ADV_OSC_TOGGLE = "advanced_osc_toggle";
    private static final String ADV_FULL_KEYBOARD_TOGGLE = "advanced_full_keyboard_toggle";
    private static final String ADV_TASK_MANAGER = "advanced_task_manager";
    private static final String ADV_VOLUME_BUTTONS = "advanced_volume_buttons";
    private static final String ADV_GYRO_AIM_SETTINGS = "advanced_gyro_aim_settings";
    private static final String ADV_CONTROLLER_KBM = "advanced_controller_kbm";
    private static final String ADV_SEND_KEYS = "advanced_send_keys";
    private static final String ADV_TOUCH_SENSITIVITY = "advanced_touch_sensitivity";
    private static final String CONTROLLER_KBM_PICK_FUNCTION_KEY = "pick_function_key";
    private static final String CONTROLLER_KBM_CONFIGURE_FLICK = "configure_directed_flick";
    private static final String CONTROLLER_KBM_ICON_LAYOUT_PREF =
            "controller_kbm_icon_layout";
    private static final String CONTROLLER_KBM_ICON_LAYOUT_XBOX = "xbox";
    private static final String CONTROLLER_KBM_ICON_LAYOUT_PLAYSTATION = "playstation";

    private static final List<String> DEFAULT_QUICK_MENU_ORDER = Arrays.asList(
            MENU_DISCONNECT,
            MENU_QUIT_SESSION,
            MENU_UPLOAD_CLIPBOARD,
            MENU_FETCH_CLIPBOARD,
            MENU_SERVER_CMD,
            MENU_TOGGLE_KEYBOARD,
            MENU_ZOOM_MODE,
            MENU_ROTATE_SCREEN,
            MENU_GYRO_AXIS_MAPPING,
            MENU_DUALSENSE_BRIDGE,
            MENU_DUALSENSE_MICROPHONE,
            MENU_DUALSENSE_AUDIO_ROUTE,
            MENU_ADVANCED,
            MENU_CANCEL
    );

    public static class MenuOption {
        private final String id;
        private final String label;
        private final boolean withGameFocus;
        private final Runnable runnable;

        public MenuOption(String id, String label, boolean withGameFocus, Runnable runnable) {
            this.id = id;
            this.label = label;
            this.withGameFocus = withGameFocus;
            this.runnable = runnable;
        }

        public MenuOption(String id, String label, Runnable runnable) {
            this(id, label, false, runnable);
        }

        public MenuOption(String label, boolean withGameFocus, Runnable runnable) {
            this(null, label, withGameFocus, runnable);
        }

        public MenuOption(String label, Runnable runnable) {
            this(null, label, false, runnable);
        }
    }

    private final Game game;
    private final Context dialogScreenContext;

    private AlertDialog currentDialog;
    private Runnable controllerBackAction;
    private long lastControllerNavigationTime;
    private TextView controllerNavigationHint;
    private SeekBar controllerCapturedSeekBar;
    private String pendingPresetExport;
    private GameInputDevice pendingPresetDevice;
    private boolean quickMenuEditMode;
    private int draggedQuickMenuIndex = -1;
    private String draggedQuickMenuId;
    private int bridgeLastButtonFlags;
    private boolean bridgeStickCentered = true;

    public GameMenu(Game game, Context dialogScreenContext) {
        this.game = game;
        this.dialogScreenContext = dialogScreenContext;
    }

    public GameMenu(Game game) {
        this.game = game;
        this.dialogScreenContext = game;
    }

    private String getString(int id) {
        return game.getResources().getString(id);
    }

    private Context getThemedContext() {
        return new ContextThemeWrapper(dialogScreenContext, game.getApplicationInfo().theme);
    }

    private SharedPreferences getMenuPrefs() {
        return game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
    }

    private String joinIds(List<String> ids) {
        return TextUtils.join(",", ids);
    }

    private List<String> splitIds(String value) {
        List<String> ids = new ArrayList<>();
        if (TextUtils.isEmpty(value)) {
            return ids;
        }

        for (String id : value.split(",")) {
            if (!TextUtils.isEmpty(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private List<String> getSavedQuickMenuOrder() {
        List<String> savedOrder = splitIds(getMenuPrefs().getString(QUICK_MENU_ORDER_KEY, ""));
        List<String> order = new ArrayList<>();

        for (String id : savedOrder) {
            if (DEFAULT_QUICK_MENU_ORDER.contains(id) && !order.contains(id)) {
                order.add(id);
            }
        }
        for (String id : DEFAULT_QUICK_MENU_ORDER) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }
        return order;
    }

    private Set<String> getHiddenQuickMenuIds() {
        Set<String> hidden = new HashSet<>();
        for (String id : splitIds(getMenuPrefs().getString(QUICK_MENU_HIDDEN_KEY, ""))) {
            if (DEFAULT_QUICK_MENU_ORDER.contains(id) && !MENU_CANCEL.equals(id)) {
                hidden.add(id);
            }
        }
        return hidden;
    }

    private String submenuHiddenKey(String title) {
        return SUBMENU_HIDDEN_PREFIX + Integer.toHexString(title.hashCode());
    }

    private Set<String> getHiddenSubmenuIds(String title) {
        return new HashSet<>(splitIds(getMenuPrefs().getString(submenuHiddenKey(title), "")));
    }

    private void saveHiddenSubmenuIds(String title, Set<String> hiddenIds) {
        getMenuPrefs().edit().putString(submenuHiddenKey(title), joinIds(new ArrayList<>(hiddenIds))).apply();
    }

    private void saveQuickMenuState(List<MenuOption> visibleOptions, Set<String> hiddenIds) {
        List<String> order = new ArrayList<>();
        for (MenuOption option : visibleOptions) {
            if (option.id != null && DEFAULT_QUICK_MENU_ORDER.contains(option.id) && !order.contains(option.id)) {
                order.add(option.id);
            }
        }
        for (String id : DEFAULT_QUICK_MENU_ORDER) {
            if (!order.contains(id)) {
                order.add(id);
            }
        }

        getMenuPrefs().edit()
                .putString(QUICK_MENU_ORDER_KEY, joinIds(order))
                .putString(QUICK_MENU_HIDDEN_KEY, joinIds(new ArrayList<>(hiddenIds)))
                .apply();
    }

    private MenuOption findOptionById(List<MenuOption> options, String id) {
        for (MenuOption option : options) {
            if (id.equals(option.id)) {
                return option;
            }
        }
        return null;
    }

    private int getDefaultOrderIndex(String id) {
        return DEFAULT_QUICK_MENU_ORDER.indexOf(id);
    }

    private void addOptionInDefaultPlace(List<MenuOption> visibleOptions, MenuOption option) {
        int insertIndex = visibleOptions.size();
        int restoredDefaultIndex = getDefaultOrderIndex(option.id);
        for (int i = 0; i < visibleOptions.size(); i++) {
            if (getDefaultOrderIndex(visibleOptions.get(i).id) > restoredDefaultIndex) {
                insertIndex = i;
                break;
            }
        }
        visibleOptions.add(insertIndex, option);
    }

    private int indexOfOption(MenuOption[] options, String id) {
        for (int i = 0; i < options.length; i++) {
            if (id.equals(options[i].id)) {
                return i;
            }
        }
        return -1;
    }

    private void addSubmenuOptionInDefaultPlace(MenuOption[] allOptions, List<MenuOption> visibleOptions,
                                                MenuOption option) {
        int insertIndex = visibleOptions.size();
        int restoredDefaultIndex = indexOfOption(allOptions, option.id);
        for (int i = 0; i < visibleOptions.size(); i++) {
            if (indexOfOption(allOptions, visibleOptions.get(i).id) > restoredDefaultIndex) {
                insertIndex = i;
                break;
            }
        }
        visibleOptions.add(insertIndex, option);
    }

    private void sendKeys(short[] keys) {
        game.sendKeys(keys);
    }

    private void runWithGameFocus(Runnable runnable) {
        if (game.isFinishing()) {
            return;
        }
        if (!game.hasWindowFocus() && dialogScreenContext instanceof Game) {
            new Handler().postDelayed(() -> runWithGameFocus(runnable), TEST_GAME_FOCUS_DELAY);
            return;
        }
        runnable.run();
    }

    private void run(MenuOption option) {
        if (option.runnable == null) {
            return;
        }

        if (option.withGameFocus) {
            runWithGameFocus(option.runnable);
        } else {
            option.runnable.run();
        }
    }

    private void refreshList(ArrayAdapter<MenuOption> adapter, List<MenuOption> options) {
        adapter.notifyDataSetChanged();
    }

    private int dp(int value) {
        return Math.round(value * game.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundedBackground(int color, float radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp((int) radiusDp));
        background.setStroke(dp(1), 0x24FFFFFF);
        return background;
    }

    private int getQuickMenuIcon(String id) {
        if (MENU_DISCONNECT.equals(id)) {
            return R.drawable.ic_qm_input;
        }
        if (MENU_QUIT_SESSION.equals(id)) {
            return R.drawable.ic_qm_power;
        }
        if (MENU_UPLOAD_CLIPBOARD.equals(id)) {
            return R.drawable.ic_qm_upload;
        }
        if (MENU_FETCH_CLIPBOARD.equals(id)) {
            return R.drawable.ic_qm_download;
        }
        if (MENU_SERVER_CMD.equals(id)) {
            return R.drawable.ic_qm_terminal;
        }
        if (MENU_TOGGLE_KEYBOARD.equals(id)) {
            return R.drawable.ic_qm_keyboard;
        }
        if (MENU_ZOOM_MODE.equals(id)) {
            return R.drawable.ic_qm_zoom;
        }
        if (MENU_ROTATE_SCREEN.equals(id)) {
            return R.drawable.ic_qm_rotate;
        }
        if (MENU_GYRO_AXIS_MAPPING.equals(id)) {
            return R.drawable.ic_qm_gyro;
        }
        if (MENU_ADVANCED.equals(id)) {
            return R.drawable.ic_qm_tune;
        }
        if (MENU_DUALSENSE_BRIDGE.equals(id)) {
            return R.drawable.ic_qm_controller;
        }
        if (ADV_MOUSE_MODE.equals(id)) {
            return R.drawable.ic_qm_mouse;
        }
        if (ADV_HUD.equals(id)) {
            return R.drawable.ic_qm_hud;
        }
        if (ADV_FLOATING_BUTTON.equals(id)) {
            return R.drawable.ic_qm_floating;
        }
        if (ADV_SPECIAL_KEYS_TOGGLE.equals(id)) {
            return R.drawable.ic_qm_keyboard;
        }
        if (ADV_OSC_TOGGLE.equals(id)) {
            return R.drawable.ic_qm_controller;
        }
        if (ADV_FULL_KEYBOARD_TOGGLE.equals(id)) {
            return R.drawable.ic_qm_fullscreen;
        }
        if (ADV_TASK_MANAGER.equals(id)) {
            return R.drawable.ic_qm_task_manager;
        }
        if (ADV_VOLUME_BUTTONS.equals(id)) {
            return R.drawable.ic_qm_volume;
        }
        if (ADV_GYRO_AIM_SETTINGS.equals(id)) {
            return R.drawable.ic_qm_gyro;
        }
        if (ADV_CONTROLLER_KBM.equals(id)) {
            return R.drawable.ic_qm_controller;
        }
        if (ADV_SEND_KEYS.equals(id)) {
            return R.drawable.ic_qm_input;
        }
        if (ADV_TOUCH_SENSITIVITY.equals(id)) {
            return R.drawable.ic_qm_touch;
        }
        if ("keys_esc".equals(id)) {
            return R.drawable.ic_qm_input;
        }
        if ("keys_f11".equals(id) || "keys_alt_enter".equals(id)) {
            return R.drawable.ic_qm_fullscreen;
        }
        if ("keys_alt_f4".equals(id)) {
            return R.drawable.ic_qm_close;
        }
        if ("keys_ctrl_v".equals(id)) {
            return R.drawable.ic_qm_paste;
        }
        if ("keys_win".equals(id)) {
            return R.drawable.ic_qm_window;
        }
        if ("keys_win_d".equals(id)) {
            return R.drawable.ic_qm_desktop;
        }
        if ("keys_win_g".equals(id)) {
            return R.drawable.ic_qm_sports;
        }
        if ("keys_ctrl_alt_tab".equals(id)) {
            return R.drawable.ic_qm_tab;
        }
        if ("keys_shift_tab".equals(id) || "keys_win_shift_left".equals(id)) {
            return R.drawable.ic_qm_swap;
        }
        if ("keys_ctrl_alt_shift_f1".equals(id) ||
                "keys_ctrl_alt_shift_f12".equals(id)) {
            return R.drawable.ic_qm_monitor;
        }
        if (id != null && id.startsWith("keys_")) {
            return R.drawable.ic_qm_keyboard;
        }
        if (id != null && id.startsWith("custom_")) {
            return R.drawable.ic_qm_keyboard;
        }
        if (id != null && id.startsWith("server_")) {
            return R.drawable.ic_qm_terminal;
        }
        if ("mouse_mode_0".equals(id)) {
            return R.drawable.ic_qm_touch;
        }
        if ("mouse_mode_1".equals(id) || "mouse_mode_2".equals(id)) {
            return R.drawable.ic_qm_mouse;
        }
        if ("mouse_mode_3".equals(id)) {
            return R.drawable.ic_qm_sports;
        }
        if ("mouse_mode_4".equals(id)) {
            return R.drawable.ic_qm_disabled;
        }
        if ("mouse_mode_5".equals(id)) {
            return R.drawable.ic_qm_swap;
        }
        if ("volume_mode_android".equals(id)) {
            return R.drawable.ic_qm_phone;
        }
        if ("volume_mode_windows".equals(id)) {
            return R.drawable.ic_qm_desktop;
        }
        if ("kbm_settings".equals(id)) {
            return R.drawable.ic_qm_settings;
        }
        if ("kbm_add".equals(id)) {
            return R.drawable.ic_qm_add;
        }
        if (id != null && id.startsWith("kbm_source_")) {
            return R.drawable.ic_qm_controller;
        }
        if ("kbm_action_unassigned".equals(id)) {
            return R.drawable.ic_qm_disabled;
        }
        if (id != null && id.startsWith("kbm_action_")) {
            if (id.contains("mouse") || id.contains("wheel") ||
                    id.contains("scroll") || id.contains("flick")) {
                return R.drawable.ic_qm_mouse;
            }
            return R.drawable.ic_qm_keyboard;
        }
        if (id != null && id.startsWith("kbm_function_")) {
            return R.drawable.ic_qm_keyboard;
        }
        if (MENU_CANCEL.equals(id)) {
            return R.drawable.ic_qm_close;
        }
        return R.drawable.ic_qm_settings;
    }

    private View createQuickMenuCard(MenuOption option, Runnable onClick) {
        LinearLayout card = new LinearLayout(getThemedContext());
        boolean destructive = MENU_DISCONNECT.equals(option.id) ||
                MENU_QUIT_SESSION.equals(option.id);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(14), dp(18), dp(14), dp(14));
        card.setBackground(roundedBackground(destructive ? 0xCC3A2026 : 0xCC202630, 18));
        card.setClickable(true);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setTag("controller_menu_card");
        card.setOnClickListener(view -> onClick.run());
        card.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                hideControllerNavigationHint();
            }
            view.setBackground(roundedBackground(
                    hasFocus ? (destructive ? 0xFF6A3038 : 0xFF354352)
                            : (destructive ? 0xCC3A2026 : 0xCC202630),
                    18));
            view.setScaleX(hasFocus ? 1.03f : 1f);
            view.setScaleY(hasFocus ? 1.03f : 1f);
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            TypedValue ripple = new TypedValue();
            getThemedContext().getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true);
            card.setForeground(getThemedContext().getDrawable(ripple.resourceId));
        }

        View icon;
        if (option.id != null && option.id.startsWith("kbm_source_")) {
            icon = new ControllerButtonIconView(
                    option.id.substring("kbm_source_".length()));
        }
        else {
            ImageView image = new ImageView(getThemedContext());
            image.setImageResource(getQuickMenuIcon(option.id));
            image.setColorFilter(destructive ? 0xFFFF8A80 : 0xFFF7B52C);
            icon = image;
        }
        card.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));

        TextView label = new TextView(getThemedContext());
        SpannableStringBuilder cardLabel = new SpannableStringBuilder(option.label);
        if (isEditableNavigationOption(option)) {
            int arrowStart = cardLabel.length();
            cardLabel.append("  ›");
            cardLabel.setSpan(new ForegroundColorSpan(0xFFF7B52C),
                    arrowStart, cardLabel.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        label.setText(cardLabel);
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(12);
        card.addView(label, labelParams);
        return card;
    }

    private boolean isEditableNavigationOption(MenuOption option) {
        if (option == null || option.id == null) {
            return false;
        }
        return MENU_ADVANCED.equals(option.id) ||
                MENU_SERVER_CMD.equals(option.id) ||
                ADV_MOUSE_MODE.equals(option.id) ||
                ADV_GYRO_AIM_SETTINGS.equals(option.id) ||
                ADV_CONTROLLER_KBM.equals(option.id) ||
                MENU_DUALSENSE_BRIDGE.equals(option.id) ||
                ADV_SEND_KEYS.equals(option.id) ||
                ADV_VOLUME_BUTTONS.equals(option.id);
    }

    private View createEditableMenuCard(MenuOption option, Runnable deleteAction,
                                        Runnable openAction,
                                        View.OnLongClickListener dragAction) {
        FrameLayout wrapper = new FrameLayout(getThemedContext());
        View card = createQuickMenuCard(option, () -> {});
        card.setOnClickListener(openAction == null ? null : view -> openAction.run());
        card.setFocusable(true);
        card.setId(View.generateViewId());
        if (dragAction != null) {
            card.setOnLongClickListener(dragAction);
        }
        wrapper.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageButton delete = new ImageButton(getThemedContext());
        delete.setImageResource(R.drawable.ic_qm_delete);
        delete.setColorFilter(Color.WHITE);
        delete.setBackground(roundedBackground(0xE6B32635, 18));
        delete.setPadding(dp(9), dp(9), dp(9), dp(9));
        delete.setContentDescription(getString(R.string.game_menu_edit_delete_item));
        delete.setOnClickListener(view -> deleteAction.run());
        delete.setFocusable(true);
        delete.setFocusableInTouchMode(true);
        delete.setId(View.generateViewId());
        delete.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) {
                hideControllerNavigationHint();
            }
            view.setBackground(roundedBackground(
                    hasFocus ? 0xFFFF4658 : 0xE6B32635, 18));
            view.setScaleX(hasFocus ? 1.14f : 1f);
            view.setScaleY(hasFocus ? 1.14f : 1f);
        });
        FrameLayout.LayoutParams deleteParams = new FrameLayout.LayoutParams(
                dp(38), dp(38), Gravity.TOP | Gravity.END);
        deleteParams.setMargins(0, dp(5), dp(5), 0);
        wrapper.addView(delete, deleteParams);

        return wrapper;
    }

    private void configureEditableGridControllerFocus(GridLayout grid) {
        int columns = grid.getColumnCount();
        int count = grid.getChildCount();
        for (int index = 0; index < count; index++) {
            View child = grid.getChildAt(index);
            if (!(child instanceof FrameLayout)) {
                continue;
            }
            FrameLayout wrapper = (FrameLayout) child;
            if (wrapper.getChildCount() < 2) {
                continue;
            }
            View card = wrapper.getChildAt(0);
            View delete = wrapper.getChildAt(1);

            // The delete action is deliberately outside normal grid traversal.
            // Up from a card always enters its own delete button.
            card.setNextFocusUpId(delete.getId());
            delete.setNextFocusDownId(card.getId());

            int aboveIndex = index - columns;
            if (aboveIndex >= 0) {
                FrameLayout above = (FrameLayout) grid.getChildAt(aboveIndex);
                delete.setNextFocusUpId(above.getChildAt(0).getId());
            }
            else {
                delete.setNextFocusUpId(delete.getId());
            }

            int belowIndex = index + columns;
            if (belowIndex < count) {
                FrameLayout below = (FrameLayout) grid.getChildAt(belowIndex);
                card.setNextFocusDownId(below.getChildAt(0).getId());
            }
            else {
                card.setNextFocusDownId(card.getId());
            }

            int column = index % columns;
            if (column > 0) {
                FrameLayout left = (FrameLayout) grid.getChildAt(index - 1);
                card.setNextFocusLeftId(left.getChildAt(0).getId());
            }
            if (column + 1 < columns && index + 1 < count) {
                FrameLayout right = (FrameLayout) grid.getChildAt(index + 1);
                card.setNextFocusRightId(right.getChildAt(0).getId());
            }

            // Left/right from a delete button returns to its owning card instead
            // of jumping unpredictably to another card's delete action.
            delete.setNextFocusLeftId(card.getId());
            delete.setNextFocusRightId(card.getId());
        }
    }

    private GridLayout createMenuGrid() {
        GridLayout grid = new GridLayout(getThemedContext());
        int widthDp = game.getResources().getConfiguration().screenWidthDp;
        grid.setColumnCount(widthDp >= 840 ? 4 : widthDp >= 560 ? 3 : 2);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        grid.setPadding(0, dp(4), 0, dp(20));
        return grid;
    }

    private GridLayout.LayoutParams createMenuCardLayoutParams() {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(128);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
        params.setMargins(dp(6), dp(6), dp(6), dp(6));
        return params;
    }

    private LinearLayout createFullscreenMenuShell(String title, View content,
                                                   View.OnClickListener editAction) {
        return createFullscreenMenuShell(title, content, editAction, null, null);
    }

    private LinearLayout createFullscreenMenuShell(String title, View content,
                                                   View.OnClickListener editAction,
                                                   Runnable backAction) {
        return createFullscreenMenuShell(title, content, editAction, backAction, null);
    }

    private LinearLayout createFullscreenMenuShell(String title, View content,
                                                   View.OnClickListener editAction,
                                                   Runnable backAction,
                                                   View headerActions) {
        controllerBackAction = backAction;
        boolean isQuickMenu = getString(R.string.quick_menu_title).equals(title);
        LinearLayout shell = new LinearLayout(getThemedContext());
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(14), dp(8), dp(14), dp(12));
        shell.setBackgroundColor(0xF2131720);

        LinearLayout header = new LinearLayout(getThemedContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, dp(2), dp(6));

        if (backAction != null) {
            ImageButton back = new ImageButton(getThemedContext());
            back.setImageResource(R.drawable.ic_qm_back);
            back.setColorFilter(Color.WHITE);
            back.setBackgroundColor(Color.TRANSPARENT);
            back.setContentDescription("Back");
            back.setOnClickListener(view -> {
                hideMenu();
                backAction.run();
            });
            applyControllerFocusStyle(back);
            header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }

        TextView titleView = new TextView(getThemedContext());
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(22);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout controllerVolumePanel = null;
        if (isQuickMenu) {
            TextView bridgeStatus = new TextView(getThemedContext());
            DualSenseInput bridgeInput = DualSenseBridge.getLatestInput();
            boolean wiredConnected = DualSenseController.hasActiveController();
            boolean bridgeConnected = DualSenseBridge.getControllerConnected();
            String bridgeLabel = wiredConnected ? "DualSense USB connected" :
                    bridgeConnected ? "DualSense Bridge connected" : "DualSense disconnected";
            if (wiredConnected && DualSenseController.getActiveBatteryPercent() >= 0) {
                bridgeLabel += "  -  " + DualSenseController.getActiveBatteryPercent() + "%";
            }
            else if (bridgeConnected &&
                    bridgeInput.getBatteryPercent() >= 0) {
                bridgeLabel += "  -  " + bridgeInput.getBatteryPercent() + "%";
            }
            bridgeStatus.setText(bridgeLabel);
            bridgeStatus.setTextColor(wiredConnected || bridgeConnected ?
                    0xFF62D995 : 0x99FFFFFF);
            bridgeStatus.setTextSize(13);
            bridgeStatus.setMaxLines(1);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.setMargins(dp(8), 0, dp(8), 0);
            header.addView(bridgeStatus, statusParams);

            controllerNavigationHint = new TextView(getThemedContext());
            controllerNavigationHint.setText(R.string.game_menu_controller_navigation_hint);
            controllerNavigationHint.setTextColor(0x99FFFFFF);
            controllerNavigationHint.setTextSize(13);
            controllerNavigationHint.setMaxLines(1);
            controllerNavigationHint.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            hintParams.setMargins(dp(8), 0, dp(8), 0);
            header.addView(controllerNavigationHint, hintParams);

            controllerVolumePanel = createControllerVolumePanel();
            LinearLayout finalControllerVolumePanel = controllerVolumePanel;
            ImageButton controllerVolumeButton = new ImageButton(getThemedContext());
            controllerVolumeButton.setImageResource(R.drawable.ic_qm_volume);
            controllerVolumeButton.setColorFilter(Color.WHITE);
            controllerVolumeButton.setBackgroundColor(Color.TRANSPARENT);
            controllerVolumeButton.setContentDescription(
                    getString(getDualSenseVolumeTitleRes()));
            controllerVolumeButton.setOnClickListener(view -> finalControllerVolumePanel.setVisibility(
                    finalControllerVolumePanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
            applyControllerFocusStyle(controllerVolumeButton);
            header.addView(controllerVolumeButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
            refreshControllerVolumePresentation(controllerVolumePanel, controllerVolumeButton);
        }
        else {
            controllerNavigationHint = null;
        }

        if (headerActions != null) {
            header.addView(headerActions);
        }

        if (editAction != null) {
            ImageButton edit = new ImageButton(getThemedContext());
            edit.setImageResource(quickMenuEditMode ?
                    R.drawable.ic_qm_check : R.drawable.ic_qm_edit);
            edit.setColorFilter(Color.WHITE);
            edit.setBackgroundColor(Color.TRANSPARENT);
            edit.setContentDescription(getString(R.string.game_menu_edit_quick_menu));
            edit.setOnClickListener(editAction);
            applyControllerFocusStyle(edit);
            header.addView(edit, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }

        ImageButton close = new ImageButton(getThemedContext());
        close.setImageResource(R.drawable.ic_qm_close);
        close.setColorFilter(Color.WHITE);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(getString(R.string.game_menu_cancel));
        close.setOnClickListener(view -> hideMenu());
        applyControllerFocusStyle(close);
        header.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

        shell.addView(header);
        if (controllerVolumePanel != null) {
            shell.addView(controllerVolumePanel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        shell.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return shell;
    }

    private LinearLayout createControllerVolumePanel() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(game);
        int initialVolume = Math.max(0, Math.min(100, preferences.getInt(
                PreferenceConfiguration.DUALSENSE_CONTROLLER_VOLUME_PREF_STRING,
                PreferenceConfiguration.DEFAULT_DUALSENSE_CONTROLLER_VOLUME)));

        LinearLayout panel = new LinearLayout(getThemedContext());
        panel.setVisibility(View.GONE);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(12), dp(4), dp(12), dp(10));
        panel.setBackground(roundedBackground(0xFF202A36, 12));

        TextView label = new TextView(getThemedContext());
        label.setText(getDualSenseVolumeTitleRes());
        label.setTextColor(Color.WHITE);
        label.setTextSize(14);
        panel.setTag(label);
        panel.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar slider = new SeekBar(getThemedContext());
        slider.setMax(100);
        slider.setProgress(initialVolume);
        slider.setKeyProgressIncrement(5);
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        sliderParams.setMargins(dp(12), 0, dp(8), 0);
        panel.addView(slider, sliderParams);

        TextView value = new TextView(getThemedContext());
        value.setText(initialVolume + "%");
        value.setTextColor(Color.WHITE);
        value.setTextSize(14);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        panel.addView(value, new LinearLayout.LayoutParams(dp(52), dp(44)));

        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(progress + "%");
                if (fromUser) {
                    DualSenseAudioBridge.setControllerVolume(progress);
                    preferences.edit().putInt(
                            PreferenceConfiguration.DUALSENSE_CONTROLLER_VOLUME_PREF_STRING,
                            progress).apply();
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        applyControllerFocusStyle(slider);
        return panel;
    }

    private void refreshControllerVolumePresentation(LinearLayout panel, ImageButton button) {
        Runnable updater = new Runnable() {
            @Override public void run() {
                int titleRes = getDualSenseVolumeTitleRes();
                button.setContentDescription(getString(titleRes));
                Object tag = panel.getTag();
                if (tag instanceof TextView) {
                    ((TextView) tag).setText(titleRes);
                }
                // The controller reports jack changes asynchronously. Keep this
                // lightweight polling confined to the visible Quick Menu.
                if (panel.isAttachedToWindow()) {
                    panel.postDelayed(this, 250L);
                }
            }
        };
        panel.post(updater);
    }

    private int getDualSenseVolumeTitleRes() {
        return (DualSenseController.hasActiveController() &&
                DualSenseController.getActiveHeadphonesConnected()) ||
                (DualSenseBridge.getControllerConnected() &&
                        DualSenseBridge.getHeadphonesConnected()) ?
                R.string.title_dualsense_headphone_volume :
                R.string.title_dualsense_controller_volume;
    }

    private void applyControllerFocusStyle(View view) {
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.setOnFocusChangeListener((focusedView, hasFocus) -> {
            if (hasFocus) {
                hideControllerNavigationHint();
            }
            focusedView.setBackground(hasFocus ?
                    roundedBackground(0xFF354352, 12) :
                    new ColorDrawable(Color.TRANSPARENT));
            focusedView.setScaleX(hasFocus ? 1.08f : 1f);
            focusedView.setScaleY(hasFocus ? 1.08f : 1f);
        });
    }

    private void hideControllerNavigationHint() {
        if (controllerNavigationHint != null) {
            controllerNavigationHint.setVisibility(View.GONE);
        }
    }

    private static final class VirtualKeyboardKey {
        final String label;
        final int keyCode;
        final float width;

        VirtualKeyboardKey(String label, int keyCode) {
            this(label, keyCode, 1.0f);
        }

        VirtualKeyboardKey(String label, int keyCode, float width) {
            this.label = label;
            this.keyCode = keyCode;
            this.width = width;
        }
    }

    private final class ControllerButtonIconView extends View {
        private final String source;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ControllerButtonIconView(String source) {
            super(getThemedContext());
            this.source = source;
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float centerX = width / 2f;
            float centerY = height / 2f;
            boolean playStation = isPlayStationKbmIconLayout();
            String label = getControllerButtonIconLabel(source, playStation);
            int color = getControllerButtonIconColor(source, playStation);

            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            if (isFaceButtonSource(source)) {
                canvas.drawCircle(centerX, centerY,
                        Math.min(width, height) * 0.42f, paint);
            }
            else {
                RectF bounds = new RectF(dp(2), dp(7),
                        width - dp(2), height - dp(7));
                canvas.drawRoundRect(bounds, dp(7), dp(7), paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(label.length() > 4 ? dp(9) :
                    label.length() > 2 ? dp(11) : dp(15));
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float baseline = centerY - (metrics.ascent + metrics.descent) / 2f;
            canvas.drawText(label, centerX, baseline, paint);
        }
    }

    private void setControllerSliderCaptured(SeekBar seekBar, boolean captured) {
        if (controllerCapturedSeekBar != null) {
            controllerCapturedSeekBar.setBackground(
                    new ColorDrawable(Color.TRANSPARENT));
        }
        controllerCapturedSeekBar = captured ? seekBar : null;
        if (controllerCapturedSeekBar != null) {
            hideControllerNavigationHint();
            controllerCapturedSeekBar.setBackground(
                    roundedBackground(0xAA3B536C, 12));
        }
    }

    private void adjustCapturedSlider(boolean increase) {
        if (controllerCapturedSeekBar == null) {
            return;
        }
        int keyCode = increase ?
                KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        long now = android.os.SystemClock.uptimeMillis();
        controllerCapturedSeekBar.onKeyDown(keyCode,
                new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        controllerCapturedSeekBar.onKeyUp(keyCode,
                new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private boolean isControllerEvent(KeyEvent event) {
        if (event == null) {
            return false;
        }
        int source = event.getSource();
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
                ControllerHandler.isGameControllerDevice(event.getDevice());
    }

    private boolean moveControllerFocus(View root, int direction) {
        View focused = root.findFocus();
        if (focused == null || focused == root) {
            ArrayList<View> focusables = new ArrayList<>();
            root.addFocusables(focusables, direction, View.FOCUSABLES_ALL);
            for (View focusable : focusables) {
                if (focusable != root && focusable.isShown() && focusable.isEnabled() &&
                        focusable.requestFocus()) {
                    hideControllerNavigationHint();
                    return true;
                }
            }
            return false;
        }
        View next = focused.focusSearch(direction);
        if (next == null || next == focused || !next.isShown() || !next.isEnabled()) {
            next = findBridgeDirectionalFocus(root, focused, direction);
        }
        boolean moved = next != null && next.requestFocus();
        if (moved) hideControllerNavigationHint();
        return moved;
    }

    private View findBridgeDirectionalFocus(View root, View focused, int direction) {
        ArrayList<View> candidates = new ArrayList<>();
        root.addFocusables(candidates, direction, View.FOCUSABLES_ALL);
        Rect from = new Rect();
        if (!focused.getGlobalVisibleRect(from)) {
            return null;
        }
        float fromX = from.exactCenterX();
        float fromY = from.exactCenterY();
        View best = null;
        float bestScore = Float.MAX_VALUE;
        for (View candidate : candidates) {
            if (candidate == focused || !candidate.isShown() || !candidate.isEnabled()) {
                continue;
            }
            Rect to = new Rect();
            if (!candidate.getGlobalVisibleRect(to)) {
                continue;
            }
            float dx = to.exactCenterX() - fromX;
            float dy = to.exactCenterY() - fromY;
            float primary;
            float secondary;
            switch (direction) {
                case View.FOCUS_LEFT:
                    if (dx >= -1) continue;
                    primary = -dx;
                    secondary = Math.abs(dy);
                    break;
                case View.FOCUS_RIGHT:
                    if (dx <= 1) continue;
                    primary = dx;
                    secondary = Math.abs(dy);
                    break;
                case View.FOCUS_UP:
                    if (dy >= -1) continue;
                    primary = -dy;
                    secondary = Math.abs(dx);
                    break;
                case View.FOCUS_DOWN:
                    if (dy <= 1) continue;
                    primary = dy;
                    secondary = Math.abs(dx);
                    break;
                default:
                    continue;
            }
            float score = primary * 4f + secondary;
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    @Override
    public void handleControllerState(int buttonFlags, float leftStickX, float leftStickY) {
        if (currentDialog == null || !currentDialog.isShowing() ||
                currentDialog.getWindow() == null) {
            bridgeLastButtonFlags = buttonFlags;
            bridgeStickCentered = true;
            return;
        }

        View root = currentDialog.getWindow().getDecorView();
        int changed = bridgeLastButtonFlags ^ buttonFlags;
        int pressed = changed & buttonFlags;
        int released = changed & ~buttonFlags;

        // The USB Bluetooth bridge is deliberately invisible to Android's InputManager.
        // Drive the menu directly from its raw state instead of synthesizing InputEvents.
        if ((pressed & ControllerPacket.LS_CLK_FLAG) != 0 && root.findFocus() == null) {
            moveControllerFocus(root, View.FOCUS_FORWARD);
        }

        if ((pressed & ControllerPacket.UP_FLAG) != 0) {
            moveBridgeControllerFocus(root, View.FOCUS_UP);
        }
        if ((pressed & ControllerPacket.DOWN_FLAG) != 0) {
            moveBridgeControllerFocus(root, View.FOCUS_DOWN);
        }
        if ((pressed & ControllerPacket.LEFT_FLAG) != 0) {
            moveBridgeControllerFocus(root, View.FOCUS_LEFT);
        }
        if ((pressed & ControllerPacket.RIGHT_FLAG) != 0) {
            moveBridgeControllerFocus(root, View.FOCUS_RIGHT);
        }

        if ((released & ControllerPacket.A_FLAG) != 0) {
            View focused = root.findFocus();
            if (focused == null) {
                moveControllerFocus(root, View.FOCUS_FORWARD);
            }
            else if (focused instanceof SeekBar) {
                setControllerSliderCaptured((SeekBar) focused,
                        controllerCapturedSeekBar != focused);
            }
            else {
                focused.performClick();
            }
        }
        if ((released & ControllerPacket.B_FLAG) != 0) {
            performBridgeBack(currentDialog);
        }
        if ((released & ControllerPacket.PLAY_FLAG) != 0) {
            hideMenu();
        }

        long now = android.os.SystemClock.uptimeMillis();
        int stickDirection = 0;
        if (Math.abs(leftStickX) > Math.abs(leftStickY) && Math.abs(leftStickX) > 0.65f) {
            stickDirection = leftStickX > 0 ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
        }
        else if (Math.abs(leftStickY) > 0.65f) {
            stickDirection = leftStickY > 0 ? View.FOCUS_DOWN : View.FOCUS_UP;
        }
        if (stickDirection == 0) {
            bridgeStickCentered = true;
        }
        else if (bridgeStickCentered || now - lastControllerNavigationTime >= 170) {
            bridgeStickCentered = false;
            lastControllerNavigationTime = now;
            moveBridgeControllerFocus(root, stickDirection);
        }
        bridgeLastButtonFlags = buttonFlags;
    }

    private void moveBridgeControllerFocus(View root, int direction) {
        if (controllerCapturedSeekBar != null &&
                (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) {
            adjustCapturedSlider(direction == View.FOCUS_RIGHT);
        }
        else {
            moveControllerFocus(root, direction);
        }
    }

    private void performBridgeBack(AlertDialog dialog) {
        Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (negative != null && negative.getVisibility() == View.VISIBLE) {
            negative.performClick();
        }
        else if (controllerBackAction != null) {
            Runnable backAction = controllerBackAction;
            dialog.dismiss();
            backAction.run();
        }
        else {
            dialog.dismiss();
        }
    }

    private void dispatchBridgeMenuButton(int changed, int buttonFlags,
                                          int mask, int keyCode) {
        if ((changed & mask) == 0) {
            return;
        }
        int action = (buttonFlags & mask) != 0 ?
                KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
        long now = android.os.SystemClock.uptimeMillis();
        currentDialog.dispatchKeyEvent(new KeyEvent(now, now, action, keyCode, 0,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                0, InputDevice.SOURCE_GAMEPAD));
    }

    private boolean handleControllerDialogKey(AlertDialog dialog, View root,
                                              int keyCode, KeyEvent event) {
        if (!isControllerEvent(event)) {
            return false;
        }

        if (controllerCapturedSeekBar != null &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                        keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                adjustCapturedSlider(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
            }
            return true;
        }

        boolean menuButton = keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                keyCode == KeyEvent.KEYCODE_BUTTON_START ||
                keyCode == KeyEvent.KEYCODE_BUTTON_MODE;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            // Activate on release so the corresponding key-up cannot leak to the host
            // after an action changes or closes the dialog.
            return menuButton;
        }
        if (event.getAction() != KeyEvent.ACTION_UP || !menuButton) {
            // D-pad events are intentionally left to Android's native focus handling.
            return false;
        }
        if (event.getRepeatCount() > 0) {
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A:
                View focused = root.findFocus();
                if (focused instanceof SeekBar) {
                    setControllerSliderCaptured((SeekBar) focused,
                            controllerCapturedSeekBar != focused);
                }
                else if (focused != null) {
                    focused.performClick();
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_B:
                Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (negative != null && negative.getVisibility() == View.VISIBLE) {
                    negative.performClick();
                }
                else if (controllerBackAction != null) {
                    Runnable backAction = controllerBackAction;
                    dialog.dismiss();
                    backAction.run();
                }
                else {
                    dialog.dismiss();
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_BUTTON_MODE:
                hideMenu();
                return true;

            default:
                return true;
        }
    }

    private boolean handleControllerDialogMotion(View root, MotionEvent event) {
        if (event == null ||
                (event.getSource() & InputDevice.SOURCE_JOYSTICK) !=
                        InputDevice.SOURCE_JOYSTICK ||
                event.getAction() != MotionEvent.ACTION_MOVE) {
            return false;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastControllerNavigationTime < 170) {
            return true;
        }

        float horizontal = Math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_X)) > 0.5f ?
                event.getAxisValue(MotionEvent.AXIS_HAT_X) :
                event.getAxisValue(MotionEvent.AXIS_X);
        float vertical = Math.abs(event.getAxisValue(MotionEvent.AXIS_HAT_Y)) > 0.5f ?
                event.getAxisValue(MotionEvent.AXIS_HAT_Y) :
                event.getAxisValue(MotionEvent.AXIS_Y);
        int direction = 0;
        if (Math.abs(horizontal) > Math.abs(vertical) && Math.abs(horizontal) > 0.65f) {
            direction = horizontal > 0 ? View.FOCUS_RIGHT : View.FOCUS_LEFT;
        }
        else if (Math.abs(vertical) > 0.65f) {
            direction = vertical > 0 ? View.FOCUS_DOWN : View.FOCUS_UP;
        }

        if (direction != 0) {
            lastControllerNavigationTime = now;
            if (controllerCapturedSeekBar != null) {
                if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) {
                    adjustCapturedSlider(direction == View.FOCUS_RIGHT);
                }
            }
            else {
                moveControllerFocus(root, direction);
            }
        }
        return true;
    }

    private void configureControllerNavigation(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        Window window = dialog.getWindow();
        View root = window.getDecorView();
        enableBridgeFocusInTouchMode(root);
        setControllerSliderCaptured(null, false);
        dialog.setOnKeyListener((ignored, keyCode, event) ->
                handleControllerDialogKey(dialog, root, keyCode, event));
        root.setOnGenericMotionListener(this::handleControllerDialogMotion);

        Callback originalCallback = window.getCallback();
        if (!(originalCallback instanceof ControllerMenuWindowCallback)) {
            window.setCallback(new ControllerMenuWindowCallback(
                    originalCallback, dialog, root));
        }

        root.post(() -> {
            enableBridgeFocusInTouchMode(root);
            ArrayList<View> focusables = new ArrayList<>();
            root.addFocusables(focusables, View.FOCUS_FORWARD,
                    View.FOCUSABLES_ALL);
            // Start on actual menu content rather than the close/edit icons in
            // the header. The Bridge cannot take Android out of touch mode.
            for (View focusable : focusables) {
                if ("controller_menu_card".equals(focusable.getTag()) &&
                        focusable.isShown() && focusable.isEnabled() &&
                        focusable.requestFocus()) {
                    hideControllerNavigationHint();
                    return;
                }
            }
            if (root.findFocus() == null) {
                for (View focusable : focusables) {
                    if (focusable.isShown() && focusable.isEnabled() &&
                            focusable.requestFocus()) {
                        break;
                    }
                }
            }
        });
    }

    private void enableBridgeFocusInTouchMode(View view) {
        if (view.isFocusable() && (view.isClickable() || view instanceof SeekBar ||
                view instanceof Spinner || view instanceof EditText)) {
            view.setFocusableInTouchMode(true);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                enableBridgeFocusInTouchMode(group.getChildAt(i));
            }
        }
    }

    private final class ControllerMenuWindowCallback extends WindowCallbackWrapper {
        private final AlertDialog dialog;
        private final View root;

        ControllerMenuWindowCallback(Callback wrapped, AlertDialog dialog, View root) {
            super(wrapped);
            this.dialog = dialog;
            this.root = root;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (isControllerEvent(event) &&
                    handleControllerDialogKey(dialog, root,
                            event.getKeyCode(), event)) {
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            if (handleControllerDialogMotion(root, event)) {
                return true;
            }
            return super.dispatchGenericMotionEvent(event);
        }
    }

    private void showControllerReadyDialog(AlertDialog dialog) {
        currentDialog = dialog;
        dialog.show();
        configureControllerNavigation(dialog);
    }

    private void cardifySettingsLayout(LinearLayout layout) {
        List<View> children = new ArrayList<>();
        for (int i = 0; i < layout.getChildCount(); i++) {
            children.add(layout.getChildAt(i));
        }
        layout.removeAllViews();
        layout.setPadding(dp(6), dp(4), dp(6), dp(12));

        for (int i = 0; i < children.size(); i++) {
            View child = children.get(i);
            LinearLayout card = new LinearLayout(getThemedContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(16), dp(10), dp(16), dp(10));
            card.setBackground(roundedBackground(0xCC202630, 16));

            if (child instanceof Switch) {
                Switch toggle = (Switch) child;
                toggle.setTextColor(Color.WHITE);
                toggle.setTextSize(15);
                card.addView(toggle, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            else if (child instanceof TextView && i + 1 < children.size() &&
                    (children.get(i + 1) instanceof SeekBar ||
                            children.get(i + 1) instanceof Spinner)) {
                TextView label = (TextView) child;
                View control = children.get(++i);
                label.setTextColor(Color.WHITE);
                label.setTextSize(15);
                card.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout.LayoutParams controlParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                controlParams.topMargin = dp(4);
                card.addView(control, controlParams);
            }
            else {
                if (child instanceof TextView) {
                    ((TextView) child).setTextColor(Color.WHITE);
                }
                card.addView(child, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.setMargins(dp(4), dp(5), dp(4), dp(5));
            layout.addView(card, cardParams);
        }
    }

    private void showFullscreenDialog(View content, Runnable onDismiss) {
        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        currentDialog = new AlertDialog.Builder(getThemedContext())
                .setView(content)
                .create();
        if (onDismiss != null) {
            currentDialog.setOnDismissListener(dialog -> onDismiss.run());
        }
        currentDialog.show();
        configureControllerNavigation(currentDialog);

        Window window = currentDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.getDecorView().setPadding(0, 0, 0, 0);
        }
    }

    private void showFullscreenSettings(int titleRes, View settingsContent,
                                        Runnable doneAction, Runnable backAction) {
        LinearLayout content = new LinearLayout(getThemedContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(settingsContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        Button done = new Button(getThemedContext());
        done.setText(R.string.game_menu_done);
        done.setTextColor(Color.WHITE);
        done.setTextSize(15);
        done.setAllCaps(false);
        done.setBackground(roundedBackground(0xFFF7A900, 14));
        done.setOnClickListener(view -> {
            hideMenu();
            doneAction.run();
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        doneParams.setMargins(dp(6), dp(12), dp(6), dp(4));
        content.addView(done, doneParams);

        LinearLayout shell = createFullscreenMenuShell(
                getString(titleRes), content, null, backAction);
        showFullscreenDialog(shell, null);
    }

    private void showQuickMenuCards(GameInputDevice device, List<MenuOption> visibleOptions,
                                    Set<String> hiddenIds) {
        showMenuCards(getString(R.string.quick_menu_title), visibleOptions, () -> {
            quickMenuEditMode = true;
            showMenu(device);
        });
    }

    private void showMenuCards(String title, List<MenuOption> visibleOptions,
                               Runnable editAction) {
        showMenuCards(title, visibleOptions, editAction, null);
    }

    private void showMenuCards(String title, List<MenuOption> visibleOptions,
                               Runnable editAction, Runnable backAction) {
        ScrollView scrollView = new ScrollView(getThemedContext());
        scrollView.setFillViewport(true);

        GridLayout grid = createMenuGrid();

        for (MenuOption option : visibleOptions) {
            if (MENU_CANCEL.equals(option.id)) {
                continue;
            }
            View card = createQuickMenuCard(option, () -> {
                hideMenu();
                run(option);
            });
            grid.addView(card, createMenuCardLayoutParams());
        }
        scrollView.addView(grid);

        LinearLayout shell = createFullscreenMenuShell(
                title, scrollView, editAction == null ? null : view -> editAction.run(),
                backAction);
        showFullscreenDialog(shell, null);
    }

    private LinearLayout createEditToolbar(Runnable restoreAction, Runnable resetAction) {
        LinearLayout toolbar = new LinearLayout(getThemedContext());
        toolbar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), 0, dp(6), dp(8));

        Button restore = new Button(getThemedContext());
        restore.setText(R.string.game_menu_edit_add_hidden);
        restore.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_qm_add, 0, 0, 0);
        restore.setCompoundDrawablePadding(dp(8));
        restore.setTextColor(Color.WHITE);
        restore.setAllCaps(false);
        restore.setBackground(roundedBackground(0xCC2A3440, 12));
        restore.setOnClickListener(view -> restoreAction.run());
        toolbar.addView(restore);

        Button reset = new Button(getThemedContext());
        reset.setText(R.string.game_menu_edit_reset);
        reset.setCompoundDrawablesWithIntrinsicBounds(
                R.drawable.ic_qm_restore, 0, 0, 0);
        reset.setCompoundDrawablePadding(dp(8));
        reset.setTextColor(Color.WHITE);
        reset.setAllCaps(false);
        reset.setBackground(roundedBackground(0xCC2A3440, 12));
        reset.setOnClickListener(view -> resetAction.run());
        LinearLayout.LayoutParams resetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetParams.leftMargin = dp(10);
        toolbar.addView(reset, resetParams);
        return toolbar;
    }

    private int findGridDropIndex(GridLayout grid, DragEvent event) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            View child = grid.getChildAt(i);
            if (event.getX() >= child.getLeft() && event.getX() <= child.getRight() &&
                    event.getY() >= child.getTop() && event.getY() <= child.getBottom()) {
                return i;
            }
        }
        return Math.max(0, grid.getChildCount() - 1);
    }

    private void showEditableQuickMenuCards(GameInputDevice device,
                                            List<MenuOption> allOptions,
                                            List<MenuOption> visibleOptions,
                                            Set<String> hiddenIds) {
        LinearLayout content = new LinearLayout(getThemedContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(createEditToolbar(
                () -> showRestoreQuickMenuCards(device, allOptions, visibleOptions, hiddenIds),
                () -> confirmResetQuickMenu(device)));

        ScrollView scroll = new ScrollView(getThemedContext());
        GridLayout grid = createMenuGrid();
        for (MenuOption option : visibleOptions) {
            if (MENU_CANCEL.equals(option.id)) {
                continue;
            }
            View wrapper = createEditableMenuCard(option, () -> {
                hiddenIds.add(option.id);
                visibleOptions.remove(option);
                saveQuickMenuState(visibleOptions, hiddenIds);
                showMenu(device);
            }, isEditableNavigationOption(option) ? () -> {
                saveQuickMenuState(visibleOptions, hiddenIds);
                hideMenu();
                run(option);
            } : null, view -> {
                draggedQuickMenuId = option.id;
                ClipData data = ClipData.newPlainText("quick_menu_item", option.id);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    view.startDragAndDrop(data, new View.DragShadowBuilder(view), null, 0);
                }
                else {
                    view.startDrag(data, new View.DragShadowBuilder(view), null, 0);
                }
                return true;
            });
            grid.addView(wrapper, createMenuCardLayoutParams());
        }
        configureEditableGridControllerFocus(grid);
        grid.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DROP && draggedQuickMenuId != null) {
                int from = findOptionIndex(visibleOptions, draggedQuickMenuId);
                int to = findGridDropIndex(grid, event);
                int cancel = findOptionIndex(visibleOptions, MENU_CANCEL);
                if (cancel >= 0) {
                    to = Math.min(to, cancel - 1);
                }
                if (from >= 0 && to >= 0 && from != to) {
                    MenuOption moved = visibleOptions.remove(from);
                    visibleOptions.add(Math.min(to, visibleOptions.size()), moved);
                    saveQuickMenuState(visibleOptions, hiddenIds);
                }
                draggedQuickMenuId = null;
                showMenu(device);
                return true;
            }
            if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                draggedQuickMenuId = null;
            }
            return event.getAction() == DragEvent.ACTION_DRAG_STARTED ||
                    event.getAction() == DragEvent.ACTION_DRAG_LOCATION ||
                    event.getAction() == DragEvent.ACTION_DRAG_ENDED;
        });
        scroll.addView(grid);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_edit_quick_menu), content, view -> {
                    quickMenuEditMode = false;
                    showMenu(device);
                });
        showFullscreenDialog(shell, () -> saveQuickMenuState(visibleOptions, hiddenIds));
    }

    private void showRestoreQuickMenuCards(GameInputDevice device,
                                           List<MenuOption> allOptions,
                                           List<MenuOption> visibleOptions,
                                           Set<String> hiddenIds) {
        List<MenuOption> hiddenOptions = new ArrayList<>();
        for (String id : DEFAULT_QUICK_MENU_ORDER) {
            if (hiddenIds.contains(id)) {
                MenuOption option = findOptionById(allOptions, id);
                if (option != null) {
                    hiddenOptions.add(option);
                }
            }
        }
        if (hiddenOptions.isEmpty()) {
            showMenu(device);
            return;
        }

        GridLayout grid = createMenuGrid();
        for (MenuOption option : hiddenOptions) {
            View card = createQuickMenuCard(option, () -> {
                hiddenIds.remove(option.id);
                if (findOptionById(visibleOptions, option.id) == null) {
                    addOptionInDefaultPlace(visibleOptions, option);
                }
                saveQuickMenuState(visibleOptions, hiddenIds);
                showRestoreQuickMenuCards(device, allOptions, visibleOptions, hiddenIds);
            });
            grid.addView(card, createMenuCardLayoutParams());
        }
        configureEditableGridControllerFocus(grid);
        ScrollView scroll = new ScrollView(getThemedContext());
        scroll.addView(grid);
        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_edit_add_hidden), scroll, null,
                () -> showMenu(device));
        showFullscreenDialog(shell, null);
    }

    private void showEditableSubmenuCards(String title, MenuOption[] allOptions,
                                          List<MenuOption> visibleOptions,
                                          Set<String> hiddenIds, Runnable backAction) {
        LinearLayout content = new LinearLayout(getThemedContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(createEditToolbar(
                () -> showRestoreSubmenuCards(
                        title, allOptions, visibleOptions, hiddenIds, backAction),
                () -> {
                    getMenuPrefs().edit().remove(submenuHiddenKey(title)).apply();
                    showMenuDialog(title, allOptions, backAction);
                }));

        GridLayout grid = createMenuGrid();
        for (MenuOption option : visibleOptions) {
            if (MENU_CANCEL.equals(option.id)) {
                continue;
            }
            View card = createEditableMenuCard(option, () -> {
                hiddenIds.add(option.id);
                visibleOptions.remove(option);
                saveHiddenSubmenuIds(title, hiddenIds);
                showMenuDialog(title, allOptions, backAction);
            }, isEditableNavigationOption(option) ? () -> {
                saveHiddenSubmenuIds(title, hiddenIds);
                hideMenu();
                run(option);
            } : null, null);
            grid.addView(card, createMenuCardLayoutParams());
        }
        ScrollView scroll = new ScrollView(getThemedContext());
        scroll.addView(grid);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_edit_quick_menu), content, view -> {
                    quickMenuEditMode = false;
                    showMenuDialog(title, allOptions, backAction);
                }, backAction);
        showFullscreenDialog(shell, () -> saveHiddenSubmenuIds(title, hiddenIds));
    }

    private void showRestoreSubmenuCards(String title, MenuOption[] allOptions,
                                         List<MenuOption> visibleOptions,
                                         Set<String> hiddenIds, Runnable backAction) {
        List<MenuOption> hiddenOptions = new ArrayList<>();
        for (MenuOption option : allOptions) {
            if (option.id != null && hiddenIds.contains(option.id)) {
                hiddenOptions.add(option);
            }
        }
        if (hiddenOptions.isEmpty()) {
            showMenuDialog(title, allOptions, backAction);
            return;
        }

        GridLayout grid = createMenuGrid();
        for (MenuOption option : hiddenOptions) {
            View card = createQuickMenuCard(option, () -> {
                hiddenIds.remove(option.id);
                if (findOptionById(visibleOptions, option.id) == null) {
                    addSubmenuOptionInDefaultPlace(allOptions, visibleOptions, option);
                }
                saveHiddenSubmenuIds(title, hiddenIds);
                showRestoreSubmenuCards(
                        title, allOptions, visibleOptions, hiddenIds, backAction);
            });
            grid.addView(card, createMenuCardLayoutParams());
        }
        ScrollView scroll = new ScrollView(getThemedContext());
        scroll.addView(grid);
        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_edit_add_hidden), scroll, null,
                () -> showMenuDialog(title, allOptions, backAction));
        showFullscreenDialog(shell, null);
    }

    private LinearLayout createHeader(String title, boolean showEditControls, Runnable addAction, Runnable resetAction) {
        LinearLayout header = new LinearLayout(getThemedContext());
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padding = (int) (12 * game.getResources().getDisplayMetrics().density);
        header.setPadding(padding, padding / 2, padding, padding / 2);

        TextView titleView = new TextView(getThemedContext());
        titleView.setText(title);
        titleView.setTextSize(20);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (showEditControls) {
            ImageButton resetButton = new ImageButton(getThemedContext());
            resetButton.setImageResource(android.R.drawable.ic_menu_revert);
            resetButton.setBackgroundColor(0x00000000);
            resetButton.setContentDescription(getString(R.string.game_menu_edit_reset));
            resetButton.setOnClickListener(v -> resetAction.run());
            header.addView(resetButton);

            ImageButton addButton = new ImageButton(getThemedContext());
            addButton.setImageResource(android.R.drawable.ic_input_add);
            addButton.setBackgroundColor(0x00000000);
            addButton.setContentDescription(getString(R.string.game_menu_edit_add_hidden));
            addButton.setOnClickListener(v -> addAction.run());
            header.addView(addButton);
        }

        ImageButton editButton = new ImageButton(getThemedContext());
        editButton.setImageResource(quickMenuEditMode ? android.R.drawable.ic_menu_close_clear_cancel : android.R.drawable.ic_menu_edit);
        editButton.setBackgroundColor(0x00000000);
        editButton.setContentDescription(quickMenuEditMode ? "Close edit mode" : getString(R.string.game_menu_edit_quick_menu));
        editButton.setOnClickListener(v -> {
            quickMenuEditMode = !quickMenuEditMode;
            showMenu(null);
        });
        header.addView(editButton);

        return header;
    }

    private ArrayAdapter<MenuOption> createMenuAdapter(List<MenuOption> options, boolean editable, Set<String> hiddenIds,
                                                       Runnable afterDelete) {
        return new ArrayAdapter<MenuOption>(getThemedContext(), android.R.layout.simple_list_item_1, options) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                MenuOption option = getItem(position);
                if (option == null) {
                    return super.getView(position, convertView, parent);
                }

                LinearLayout row = new LinearLayout(getThemedContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int rowPadding = (int) (12 * game.getResources().getDisplayMetrics().density);
                row.setPadding(rowPadding, rowPadding / 2, rowPadding, rowPadding / 2);
                if (editable && draggedQuickMenuId != null) {
                    if (draggedQuickMenuId.equals(option.id)) {
                        row.setAlpha(1.0f);
                        row.setBackgroundColor(0x22FFFFFF);
                    } else {
                        row.setAlpha(0.38f);
                    }
                }

                TextView label = new TextView(getThemedContext());
                label.setText(option.label);
                label.setTextSize(18);
                row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                if (editable && option.id != null && !MENU_CANCEL.equals(option.id)) {
                    ImageButton deleteButton = new ImageButton(getThemedContext());
                    deleteButton.setImageResource(android.R.drawable.ic_menu_delete);
                    deleteButton.setBackgroundColor(0x00000000);
                    applyControllerFocusStyle(deleteButton);
                    deleteButton.setContentDescription(getString(R.string.game_menu_edit_delete_item));
                    deleteButton.setOnClickListener(v -> {
                        hiddenIds.add(option.id);
                        options.remove(option);
                        afterDelete.run();
                    });
                    row.addView(deleteButton);
                }

                return row;
            }
        };
    }

    private void showRestoreSubmenuDialog(String title, MenuOption[] allOptions, List<MenuOption> visibleOptions,
                                          Set<String> hiddenIds, Runnable backAction) {
        List<MenuOption> hiddenOptions = new ArrayList<>();
        for (MenuOption option : allOptions) {
            if (option.id != null && hiddenIds.contains(option.id)) {
                hiddenOptions.add(option);
            }
        }

        if (hiddenOptions.isEmpty()) {
            Toast.makeText(game, R.string.game_menu_edit_nothing_hidden, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[hiddenOptions.size()];
        boolean[] checked = new boolean[hiddenOptions.size()];
        for (int i = 0; i < hiddenOptions.size(); i++) {
            labels[i] = hiddenOptions.get(i).label;
        }

        AlertDialog controllerDialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_edit_add_hidden)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    for (int i = 0; i < hiddenOptions.size(); i++) {
                        if (checked[i]) {
                            MenuOption option = hiddenOptions.get(i);
                            hiddenIds.remove(option.id);
                            if (findOptionById(visibleOptions, option.id) == null) {
                                addSubmenuOptionInDefaultPlace(allOptions, visibleOptions, option);
                            }
                        }
                    }
                    saveHiddenSubmenuIds(title, hiddenIds);
                    showMenuDialog(title, allOptions);
                })
                .setNegativeButton(R.string.game_menu_cancel, null)
                .create();
        showControllerReadyDialog(controllerDialog);
    }

    private void showMenuDialog(String title, MenuOption[] allOptions) {
        showMenuDialog(title, allOptions, null);
    }

    private void showMenuDialog(String title, MenuOption[] allOptions, Runnable backAction) {
        Set<String> hiddenIds = getHiddenSubmenuIds(title);
        List<MenuOption> visibleOptions = new ArrayList<>();
        for (MenuOption option : allOptions) {
            if (quickMenuEditMode && option.id != null && hiddenIds.contains(option.id)) {
                continue;
            }
            visibleOptions.add(option);
        }

        if (!quickMenuEditMode) {
            showMenuCards(title, visibleOptions, () -> {
                quickMenuEditMode = true;
                showMenuDialog(title, allOptions, backAction);
            }, backAction);
            return;
        }
        showEditableSubmenuCards(title, allOptions, visibleOptions, hiddenIds, backAction);
        return;

        /*
        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);

        boolean editableSubmenu = quickMenuEditMode;
        layout.addView(createHeader(title, editableSubmenu,
                () -> showRestoreSubmenuDialog(
                        title, allOptions, visibleOptions, hiddenIds, backAction),
                () -> {
                    getMenuPrefs().edit().remove(submenuHiddenKey(title)).apply();
                    showMenuDialog(title, allOptions, backAction);
                }));

        ListView listView = new ListView(getThemedContext());
        final ArrayAdapter<MenuOption>[] adapterRef = new ArrayAdapter[1];
        ArrayAdapter<MenuOption> adapter = createMenuAdapter(visibleOptions, editableSubmenu, hiddenIds, () -> {
            saveHiddenSubmenuIds(title, hiddenIds);
            refreshList(adapterRef[0], visibleOptions);
        });
        adapterRef[0] = adapter;
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, which, id) -> {
            MenuOption option = adapter.getItem(which);
            if (option == null) {
                return;
            }
            if (currentDialog != null) {
                currentDialog.dismiss();
                currentDialog = null;
            }
            run(option);
        });
        layout.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_edit_quick_menu), layout, view -> {
                    quickMenuEditMode = false;
                    showMenuDialog(title, allOptions, backAction);
                }, backAction);
        showFullscreenDialog(shell, null);
        */
    }

    private void showSpecialKeysMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();

        if (!PreferenceConfiguration.readPreferences(game).disableDefaultExtraKeys) {
            options.add(new MenuOption("keys_esc", getString(R.string.game_menu_send_keys_esc),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_ESCAPE})));
            options.add(new MenuOption("keys_f11", getString(R.string.game_menu_send_keys_f11),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_F11})));
            options.add(new MenuOption("keys_alt_f4", getString(R.string.game_menu_send_keys_alt_f4),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_F4})));
            options.add(new MenuOption("keys_alt_enter", getString(R.string.game_menu_send_keys_alt_enter),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_RETURN})));
            options.add(new MenuOption("keys_ctrl_v", getString(R.string.game_menu_send_keys_ctrl_v),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_V})));
            options.add(new MenuOption("keys_win", getString(R.string.game_menu_send_keys_win),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN})));
            options.add(new MenuOption("keys_win_d", getString(R.string.game_menu_send_keys_win_d),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_D})));
            options.add(new MenuOption("keys_win_g", getString(R.string.game_menu_send_keys_win_g),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_G})));
            options.add(new MenuOption("keys_ctrl_alt_tab", getString(R.string.game_menu_send_keys_ctrl_alt_tab),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_TAB})));
            options.add(new MenuOption("keys_shift_tab", getString(R.string.game_menu_send_keys_shift_tab),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_TAB})));
            options.add(new MenuOption("keys_win_shift_left", getString(R.string.game_menu_send_keys_win_shift_left),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_LEFT})));
            options.add(new MenuOption("keys_ctrl_alt_shift_f1", getString(R.string.game_menu_send_keys_ctrl_alt_shift_f1),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F1})));
            options.add(new MenuOption("keys_ctrl_alt_shift_f12", getString(R.string.game_menu_send_keys_ctrl_alt_shift_f12),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_F12})));
            options.add(new MenuOption("keys_alt_b", getString(R.string.game_menu_send_keys_alt_b),
                    () -> sendKeys(new short[]{KeyboardTranslator.VK_LWIN, KeyboardTranslator.VK_LMENU, KeyboardTranslator.VK_B})));
        }

        SharedPreferences preferences = game.getSharedPreferences(PREF_NAME, Activity.MODE_PRIVATE);
        String value = preferences.getString(KEY_NAME, "");
        if (!TextUtils.isEmpty(value)) {
            try {
                KeyConfigHelper.ShortcutFile shortcutFile = KeyConfigHelper.parseShortcutFile(value);
                if (shortcutFile != null && shortcutFile.data != null && !shortcutFile.data.isEmpty()) {
                    for (KeyConfigHelper.Shortcut sc : shortcutFile.data) {
                        short[] keyCodes = new short[sc.keys.size()];
                        for (int i = 0; i < sc.keys.size(); i++) {
                            String code = sc.keys.get(i);
                            int keycode;
                            if (code.startsWith("0x")) {
                                keycode = Integer.parseInt(code.substring(2), 16);
                            } else if (code.startsWith("VK_")) {
                                Field field = KeyMapper.class.getDeclaredField(code);
                                keycode = field.getInt(null);
                            } else {
                                throw new IllegalArgumentException("Unknown key code: " + code);
                            }
                            keyCodes[i] = (short) keycode;
                        }
                        options.add(new MenuOption("custom_" + sc.name, sc.name, () -> sendKeys(keyCodes)));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(game, getString(R.string.wrong_import_format), Toast.LENGTH_SHORT).show();
            }
        }

        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_send_keys),
                options.toArray(new MenuOption[0]), () -> showAdvancedMenu(device));
    }

    private TextView createGyroSensitivityRow(LinearLayout parent, SharedPreferences prefs,
                                              String label, String key, int defaultValue,
                                              boolean enabled) {
        TextView valueView = new TextView(getThemedContext());
        SeekBar seekBar = new SeekBar(getThemedContext());
        int currentValue = prefs.getInt(key, defaultValue);

        valueView.setText(label + ": " + currentValue + "%");
        valueView.setTextSize(16);
        seekBar.setMax(200);
        seekBar.setProgress(currentValue);
        seekBar.setEnabled(enabled);
        valueView.setEnabled(enabled);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                prefs.edit().putInt(key, progress).apply();
                valueView.setText(label + ": " + progress + "%");
                game.reloadGyroAimSettings();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        parent.addView(valueView);
        parent.addView(seekBar);
        return valueView;
    }

    private void showGyroAimSettingsMenu(GameInputDevice device) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(game);
        ScrollView scrollView = new ScrollView(getThemedContext());
        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (18 * game.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);
        scrollView.addView(layout);

        createGyroSensitivityRow(layout, prefs, getString(R.string.game_menu_gyro_aim_pitch),
                PreferenceConfiguration.GYRO_AIM_PITCH_SENSITIVITY_PREF_STRING,
                PreferenceConfiguration.DEFAULT_GYRO_AIM_PITCH_SENSITIVITY, true);
        createGyroSensitivityRow(layout, prefs, getString(R.string.game_menu_gyro_aim_side),
                PreferenceConfiguration.GYRO_AIM_SIDE_SENSITIVITY_PREF_STRING,
                PreferenceConfiguration.DEFAULT_GYRO_AIM_SIDE_SENSITIVITY, true);

        Switch linkSwitch = new Switch(getThemedContext());
        linkSwitch.setText(R.string.game_menu_gyro_aim_link_side_axes);
        linkSwitch.setChecked(prefs.getBoolean(PreferenceConfiguration.GYRO_AIM_LINK_SIDE_AXES_PREF_STRING,
                PreferenceConfiguration.DEFAULT_GYRO_AIM_LINK_SIDE_AXES));
        layout.addView(linkSwitch);

        TextView verticalLabel = createGyroSensitivityRow(layout, prefs, getString(R.string.game_menu_gyro_aim_vertical),
                PreferenceConfiguration.GYRO_AIM_VERTICAL_SENSITIVITY_PREF_STRING,
                PreferenceConfiguration.DEFAULT_GYRO_AIM_VERTICAL_SENSITIVITY, !linkSwitch.isChecked());
        SeekBar verticalSeekBar = (SeekBar) layout.getChildAt(layout.getChildCount() - 1);

        createGyroSensitivityRow(layout, prefs, getString(R.string.game_menu_gyro_aim_deadzone_compensation),
                PreferenceConfiguration.GYRO_AIM_DEADZONE_COMPENSATION_PREF_STRING,
                PreferenceConfiguration.DEFAULT_GYRO_AIM_DEADZONE_COMPENSATION, true);
        SeekBar deadzoneSeekBar = (SeekBar) layout.getChildAt(layout.getChildCount() - 1);
        deadzoneSeekBar.setMax(50);

        Switch smoothingSwitch = new Switch(getThemedContext());
        smoothingSwitch.setText(R.string.game_menu_gyro_smoothing);
        smoothingSwitch.setChecked(prefs.getBoolean(
                PreferenceConfiguration.GYRO_AIM_SMOOTHING_PREF_STRING,
                PreferenceConfiguration.DEFAULT_GYRO_AIM_SMOOTHING));
        smoothingSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(
                        PreferenceConfiguration.GYRO_AIM_SMOOTHING_PREF_STRING,
                        checked).apply());
        layout.addView(smoothingSwitch);

        Switch overlaySwitch = new Switch(getThemedContext());
        overlaySwitch.setText(R.string.game_menu_gyro_status_overlay);
        overlaySwitch.setChecked(prefs.getBoolean(
                PreferenceConfiguration.GYRO_AIM_STATUS_OVERLAY_PREF_STRING,
                PreferenceConfiguration.DEFAULT_GYRO_AIM_STATUS_OVERLAY));
        overlaySwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(
                        PreferenceConfiguration.GYRO_AIM_STATUS_OVERLAY_PREF_STRING,
                        checked).apply());
        layout.addView(overlaySwitch);

        ControllerKbmMapper mapper = game.getControllerKbmMapper();
        List<String> activationSources = mapper != null ?
                mapper.getStandardGyroActivationSources() : new ArrayList<>();
        addGyroActivationControls(layout, prefs, device, mapper, activationSources,
                PreferenceConfiguration.GYRO_AIM_ACTIVATION_MODE_PREF_STRING,
                PreferenceConfiguration.GYRO_AIM_ACTIVATION_SOURCES_PREF_STRING,
                PreferenceConfiguration.GYRO_AIM_ACTIVATION_SOURCE_PREF_STRING,
                PreferenceConfiguration.GYRO_AIM_HOLD_ACTIVATION_PREF_STRING,
                false);

        linkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PreferenceConfiguration.GYRO_AIM_LINK_SIDE_AXES_PREF_STRING, isChecked).apply();
            verticalSeekBar.setEnabled(!isChecked);
            verticalLabel.setEnabled(!isChecked);
            game.reloadGyroAimSettings();
        });

        cardifySettingsLayout(layout);
        showFullscreenSettings(R.string.game_menu_gyro_aim_settings,
                scrollView, () -> showAdvancedMenu(device),
                () -> showAdvancedMenu(device));
    }

    private void addGyroAxisMappingControls(LinearLayout layout, SharedPreferences prefs,
                                            String xKey, String yKey, String zKey) {
        TextView heading = new TextView(getThemedContext());
        heading.setText(R.string.game_menu_gyro_axis_mapping);
        heading.setTextSize(18);
        layout.addView(heading);
        addGyroAxisSpinner(layout, prefs, R.string.game_menu_gyro_axis_x, xKey, "x");
        addGyroAxisSpinner(layout, prefs, R.string.game_menu_gyro_axis_y, yKey, "y");
        addGyroAxisSpinner(layout, prefs, R.string.game_menu_gyro_axis_z, zKey, "z");
    }

    private void addGyroAxisSpinner(LinearLayout layout, SharedPreferences prefs,
                                    int labelId, String key, String defaultValue) {
        TextView label = new TextView(getThemedContext());
        label.setText(labelId);
        label.setTextSize(16);
        layout.addView(label);
        Spinner spinner = new Spinner(getThemedContext());
        String[] axes = { "X", "Y", "Z" };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getThemedContext(),
                android.R.layout.simple_spinner_item, axes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        String current = prefs.getString(key, defaultValue);
        spinner.setSelection("y".equals(current) ? 1 : "z".equals(current) ? 2 : 0);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                prefs.edit().putString(key, position == 1 ? "y" : position == 2 ? "z" : "x").apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        layout.addView(spinner);
    }

    private void showGyroAxisMappingMenu(GameInputDevice device) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(game);
        ScrollView scroll = new ScrollView(getThemedContext());
        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (18 * game.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);
        addGyroAxisMappingControls(layout, prefs,
                PreferenceConfiguration.GYRO_AXIS_X_PREF_STRING,
                PreferenceConfiguration.GYRO_AXIS_Y_PREF_STRING,
                PreferenceConfiguration.GYRO_AXIS_Z_PREF_STRING);
        addGyroAxisInvertSwitch(layout, prefs, R.string.game_menu_gyro_axis_invert_x,
                PreferenceConfiguration.GYRO_AXIS_INVERT_X_PREF_STRING);
        addGyroAxisInvertSwitch(layout, prefs, R.string.game_menu_gyro_axis_invert_y,
                PreferenceConfiguration.GYRO_AXIS_INVERT_Y_PREF_STRING);
        addGyroAxisInvertSwitch(layout, prefs, R.string.game_menu_gyro_axis_invert_z,
                PreferenceConfiguration.GYRO_AXIS_INVERT_Z_PREF_STRING);
        addGyroAxisInvertSwitch(layout, prefs, R.string.game_menu_gyro_axis_disable_x,
                PreferenceConfiguration.GYRO_AXIS_DISABLE_X_PREF_STRING);
        addGyroAxisInvertSwitch(layout, prefs, R.string.game_menu_gyro_axis_disable_y,
                PreferenceConfiguration.GYRO_AXIS_DISABLE_Y_PREF_STRING);
        addGyroAxisInvertSwitch(layout, prefs, R.string.game_menu_gyro_axis_disable_z,
                PreferenceConfiguration.GYRO_AXIS_DISABLE_Z_PREF_STRING);
        cardifySettingsLayout(layout);
        scroll.addView(layout);
        showFullscreenSettings(R.string.game_menu_gyro_axis_mapping, scroll,
                () -> showMenu(device), () -> showMenu(device));
    }

    private void addGyroAxisInvertSwitch(LinearLayout layout, SharedPreferences prefs,
                                         int labelId, String key) {
        Switch invert = new Switch(getThemedContext());
        invert.setText(labelId);
        invert.setChecked(prefs.getBoolean(key, false));
        invert.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(key, checked).apply());
        layout.addView(invert);
    }

    private boolean isPlayStationKbmIconLayout() {
        return CONTROLLER_KBM_ICON_LAYOUT_PLAYSTATION.equals(
                PreferenceManager.getDefaultSharedPreferences(game).getString(
                        CONTROLLER_KBM_ICON_LAYOUT_PREF,
                        CONTROLLER_KBM_ICON_LAYOUT_XBOX));
    }

    private boolean isFaceButtonSource(String source) {
        return ControllerKbmMapper.SOURCE_A.equals(source) ||
                ControllerKbmMapper.SOURCE_B.equals(source) ||
                ControllerKbmMapper.SOURCE_X.equals(source) ||
                ControllerKbmMapper.SOURCE_Y.equals(source);
    }

    private int getControllerButtonIconColor(String source, boolean playStation) {
        if (ControllerKbmMapper.SOURCE_A.equals(source)) {
            return playStation ? 0xFF5CA8E6 : 0xFF6CC24A;
        }
        if (ControllerKbmMapper.SOURCE_B.equals(source)) {
            return 0xFFE35D6A;
        }
        if (ControllerKbmMapper.SOURCE_X.equals(source)) {
            return playStation ? 0xFFE985C5 : 0xFF4EA5D9;
        }
        if (ControllerKbmMapper.SOURCE_Y.equals(source)) {
            return playStation ? 0xFF65C18C : 0xFFF2C94C;
        }
        return 0xFFF7B52C;
    }

    private String getControllerButtonIconLabel(String source, boolean playStation) {
        if (ControllerKbmMapper.SOURCE_A.equals(source)) return playStation ? "×" : "A";
        if (ControllerKbmMapper.SOURCE_B.equals(source)) return playStation ? "○" : "B";
        if (ControllerKbmMapper.SOURCE_X.equals(source)) return playStation ? "□" : "X";
        if (ControllerKbmMapper.SOURCE_Y.equals(source)) return playStation ? "△" : "Y";
        if (ControllerKbmMapper.SOURCE_DPAD_UP.equals(source)) return "↑";
        if (ControllerKbmMapper.SOURCE_DPAD_DOWN.equals(source)) return "↓";
        if (ControllerKbmMapper.SOURCE_DPAD_LEFT.equals(source)) return "←";
        if (ControllerKbmMapper.SOURCE_DPAD_RIGHT.equals(source)) return "→";
        if (ControllerKbmMapper.SOURCE_LB.equals(source)) return playStation ? "L1" : "LB";
        if (ControllerKbmMapper.SOURCE_RB.equals(source)) return playStation ? "R1" : "RB";
        if (ControllerKbmMapper.SOURCE_LT.equals(source)) return playStation ? "L2" : "LT";
        if (ControllerKbmMapper.SOURCE_RT.equals(source)) return playStation ? "R2" : "RT";
        if (ControllerKbmMapper.SOURCE_L3.equals(source)) return "L3";
        if (ControllerKbmMapper.SOURCE_R3.equals(source)) return "R3";
        if (ControllerKbmMapper.SOURCE_LEFT_STICK.equals(source)) return "LS";
        if (ControllerKbmMapper.SOURCE_RIGHT_STICK.equals(source)) return "RS";
        if (ControllerKbmMapper.SOURCE_START.equals(source)) return playStation ? "OPT" : "MENU";
        if (ControllerKbmMapper.SOURCE_SELECT.equals(source)) return playStation ? "CREATE" : "VIEW";
        if (ControllerKbmMapper.SOURCE_GUIDE.equals(source)) return playStation ? "PS" : "XBOX";
        if (ControllerKbmMapper.SOURCE_SHARE.equals(source)) return playStation ? "SHARE" : "CAP";
        if (ControllerKbmMapper.SOURCE_TOUCHPAD.equals(source)) return "PAD";
        if (ControllerKbmMapper.SOURCE_PADDLE_1.equals(source)) return "P1";
        if (ControllerKbmMapper.SOURCE_PADDLE_2.equals(source)) return "P2";
        if (ControllerKbmMapper.SOURCE_PADDLE_3.equals(source)) return "P3";
        if (ControllerKbmMapper.SOURCE_PADDLE_4.equals(source)) return "P4";
        if (source.startsWith("keycode_")) return "K" + source.substring("keycode_".length());
        if (source.startsWith("scancode_")) return "S" + source.substring("scancode_".length());
        return "?";
    }

    private String getControllerKbmSourceLabel(String source) {
        boolean playStation = isPlayStationKbmIconLayout();
        switch (source) {
            case ControllerKbmMapper.SOURCE_A: return playStation ? "Cross" : "A";
            case ControllerKbmMapper.SOURCE_B: return playStation ? "Circle" : "B";
            case ControllerKbmMapper.SOURCE_X: return playStation ? "Square" : "X";
            case ControllerKbmMapper.SOURCE_Y: return playStation ? "Triangle" : "Y";
            case ControllerKbmMapper.SOURCE_DPAD_UP: return "D-pad Up";
            case ControllerKbmMapper.SOURCE_DPAD_DOWN: return "D-pad Down";
            case ControllerKbmMapper.SOURCE_DPAD_LEFT: return "D-pad Left";
            case ControllerKbmMapper.SOURCE_DPAD_RIGHT: return "D-pad Right";
            case ControllerKbmMapper.SOURCE_LB: return playStation ? "L1" : "LB";
            case ControllerKbmMapper.SOURCE_RB: return playStation ? "R1" : "RB";
            case ControllerKbmMapper.SOURCE_L3: return "Left Stick Click";
            case ControllerKbmMapper.SOURCE_R3: return "Right Stick Click";
            case ControllerKbmMapper.SOURCE_START: return playStation ? "Options" : "Menu";
            case ControllerKbmMapper.SOURCE_SELECT: return playStation ? "Create" : "View";
            case ControllerKbmMapper.SOURCE_GUIDE: return playStation ? "PS" : "Xbox";
            case ControllerKbmMapper.SOURCE_SHARE: return playStation ? "Share" : "Capture";
            case ControllerKbmMapper.SOURCE_TOUCHPAD: return "Touchpad Click";
            case ControllerKbmMapper.SOURCE_PADDLE_1: return "Rear Paddle 1";
            case ControllerKbmMapper.SOURCE_PADDLE_2: return "Rear Paddle 2";
            case ControllerKbmMapper.SOURCE_PADDLE_3: return "Rear Paddle 3";
            case ControllerKbmMapper.SOURCE_PADDLE_4: return "Rear Paddle 4";
            case ControllerKbmMapper.SOURCE_LT: return playStation ? "L2" : "LT";
            case ControllerKbmMapper.SOURCE_RT: return playStation ? "R2" : "RT";
            case ControllerKbmMapper.SOURCE_LEFT_STICK: return "Left Stick";
            case ControllerKbmMapper.SOURCE_RIGHT_STICK: return "Right Stick";
            default:
                if (source.startsWith("keycode_")) {
                    try {
                        return KeyEvent.keyCodeToString(
                                Integer.parseInt(source.substring("keycode_".length())));
                    }
                    catch (NumberFormatException ignored) {}
                }
                if (source.startsWith("scancode_")) {
                    return "Controller scan code " + source.substring("scancode_".length());
                }
                return source;
        }
    }

    private Set<String> getGyroActivationSelection(SharedPreferences prefs,
                                                    String sourcesKey,
                                                    String legacySourceKey,
                                                    String legacyHoldKey) {
        Set<String> selected = new java.util.LinkedHashSet<>(
                prefs.getStringSet(sourcesKey, new java.util.LinkedHashSet<>()));
        if (!prefs.contains(sourcesKey) && prefs.getBoolean(legacyHoldKey, false)) {
            String legacySource = prefs.getString(legacySourceKey, "");
            if (!legacySource.isEmpty()) {
                selected.add(legacySource);
            }
        }
        return selected;
    }

    private void addGyroActivationControls(LinearLayout layout,
                                            SharedPreferences prefs,
                                            GameInputDevice device,
                                            ControllerKbmMapper mapper,
                                            List<String> availableSources,
                                            String modeKey,
                                            String sourcesKey,
                                            String legacySourceKey,
                                            String legacyHoldKey,
                                            boolean kbmMode) {
        Set<String> selectedSources = getGyroActivationSelection(
                prefs, sourcesKey, legacySourceKey, legacyHoldKey);
        selectedSources.retainAll(availableSources);

        String currentMode;
        if (prefs.contains(modeKey)) {
            currentMode = prefs.getString(modeKey,
                    ControllerKbmMapper.GYRO_ACTIVATION_ALWAYS);
        }
        else {
            currentMode = kbmMode &&
                    !prefs.getBoolean(ControllerKbmMapper.PREF_GYRO_ENABLED, false) ?
                    ControllerKbmMapper.GYRO_ACTIVATION_OFF :
                    prefs.getBoolean(legacyHoldKey, false) ?
                    ControllerKbmMapper.GYRO_ACTIVATION_HELD :
                    ControllerKbmMapper.GYRO_ACTIVATION_ALWAYS;
        }
        if (selectedSources.isEmpty() &&
                ControllerKbmMapper.GYRO_ACTIVATION_HELD.equals(currentMode)) {
            currentMode = ControllerKbmMapper.GYRO_ACTIVATION_ALWAYS;
            prefs.edit().putString(modeKey, currentMode).apply();
        }

        TextView modeLabel = new TextView(getThemedContext());
        modeLabel.setText(R.string.game_menu_gyro_activation_mode);
        modeLabel.setTextSize(16);
        layout.addView(modeLabel);

        List<String> modeLabels = new ArrayList<>();
        List<String> modeValues = new ArrayList<>();
        modeLabels.add(getString(R.string.game_menu_gyro_activation_off));
        modeValues.add(ControllerKbmMapper.GYRO_ACTIVATION_OFF);
        modeLabels.add(getString(R.string.game_menu_gyro_activation_always));
        modeValues.add(ControllerKbmMapper.GYRO_ACTIVATION_ALWAYS);
        if (!selectedSources.isEmpty()) {
            modeLabels.add(getString(R.string.game_menu_gyro_activation_held));
            modeValues.add(ControllerKbmMapper.GYRO_ACTIVATION_HELD);
        }

        Spinner modeSpinner = new Spinner(getThemedContext());
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                getThemedContext(), android.R.layout.simple_spinner_item, modeLabels);
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        int modeIndex = modeValues.indexOf(currentMode);
        modeSpinner.setSelection(Math.max(0, modeIndex));
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view,
                                       int position, long id) {
                String mode = modeValues.get(position);
                SharedPreferences.Editor editor = prefs.edit().putString(modeKey, mode);
                if (kbmMode) {
                    editor.putBoolean(ControllerKbmMapper.PREF_GYRO_ENABLED,
                            !ControllerKbmMapper.GYRO_ACTIVATION_OFF.equals(mode));
                }
                editor.apply();
                if (kbmMode) {
                    game.refreshControllerKbmGyro();
                }
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        layout.addView(modeSpinner);

        LinearLayout heading = new LinearLayout(getThemedContext());
        heading.setOrientation(LinearLayout.HORIZONTAL);
        TextView buttonsLabel = new TextView(getThemedContext());
        buttonsLabel.setText(R.string.game_menu_gyro_activation_buttons);
        buttonsLabel.setTextSize(16);
        buttonsLabel.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        heading.addView(buttonsLabel);

        Button addButton = new Button(getThemedContext());
        addButton.setText("+");
        heading.addView(addButton);
        layout.addView(heading);

        List<String> candidates = new ArrayList<>(availableSources);
        candidates.removeAll(selectedSources);
        addButton.setEnabled(!candidates.isEmpty());
        addButton.setOnClickListener(view -> {
            if (currentDialog != null) {
                currentDialog.dismiss();
            }
            String[] labels = new String[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                labels[i] = getControllerKbmSourceLabel(candidates.get(i));
            }
            currentDialog = new AlertDialog.Builder(getThemedContext())
                    .setTitle(R.string.game_menu_gyro_add_activation_button)
                    .setItems(labels, (dialog, which) -> {
                        Set<String> updated = getGyroActivationSelection(
                                prefs, sourcesKey, legacySourceKey, legacyHoldKey);
                        updated.add(candidates.get(which));
                        prefs.edit()
                                .putStringSet(sourcesKey, updated)
                                .remove(legacySourceKey)
                                .putBoolean(legacyHoldKey, false)
                                .apply();
                        if (kbmMode) {
                            showControllerKbmSettings(device, mapper);
                        }
                        else {
                            showGyroAimSettingsMenu(device);
                        }
                    })
                    .setNegativeButton(R.string.game_menu_cancel, (dialog, which) -> {
                        if (kbmMode) {
                            showControllerKbmSettings(device, mapper);
                        }
                        else {
                            showGyroAimSettingsMenu(device);
                        }
                    })
                    .create();
            currentDialog.show();
            configureControllerNavigation(currentDialog);
        });

        for (String source : selectedSources) {
            LinearLayout row = new LinearLayout(getThemedContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView sourceLabel = new TextView(getThemedContext());
            sourceLabel.setText(getControllerKbmSourceLabel(source));
            sourceLabel.setTextSize(16);
            sourceLabel.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            row.addView(sourceLabel);

            Button removeButton = new Button(getThemedContext());
            removeButton.setText("−");
            removeButton.setOnClickListener(view -> {
                Set<String> updated = getGyroActivationSelection(
                        prefs, sourcesKey, legacySourceKey, legacyHoldKey);
                updated.remove(source);
                SharedPreferences.Editor editor = prefs.edit()
                        .putStringSet(sourcesKey, updated)
                        .remove(legacySourceKey)
                        .putBoolean(legacyHoldKey, false);
                if (updated.isEmpty() && ControllerKbmMapper.GYRO_ACTIVATION_HELD.equals(
                        prefs.getString(modeKey,
                                ControllerKbmMapper.GYRO_ACTIVATION_ALWAYS))) {
                    editor.putString(modeKey, ControllerKbmMapper.GYRO_ACTIVATION_ALWAYS);
                }
                editor.apply();
                if (kbmMode) {
                    showControllerKbmSettings(device, mapper);
                }
                else {
                    showGyroAimSettingsMenu(device);
                }
            });
            row.addView(removeButton);
            layout.addView(row);
        }
    }

    private String getControllerKbmActionLabel(String action) {
        if (action == null || action.isEmpty()) {
            return getString(R.string.game_menu_controller_kbm_unassigned);
        }
        if (action.startsWith(ControllerKbmMapper.ACTION_DIRECTED_FLICK_PREFIX)) {
            String direction = ControllerKbmMapper.getDirectedFlickDirection(action);
            int distance = ControllerKbmMapper.getDirectedFlickDistance(action);
            return getString(R.string.game_menu_controller_kbm_directed_flick) +
                    " · " + getDirectedFlickDirectionLabel(direction) +
                    " · " + distance;
        }
        if (action.startsWith(ControllerKbmMapper.ACTION_KEY_PREFIX)) {
            try {
                int keyCode = Integer.parseInt(
                        action.substring(ControllerKbmMapper.ACTION_KEY_PREFIX.length()));
                return KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "");
            }
            catch (NumberFormatException ignored) {
                return getString(R.string.game_menu_controller_kbm_unassigned);
            }
        }
        switch (action) {
            case ControllerKbmMapper.ACTION_MOUSE_LEFT:
                return getString(R.string.game_menu_controller_kbm_mouse_left);
            case ControllerKbmMapper.ACTION_MOUSE_RIGHT:
                return getString(R.string.game_menu_controller_kbm_mouse_right);
            case ControllerKbmMapper.ACTION_MOUSE_MIDDLE:
                return getString(R.string.game_menu_controller_kbm_mouse_middle);
            case ControllerKbmMapper.ACTION_MOUSE_BACK:
                return getString(R.string.game_menu_controller_kbm_mouse_back);
            case ControllerKbmMapper.ACTION_MOUSE_FORWARD:
                return getString(R.string.game_menu_controller_kbm_mouse_forward);
            case ControllerKbmMapper.ACTION_WHEEL_UP:
                return getString(R.string.game_menu_controller_kbm_wheel_up);
            case ControllerKbmMapper.ACTION_WHEEL_DOWN:
                return getString(R.string.game_menu_controller_kbm_wheel_down);
            case ControllerKbmMapper.ACTION_MOUSE_MOVE:
                return getString(R.string.game_menu_controller_kbm_mouse_move);
            case ControllerKbmMapper.ACTION_SCROLL:
                return getString(R.string.game_menu_controller_kbm_scroll);
            case ControllerKbmMapper.ACTION_BASIC_WASD:
                return getString(R.string.game_menu_controller_kbm_basic_wasd);
            case ControllerKbmMapper.ACTION_BASIC_ARROWS:
                return getString(R.string.game_menu_controller_kbm_basic_arrows);
            default:
                return action;
        }
    }

    private void showControllerKbmKeyboardCapture(GameInputDevice device,
                                                   ControllerKbmMapper mapper, String source) {
        LinearLayout page = new LinearLayout(getThemedContext());
        boolean wideLayout =
                game.getResources().getConfiguration().screenWidthDp >= 600;
        page.setOrientation(wideLayout ?
                LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.setPadding(dp(6), dp(4), dp(6), dp(8));

        LinearLayout instructions = new LinearLayout(getThemedContext());
        instructions.setOrientation(LinearLayout.VERTICAL);
        instructions.setPadding(dp(16), dp(14), dp(16), dp(14));
        instructions.setBackground(roundedBackground(0xCC202630, 16));

        TextView instructionText = new TextView(getThemedContext());
        instructionText.setText(R.string.game_menu_controller_kbm_keyboard_picker_instructions);
        instructionText.setTextColor(0xFFE3E7ED);
        instructionText.setTextSize(15);
        instructions.addView(instructionText);

        EditText input = new EditText(getThemedContext());
        input.setFocusableInTouchMode(true);
        input.setHint(R.string.game_menu_controller_kbm_press_keyboard_key);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0x88FFFFFF);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = dp(12);
        instructions.addView(input, inputParams);

        LinearLayout keyboard = new LinearLayout(getThemedContext());
        keyboard.setOrientation(LinearLayout.VERTICAL);
        keyboard.setPadding(dp(8), dp(4), dp(8), dp(12));

        final boolean[] captured = { false };

        input.setOnKeyListener((view, keyCode, event) -> {
            if (isControllerEvent(event)) {
                return false;
            }
            if (captured[0] || event.getAction() != KeyEvent.ACTION_DOWN ||
                    keyCode == KeyEvent.KEYCODE_UNKNOWN) {
                return false;
            }
            captured[0] = true;
            mapper.setAction(source, ControllerKbmMapper.ACTION_KEY_PREFIX + keyCode);
            hideMenu();
            showControllerKbmMenu(device);
            return true;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable text) {
                if (captured[0] || text.length() == 0) {
                    return;
                }

                // Soft keyboards usually commit text directly into EditText and only emit a
                // KeyEvent for the final Enter. Convert the first committed character back to
                // the Android key that would produce it, and finish before Enter can overwrite it.
                KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                        .getEvents(new char[] { text.charAt(0) });
                if (events == null) {
                    return;
                }
                for (KeyEvent event : events) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN &&
                            !KeyEvent.isModifierKey(event.getKeyCode())) {
                        captured[0] = true;
                        mapper.setAction(source, ControllerKbmMapper.ACTION_KEY_PREFIX +
                                event.getKeyCode());
                        hideMenu();
                        showControllerKbmMenu(device);
                        return;
                    }
                }
            }
        });

        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("Esc", KeyEvent.KEYCODE_ESCAPE),
                new VirtualKeyboardKey("F1", KeyEvent.KEYCODE_F1),
                new VirtualKeyboardKey("F2", KeyEvent.KEYCODE_F2),
                new VirtualKeyboardKey("F3", KeyEvent.KEYCODE_F3),
                new VirtualKeyboardKey("F4", KeyEvent.KEYCODE_F4),
                new VirtualKeyboardKey("F5", KeyEvent.KEYCODE_F5),
                new VirtualKeyboardKey("F6", KeyEvent.KEYCODE_F6),
                new VirtualKeyboardKey("F7", KeyEvent.KEYCODE_F7),
                new VirtualKeyboardKey("F8", KeyEvent.KEYCODE_F8),
                new VirtualKeyboardKey("F9", KeyEvent.KEYCODE_F9),
                new VirtualKeyboardKey("F10", KeyEvent.KEYCODE_F10),
                new VirtualKeyboardKey("F11", KeyEvent.KEYCODE_F11),
                new VirtualKeyboardKey("F12", KeyEvent.KEYCODE_F12));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("Print", KeyEvent.KEYCODE_SYSRQ),
                new VirtualKeyboardKey("Scroll", KeyEvent.KEYCODE_SCROLL_LOCK),
                new VirtualKeyboardKey("Pause", KeyEvent.KEYCODE_BREAK),
                new VirtualKeyboardKey("Insert", KeyEvent.KEYCODE_INSERT),
                new VirtualKeyboardKey("Home", KeyEvent.KEYCODE_MOVE_HOME),
                new VirtualKeyboardKey("PgUp", KeyEvent.KEYCODE_PAGE_UP),
                new VirtualKeyboardKey("Delete", KeyEvent.KEYCODE_FORWARD_DEL),
                new VirtualKeyboardKey("End", KeyEvent.KEYCODE_MOVE_END),
                new VirtualKeyboardKey("PgDn", KeyEvent.KEYCODE_PAGE_DOWN));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("`", KeyEvent.KEYCODE_GRAVE),
                new VirtualKeyboardKey("1", KeyEvent.KEYCODE_1),
                new VirtualKeyboardKey("2", KeyEvent.KEYCODE_2),
                new VirtualKeyboardKey("3", KeyEvent.KEYCODE_3),
                new VirtualKeyboardKey("4", KeyEvent.KEYCODE_4),
                new VirtualKeyboardKey("5", KeyEvent.KEYCODE_5),
                new VirtualKeyboardKey("6", KeyEvent.KEYCODE_6),
                new VirtualKeyboardKey("7", KeyEvent.KEYCODE_7),
                new VirtualKeyboardKey("8", KeyEvent.KEYCODE_8),
                new VirtualKeyboardKey("9", KeyEvent.KEYCODE_9),
                new VirtualKeyboardKey("0", KeyEvent.KEYCODE_0),
                new VirtualKeyboardKey("-", KeyEvent.KEYCODE_MINUS),
                new VirtualKeyboardKey("=", KeyEvent.KEYCODE_EQUALS),
                new VirtualKeyboardKey("Backspace", KeyEvent.KEYCODE_DEL, 1.8f));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("Tab", KeyEvent.KEYCODE_TAB, 1.5f),
                key("Q"), key("W"), key("E"), key("R"), key("T"), key("Y"),
                key("U"), key("I"), key("O"), key("P"),
                new VirtualKeyboardKey("[", KeyEvent.KEYCODE_LEFT_BRACKET),
                new VirtualKeyboardKey("]", KeyEvent.KEYCODE_RIGHT_BRACKET),
                new VirtualKeyboardKey("\\", KeyEvent.KEYCODE_BACKSLASH, 1.4f));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 1.8f),
                key("A"), key("S"), key("D"), key("F"), key("G"), key("H"),
                key("J"), key("K"), key("L"),
                new VirtualKeyboardKey(";", KeyEvent.KEYCODE_SEMICOLON),
                new VirtualKeyboardKey("'", KeyEvent.KEYCODE_APOSTROPHE),
                new VirtualKeyboardKey("Enter", KeyEvent.KEYCODE_ENTER, 2.0f));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("L Shift", KeyEvent.KEYCODE_SHIFT_LEFT, 2.2f),
                key("Z"), key("X"), key("C"), key("V"), key("B"), key("N"), key("M"),
                new VirtualKeyboardKey(",", KeyEvent.KEYCODE_COMMA),
                new VirtualKeyboardKey(".", KeyEvent.KEYCODE_PERIOD),
                new VirtualKeyboardKey("/", KeyEvent.KEYCODE_SLASH),
                new VirtualKeyboardKey("R Shift", KeyEvent.KEYCODE_SHIFT_RIGHT, 2.2f));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("L Ctrl", KeyEvent.KEYCODE_CTRL_LEFT, 1.4f),
                new VirtualKeyboardKey("L Win", KeyEvent.KEYCODE_META_LEFT, 1.4f),
                new VirtualKeyboardKey("L Alt", KeyEvent.KEYCODE_ALT_LEFT, 1.4f),
                new VirtualKeyboardKey("Space", KeyEvent.KEYCODE_SPACE, 5.0f),
                new VirtualKeyboardKey("R Alt", KeyEvent.KEYCODE_ALT_RIGHT, 1.4f),
                new VirtualKeyboardKey("R Win", KeyEvent.KEYCODE_META_RIGHT, 1.4f),
                new VirtualKeyboardKey("Menu", KeyEvent.KEYCODE_MENU, 1.4f),
                new VirtualKeyboardKey("R Ctrl", KeyEvent.KEYCODE_CTRL_RIGHT, 1.4f));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("←", KeyEvent.KEYCODE_DPAD_LEFT),
                new VirtualKeyboardKey("↑", KeyEvent.KEYCODE_DPAD_UP),
                new VirtualKeyboardKey("↓", KeyEvent.KEYCODE_DPAD_DOWN),
                new VirtualKeyboardKey("→", KeyEvent.KEYCODE_DPAD_RIGHT));

        TextView numpadTitle = new TextView(getThemedContext());
        numpadTitle.setText("Numpad");
        numpadTitle.setTextColor(0x99FFFFFF);
        numpadTitle.setTextSize(13);
        numpadTitle.setPadding(dp(4), dp(8), dp(4), dp(2));
        keyboard.addView(numpadTitle);
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("Num", KeyEvent.KEYCODE_NUM_LOCK),
                new VirtualKeyboardKey("/", KeyEvent.KEYCODE_NUMPAD_DIVIDE),
                new VirtualKeyboardKey("*", KeyEvent.KEYCODE_NUMPAD_MULTIPLY),
                new VirtualKeyboardKey("-", KeyEvent.KEYCODE_NUMPAD_SUBTRACT),
                new VirtualKeyboardKey("7", KeyEvent.KEYCODE_NUMPAD_7),
                new VirtualKeyboardKey("8", KeyEvent.KEYCODE_NUMPAD_8),
                new VirtualKeyboardKey("9", KeyEvent.KEYCODE_NUMPAD_9),
                new VirtualKeyboardKey("+", KeyEvent.KEYCODE_NUMPAD_ADD));
        addVirtualKeyboardRow(keyboard, device, mapper, source,
                new VirtualKeyboardKey("4", KeyEvent.KEYCODE_NUMPAD_4),
                new VirtualKeyboardKey("5", KeyEvent.KEYCODE_NUMPAD_5),
                new VirtualKeyboardKey("6", KeyEvent.KEYCODE_NUMPAD_6),
                new VirtualKeyboardKey("1", KeyEvent.KEYCODE_NUMPAD_1),
                new VirtualKeyboardKey("2", KeyEvent.KEYCODE_NUMPAD_2),
                new VirtualKeyboardKey("3", KeyEvent.KEYCODE_NUMPAD_3),
                new VirtualKeyboardKey("0", KeyEvent.KEYCODE_NUMPAD_0, 2.0f),
                new VirtualKeyboardKey(".", KeyEvent.KEYCODE_NUMPAD_DOT));

        ScrollView keyboardScroll = new ScrollView(getThemedContext());
        keyboardScroll.addView(keyboard);
        if (wideLayout) {
            LinearLayout.LayoutParams leftParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.34f);
            leftParams.setMargins(dp(4), dp(4), dp(6), dp(4));
            page.addView(instructions, leftParams);
            LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.66f);
            rightParams.setMargins(dp(6), 0, 0, 0);
            page.addView(keyboardScroll, rightParams);
        }
        else {
            page.addView(instructions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            page.addView(keyboardScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }

        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_controller_kbm_keyboard_key),
                page, null,
                () -> showControllerKbmActionPicker(device, mapper, source));
        showFullscreenDialog(shell, null);
    }

    private VirtualKeyboardKey key(String label) {
        return new VirtualKeyboardKey(label,
                KeyEvent.KEYCODE_A + (label.charAt(0) - 'A'));
    }

    private void addVirtualKeyboardRow(LinearLayout keyboard,
                                       GameInputDevice device,
                                       ControllerKbmMapper mapper,
                                       String source,
                                       VirtualKeyboardKey... keys) {
        LinearLayout row = new LinearLayout(getThemedContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (VirtualKeyboardKey key : keys) {
            Button button = new Button(getThemedContext());
            button.setText(key.label);
            button.setTextColor(Color.WHITE);
            button.setTextSize(11);
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(3), 0, dp(3), 0);
            button.setBackground(roundedBackground(0xCC2A3440, 9));
            button.setOnClickListener(view -> {
                mapper.setAction(source,
                        ControllerKbmMapper.ACTION_KEY_PREFIX + key.keyCode);
                hideMenu();
                showControllerKbmMenu(device);
            });
            button.setOnFocusChangeListener((view, hasFocus) -> {
                view.setBackground(roundedBackground(
                        hasFocus ? 0xFFF7A900 : 0xCC2A3440, 9));
                view.setScaleX(hasFocus ? 1.04f : 1f);
                view.setScaleY(hasFocus ? 1.04f : 1f);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, dp(44), key.width);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(button, params);
        }
        keyboard.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showControllerKbmActionPicker(GameInputDevice device,
                                                ControllerKbmMapper mapper, String source) {
        final String[] labels;
        final String[] actions;
        if (mapper.isAxisSource(source)) {
            labels = new String[] {
                    getString(R.string.game_menu_controller_kbm_unassigned),
                    getString(R.string.game_menu_controller_kbm_mouse_move),
                    getString(R.string.game_menu_controller_kbm_scroll),
                    getString(R.string.game_menu_controller_kbm_basic_wasd),
                    getString(R.string.game_menu_controller_kbm_basic_arrows)
            };
            actions = new String[] {
                    ControllerKbmMapper.ACTION_UNASSIGNED,
                    ControllerKbmMapper.ACTION_MOUSE_MOVE,
                    ControllerKbmMapper.ACTION_SCROLL,
                    ControllerKbmMapper.ACTION_BASIC_WASD,
                    ControllerKbmMapper.ACTION_BASIC_ARROWS
            };
        }
        else {
            labels = new String[] {
                    getString(R.string.game_menu_controller_kbm_unassigned),
                    getString(R.string.game_menu_controller_kbm_keyboard_key),
                    getString(R.string.game_menu_controller_kbm_key_shift),
                    getString(R.string.game_menu_controller_kbm_key_ctrl),
                    getString(R.string.game_menu_controller_kbm_key_alt),
                    getString(R.string.game_menu_controller_kbm_key_space),
                    getString(R.string.game_menu_controller_kbm_key_tab),
                    getString(R.string.game_menu_controller_kbm_key_escape),
                    getString(R.string.game_menu_controller_kbm_key_enter),
                    getString(R.string.game_menu_controller_kbm_key_backspace),
                    getString(R.string.game_menu_controller_kbm_key_caps_lock),
                    getString(R.string.game_menu_controller_kbm_key_windows),
                    getString(R.string.game_menu_controller_kbm_key_delete),
                    getString(R.string.game_menu_controller_kbm_key_insert),
                    getString(R.string.game_menu_controller_kbm_key_home),
                    getString(R.string.game_menu_controller_kbm_key_end),
                    getString(R.string.game_menu_controller_kbm_key_page_up),
                    getString(R.string.game_menu_controller_kbm_key_page_down),
                    getString(R.string.game_menu_controller_kbm_function_keys),
                    getString(R.string.game_menu_controller_kbm_directed_flick),
                    getString(R.string.game_menu_controller_kbm_mouse_left),
                    getString(R.string.game_menu_controller_kbm_mouse_right),
                    getString(R.string.game_menu_controller_kbm_mouse_middle),
                    getString(R.string.game_menu_controller_kbm_mouse_back),
                    getString(R.string.game_menu_controller_kbm_mouse_forward),
                    getString(R.string.game_menu_controller_kbm_wheel_up),
                    getString(R.string.game_menu_controller_kbm_wheel_down)
            };
            actions = new String[] {
                    ControllerKbmMapper.ACTION_UNASSIGNED,
                    ControllerKbmMapper.ACTION_KEY_PREFIX,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_SHIFT_LEFT,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_CTRL_LEFT,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_ALT_LEFT,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_SPACE,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_TAB,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_ESCAPE,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_ENTER,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_DEL,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_CAPS_LOCK,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_META_LEFT,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_FORWARD_DEL,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_INSERT,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_MOVE_HOME,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_MOVE_END,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_PAGE_UP,
                    ControllerKbmMapper.ACTION_KEY_PREFIX + KeyEvent.KEYCODE_PAGE_DOWN,
                    CONTROLLER_KBM_PICK_FUNCTION_KEY,
                    CONTROLLER_KBM_CONFIGURE_FLICK,
                    ControllerKbmMapper.ACTION_MOUSE_LEFT,
                    ControllerKbmMapper.ACTION_MOUSE_RIGHT,
                    ControllerKbmMapper.ACTION_MOUSE_MIDDLE,
                    ControllerKbmMapper.ACTION_MOUSE_BACK,
                    ControllerKbmMapper.ACTION_MOUSE_FORWARD,
                    ControllerKbmMapper.ACTION_WHEEL_UP,
                    ControllerKbmMapper.ACTION_WHEEL_DOWN
            };
        }

        ScrollView scrollView = new ScrollView(getThemedContext());
        GridLayout grid = createMenuGrid();
        for (int i = 0; i < labels.length; i++) {
            final int choice = i;
            String id = actions[i].isEmpty() ? "kbm_action_unassigned" :
                    "kbm_action_" + actions[i].replace(':', '_');
            MenuOption option = new MenuOption(id, labels[i], () -> {});
            View card = createQuickMenuCard(option, () -> {
                hideMenu();
                if (ControllerKbmMapper.ACTION_KEY_PREFIX.equals(actions[choice])) {
                    showControllerKbmKeyboardCapture(device, mapper, source);
                }
                else if (CONTROLLER_KBM_PICK_FUNCTION_KEY.equals(actions[choice])) {
                    showControllerKbmFunctionKeyPicker(device, mapper, source);
                }
                else if (CONTROLLER_KBM_CONFIGURE_FLICK.equals(actions[choice])) {
                    showControllerKbmDirectedFlickEditor(device, mapper, source);
                }
                else {
                    mapper.setAction(source, actions[choice]);
                    showControllerKbmMenu(device);
                }
            });
            grid.addView(card, createMenuCardLayoutParams());
        }
        scrollView.addView(grid);
        LinearLayout shell = createFullscreenMenuShell(
                getControllerKbmSourceLabel(source), scrollView, null,
                () -> showControllerKbmMenu(device));
        showFullscreenDialog(shell, null);
    }

    private String getDirectedFlickDirectionLabel(String direction) {
        if (ControllerKbmMapper.FLICK_LEFT.equals(direction)) {
            return getString(R.string.game_menu_controller_kbm_flick_left);
        }
        if (ControllerKbmMapper.FLICK_UP.equals(direction)) {
            return getString(R.string.game_menu_controller_kbm_flick_up);
        }
        if (ControllerKbmMapper.FLICK_DOWN.equals(direction)) {
            return getString(R.string.game_menu_controller_kbm_flick_down);
        }
        return getString(R.string.game_menu_controller_kbm_flick_right);
    }

    private void updateDirectedFlickDirectionButtons(List<Button> buttons,
                                                     String selectedDirection) {
        for (Button button : buttons) {
            boolean selected = selectedDirection.equals(button.getTag());
            button.setBackground(roundedBackground(
                    selected ? 0xFFF7A900 : 0xCC2A3440, 13));
        }
    }

    private void showControllerKbmDirectedFlickEditor(GameInputDevice device,
                                                       ControllerKbmMapper mapper,
                                                       String source) {
        String currentAction = mapper.getAction(source);
        final String[] direction = {
                ControllerKbmMapper.getDirectedFlickDirection(currentAction)
        };
        int currentDistance =
                ControllerKbmMapper.getDirectedFlickDistance(currentAction);

        LinearLayout content = new LinearLayout(getThemedContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(6), dp(10), dp(10));

        LinearLayout directionCard = new LinearLayout(getThemedContext());
        directionCard.setOrientation(LinearLayout.VERTICAL);
        directionCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        directionCard.setBackground(roundedBackground(0xCC202630, 16));
        TextView directionTitle = new TextView(getThemedContext());
        directionTitle.setText(R.string.game_menu_controller_kbm_flick_direction);
        directionTitle.setTextColor(Color.WHITE);
        directionTitle.setTextSize(15);
        directionCard.addView(directionTitle);

        LinearLayout directionRow = new LinearLayout(getThemedContext());
        directionRow.setGravity(Gravity.CENTER);
        List<Button> directionButtons = new ArrayList<>();
        String[] directions = {
                ControllerKbmMapper.FLICK_LEFT,
                ControllerKbmMapper.FLICK_RIGHT,
                ControllerKbmMapper.FLICK_UP,
                ControllerKbmMapper.FLICK_DOWN
        };
        for (String value : directions) {
            Button button = new Button(getThemedContext());
            button.setTag(value);
            button.setText(getDirectedFlickDirectionLabel(value));
            button.setTextColor(Color.WHITE);
            button.setTextSize(14);
            button.setAllCaps(false);
            button.setOnClickListener(view -> {
                direction[0] = (String) view.getTag();
                updateDirectedFlickDirectionButtons(directionButtons, direction[0]);
            });
            button.setOnFocusChangeListener((view, hasFocus) -> {
                boolean selected = direction[0].equals(view.getTag());
                view.setBackground(roundedBackground(
                        hasFocus ? 0xFF435264 :
                                selected ? 0xFFF7A900 : 0xCC2A3440, 13));
            });
            directionButtons.add(button);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, dp(48), 1);
            params.setMargins(dp(4), dp(8), dp(4), 0);
            directionRow.addView(button, params);
        }
        updateDirectedFlickDirectionButtons(directionButtons, direction[0]);
        directionCard.addView(directionRow);
        content.addView(directionCard);

        LinearLayout distanceCard = new LinearLayout(getThemedContext());
        distanceCard.setOrientation(LinearLayout.VERTICAL);
        distanceCard.setPadding(dp(16), dp(12), dp(16), dp(12));
        distanceCard.setBackground(roundedBackground(0xCC202630, 16));
        LinearLayout.LayoutParams distanceCardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        distanceCardParams.setMargins(0, dp(10), 0, 0);

        TextView distanceLabel = new TextView(getThemedContext());
        distanceLabel.setTextColor(Color.WHITE);
        distanceLabel.setTextSize(15);
        distanceLabel.setText(getString(
                R.string.game_menu_controller_kbm_flick_distance) +
                ": " + currentDistance);
        distanceCard.addView(distanceLabel);

        SeekBar distance = new SeekBar(getThemedContext());
        distance.setMax(ControllerKbmMapper.MAX_FLICK_DISTANCE -
                ControllerKbmMapper.MIN_FLICK_DISTANCE);
        distance.setProgress(currentDistance -
                ControllerKbmMapper.MIN_FLICK_DISTANCE);
        distance.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                distanceLabel.setText(getString(
                        R.string.game_menu_controller_kbm_flick_distance) +
                        ": " + (progress + ControllerKbmMapper.MIN_FLICK_DISTANCE));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        distanceCard.addView(distance);
        content.addView(distanceCard, distanceCardParams);

        LinearLayout actions = new LinearLayout(getThemedContext());
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(12), 0, 0);
        actions.addView(createModernDialogButton(
                getString(R.string.game_menu_cancel), false,
                () -> showControllerKbmActionPicker(device, mapper, source)),
                modernDialogButtonParams());
        actions.addView(createModernDialogButton(
                getString(R.string.game_menu_controller_kbm_save_flick), true, () -> {
                    mapper.setAction(source,
                            ControllerKbmMapper.createDirectedFlickAction(
                                    direction[0],
                                    distance.getProgress() +
                                            ControllerKbmMapper.MIN_FLICK_DISTANCE));
                    hideMenu();
                    showControllerKbmMenu(device);
                }), modernDialogButtonParams());
        content.addView(actions);

        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_controller_kbm_directed_flick),
                content, null,
                () -> showControllerKbmActionPicker(device, mapper, source));
        showFullscreenDialog(shell, null);
    }

    private void showControllerKbmFunctionKeyPicker(GameInputDevice device,
                                                     ControllerKbmMapper mapper,
                                                     String source) {
        String[] labels = new String[12];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "F" + (i + 1);
        }

        ScrollView scrollView = new ScrollView(getThemedContext());
        GridLayout grid = createMenuGrid();
        for (int i = 0; i < labels.length; i++) {
            final int functionIndex = i;
            MenuOption option = new MenuOption(
                    "kbm_function_" + (i + 1), labels[i], () -> {});
            View card = createQuickMenuCard(option, () -> {
                mapper.setAction(source, ControllerKbmMapper.ACTION_KEY_PREFIX +
                        (KeyEvent.KEYCODE_F1 + functionIndex));
                hideMenu();
                showControllerKbmMenu(device);
            });
            grid.addView(card, createMenuCardLayoutParams());
        }
        scrollView.addView(grid);
        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_controller_kbm_function_keys),
                scrollView, null,
                () -> showControllerKbmActionPicker(device, mapper, source));
        showFullscreenDialog(shell, null);
    }

    private void addControllerKbmSlider(LinearLayout layout, SharedPreferences prefs,
                                         int labelRes, String key, int defaultValue,
                                         int maxValue) {
        TextView label = new TextView(getThemedContext());
        SeekBar seekBar = new SeekBar(getThemedContext());
        int value = prefs.getInt(key, defaultValue);
        label.setText(getString(labelRes) + ": " + value + "%");
        label.setTextSize(16);
        seekBar.setMax(maxValue);
        seekBar.setProgress(value);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    prefs.edit().putInt(key, progress).apply();
                    label.setText(getString(labelRes) + ": " + progress + "%");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        layout.addView(label);
        layout.addView(seekBar);
    }

    private void addControllerKbmRateSlider(LinearLayout layout, SharedPreferences prefs,
                                             int labelRes, String key, int defaultValue,
                                             int maxValue) {
        TextView label = new TextView(getThemedContext());
        SeekBar seekBar = new SeekBar(getThemedContext());
        int value = prefs.getInt(key, defaultValue);
        label.setText(getString(labelRes) + ": " + value + "/s");
        label.setTextSize(16);
        seekBar.setMax(maxValue);
        seekBar.setProgress(value);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    int rate = Math.max(1, progress);
                    if (rate != progress) {
                        bar.setProgress(rate);
                        return;
                    }
                    prefs.edit().putInt(key, rate).apply();
                    label.setText(getString(labelRes) + ": " + rate + "/s");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        layout.addView(label);
        layout.addView(seekBar);
    }

    private void showControllerKbmSettings(GameInputDevice device, ControllerKbmMapper mapper) {
        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(game);
        ScrollView scrollView = new ScrollView(getThemedContext());
        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (18 * game.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);
        scrollView.addView(layout);

        addControllerKbmSlider(layout, prefs, R.string.game_menu_controller_kbm_stick_speed,
                ControllerKbmMapper.PREF_STICK_SPEED,
                ControllerKbmMapper.DEFAULT_STICK_SPEED, 300);

        Switch continuousStickSwitch = new Switch(getThemedContext());
        continuousStickSwitch.setText(R.string.game_menu_controller_kbm_continuous_stick_mouse);
        continuousStickSwitch.setChecked(prefs.getBoolean(
                ControllerKbmMapper.PREF_CONTINUOUS_STICK_MOUSE, true));
        continuousStickSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(
                        ControllerKbmMapper.PREF_CONTINUOUS_STICK_MOUSE, checked).apply());
        layout.addView(continuousStickSwitch);

        addControllerKbmSlider(layout, prefs, R.string.game_menu_controller_kbm_trigger_threshold,
                ControllerKbmMapper.PREF_TRIGGER_THRESHOLD,
                ControllerKbmMapper.DEFAULT_TRIGGER_THRESHOLD, 100);

        TextView triggerBehaviorLabel = new TextView(getThemedContext());
        triggerBehaviorLabel.setText(R.string.game_menu_controller_kbm_trigger_behavior);
        triggerBehaviorLabel.setTextSize(16);
        layout.addView(triggerBehaviorLabel);

        Spinner triggerBehaviorSpinner = new Spinner(getThemedContext());
        String[] triggerBehaviorLabels = {
                getString(R.string.game_menu_controller_kbm_trigger_hold),
                getString(R.string.game_menu_controller_kbm_trigger_single),
                getString(R.string.game_menu_controller_kbm_trigger_repeat)
        };
        String[] triggerBehaviorValues = {
                ControllerKbmMapper.TRIGGER_BEHAVIOR_HOLD,
                ControllerKbmMapper.TRIGGER_BEHAVIOR_SINGLE,
                ControllerKbmMapper.TRIGGER_BEHAVIOR_REPEAT
        };
        ArrayAdapter<String> triggerBehaviorAdapter = new ArrayAdapter<>(
                getThemedContext(), android.R.layout.simple_spinner_item,
                triggerBehaviorLabels);
        triggerBehaviorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        triggerBehaviorSpinner.setAdapter(triggerBehaviorAdapter);
        String currentTriggerBehavior = prefs.getString(
                ControllerKbmMapper.PREF_TRIGGER_BEHAVIOR,
                ControllerKbmMapper.TRIGGER_BEHAVIOR_HOLD);
        triggerBehaviorSpinner.setSelection(
                ControllerKbmMapper.TRIGGER_BEHAVIOR_SINGLE.equals(currentTriggerBehavior) ? 1 :
                        ControllerKbmMapper.TRIGGER_BEHAVIOR_REPEAT.equals(currentTriggerBehavior) ? 2 : 0);
        layout.addView(triggerBehaviorSpinner);

        addControllerKbmRateSlider(layout, prefs,
                R.string.game_menu_controller_kbm_trigger_repeat_rate,
                ControllerKbmMapper.PREF_TRIGGER_REPEAT_RATE,
                ControllerKbmMapper.DEFAULT_TRIGGER_REPEAT_RATE, 20);
        TextView repeatRateLabel = (TextView) layout.getChildAt(layout.getChildCount() - 2);
        SeekBar repeatRateSeekBar = (SeekBar) layout.getChildAt(layout.getChildCount() - 1);
        boolean repeatSelected = ControllerKbmMapper.TRIGGER_BEHAVIOR_REPEAT.equals(
                currentTriggerBehavior);
        repeatRateLabel.setEnabled(repeatSelected);
        repeatRateSeekBar.setEnabled(repeatSelected);
        triggerBehaviorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String value = triggerBehaviorValues[position];
                prefs.edit().putString(ControllerKbmMapper.PREF_TRIGGER_BEHAVIOR, value).apply();
                boolean repeat = ControllerKbmMapper.TRIGGER_BEHAVIOR_REPEAT.equals(value);
                repeatRateLabel.setEnabled(repeat);
                repeatRateSeekBar.setEnabled(repeat);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        addControllerKbmSlider(layout, prefs, R.string.game_menu_controller_kbm_gyro_sensitivity,
                ControllerKbmMapper.PREF_GYRO_SENSITIVITY,
                ControllerKbmMapper.DEFAULT_GYRO_SENSITIVITY, 300);
        TextView gyroLabel = (TextView) layout.getChildAt(layout.getChildCount() - 2);
        SeekBar gyroSensitivity = (SeekBar) layout.getChildAt(layout.getChildCount() - 1);

        Switch gyroInvertX = new Switch(getThemedContext());
        gyroInvertX.setText(R.string.game_menu_controller_kbm_gyro_invert_x);
        gyroInvertX.setChecked(prefs.getBoolean(ControllerKbmMapper.PREF_GYRO_INVERT_X,
                ControllerKbmMapper.DEFAULT_GYRO_INVERT_X));
        gyroInvertX.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(ControllerKbmMapper.PREF_GYRO_INVERT_X, checked).apply());
        layout.addView(gyroInvertX);

        Switch gyroInvertY = new Switch(getThemedContext());
        gyroInvertY.setText(R.string.game_menu_controller_kbm_gyro_invert_y);
        gyroInvertY.setChecked(prefs.getBoolean(ControllerKbmMapper.PREF_GYRO_INVERT_Y, false));
        gyroInvertY.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(ControllerKbmMapper.PREF_GYRO_INVERT_Y, checked).apply());
        layout.addView(gyroInvertY);

        Switch gyroInvertZ = new Switch(getThemedContext());
        gyroInvertZ.setText(R.string.game_menu_controller_kbm_gyro_invert_z);
        gyroInvertZ.setChecked(prefs.getBoolean(ControllerKbmMapper.PREF_GYRO_INVERT_Z, false));
        gyroInvertZ.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(ControllerKbmMapper.PREF_GYRO_INVERT_Z, checked).apply());
        layout.addView(gyroInvertZ);

        Switch gyroSmoothing = new Switch(getThemedContext());
        gyroSmoothing.setText(R.string.game_menu_gyro_smoothing);
        gyroSmoothing.setChecked(prefs.getBoolean(
                ControllerKbmMapper.PREF_GYRO_SMOOTHING,
                ControllerKbmMapper.DEFAULT_GYRO_SMOOTHING));
        gyroSmoothing.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(
                        ControllerKbmMapper.PREF_GYRO_SMOOTHING, checked).apply());
        layout.addView(gyroSmoothing);

        Switch gyroStatusOverlay = new Switch(getThemedContext());
        gyroStatusOverlay.setText(R.string.game_menu_gyro_status_overlay);
        gyroStatusOverlay.setChecked(prefs.getBoolean(
                ControllerKbmMapper.PREF_GYRO_STATUS_OVERLAY,
                ControllerKbmMapper.DEFAULT_GYRO_STATUS_OVERLAY));
        gyroStatusOverlay.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(
                        ControllerKbmMapper.PREF_GYRO_STATUS_OVERLAY, checked).apply());
        layout.addView(gyroStatusOverlay);

        List<String> activationSources = mapper.getGyroActivationSources(true);
        addGyroActivationControls(layout, prefs, device, mapper, activationSources,
                ControllerKbmMapper.PREF_GYRO_ACTIVATION_MODE,
                ControllerKbmMapper.PREF_GYRO_ACTIVATION_SOURCES,
                ControllerKbmMapper.PREF_GYRO_ACTIVATION_SOURCE,
                ControllerKbmMapper.PREF_GYRO_HOLD_ACTIVATION,
                true);

        cardifySettingsLayout(layout);
        showFullscreenSettings(R.string.game_menu_controller_kbm_settings,
                scrollView, () -> showControllerKbmMenu(device),
                () -> showControllerKbmMenu(device));
    }

    private void showControllerKbmAddButton(GameInputDevice device, ControllerKbmMapper mapper) {
        AlertDialog dialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_add_button)
                .setMessage(R.string.game_menu_controller_kbm_press_button)
                .setNegativeButton(R.string.game_menu_cancel,
                        (ignored, which) -> showControllerKbmMenu(device))
                .create();
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }
            String source = mapper.sourceForKeyEvent(event);
            if (!mapper.addCustomSource(source)) {
                Toast.makeText(game, R.string.game_menu_controller_kbm_duplicate_button,
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            dialog.dismiss();
            showControllerKbmMenu(device);
            return true;
        });
        currentDialog = dialog;
        dialog.show();
    }

    private CharSequence getControllerKbmPresetDisplayName(ControllerKbmMapper.Preset preset) {
        SpannableStringBuilder text = new SpannableStringBuilder(preset.baseName);
        if (preset.duplicateIndex > 0) {
            int start = text.length();
            text.append(" (").append(String.valueOf(preset.duplicateIndex)).append(")");
            text.setSpan(new ForegroundColorSpan(Color.GRAY), start, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private void exportControllerKbmPreset(GameInputDevice device,
                                            ControllerKbmMapper mapper,
                                            ControllerKbmMapper.Preset preset) {
        try {
            pendingPresetExport = mapper.exportPreset(preset);
            pendingPresetDevice = device;
            String safeName = preset.getDisplayName().replaceAll("[\\\\/:*?\"<>|]", "_");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, safeName + ".artemis-kbm.json");
            game.startActivityForResult(intent, REQUEST_CODE_EXPORT_KBM_PRESET);
        }
        catch (Exception e) {
            pendingPresetExport = null;
            Toast.makeText(game, R.string.game_menu_controller_kbm_export_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void importControllerKbmPreset(GameInputDevice device) {
        pendingPresetDevice = device;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/json", "text/json", "text/plain", "application/octet-stream"
        });
        try {
            game.startActivityForResult(intent, REQUEST_CODE_IMPORT_KBM_PRESET);
        }
        catch (Exception e) {
            Toast.makeText(game, R.string.game_menu_controller_kbm_import_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    public void handleControllerKbmPresetActivityResult(int requestCode,
                                                         int resultCode,
                                                         Intent data) {
        if (requestCode != REQUEST_CODE_EXPORT_KBM_PRESET &&
                requestCode != REQUEST_CODE_IMPORT_KBM_PRESET) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingPresetExport = null;
            if (requestCode == REQUEST_CODE_EXPORT_KBM_PRESET) {
                ControllerKbmMapper mapper = game.getControllerKbmMapper();
                if (mapper != null) {
                    showControllerKbmLoadPreset(pendingPresetDevice, mapper);
                }
            }
            else {
                showControllerKbmMenu(pendingPresetDevice);
            }
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_CODE_EXPORT_KBM_PRESET) {
            try (OutputStream output = game.getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null || pendingPresetExport == null) {
                    throw new IllegalStateException("Unable to open preset output");
                }
                output.write(pendingPresetExport.getBytes(StandardCharsets.UTF_8));
                output.flush();
                Toast.makeText(game, R.string.game_menu_controller_kbm_export_success,
                        Toast.LENGTH_SHORT).show();
            }
            catch (Exception e) {
                Toast.makeText(game, R.string.game_menu_controller_kbm_export_failed,
                        Toast.LENGTH_LONG).show();
            }
            finally {
                pendingPresetExport = null;
                ControllerKbmMapper mapper = game.getControllerKbmMapper();
                if (mapper != null) {
                    showControllerKbmLoadPreset(pendingPresetDevice, mapper);
                }
            }
            return;
        }

        try (InputStream input = game.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("Unable to open preset input");
            }
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_PRESET_FILE_SIZE) {
                    throw new IllegalArgumentException("Preset file is too large");
                }
                output.write(buffer, 0, count);
            }
            ControllerKbmMapper mapper = game.getControllerKbmMapper();
            if (mapper == null) {
                throw new IllegalStateException("Controller mapper unavailable");
            }
            String serialized = output.toString(StandardCharsets.UTF_8.name());
            if (!serialized.isEmpty() && serialized.charAt(0) == '\uFEFF') {
                serialized = serialized.substring(1);
            }
            ControllerKbmMapper.Preset imported = mapper.importPreset(serialized);
            Toast.makeText(game, game.getString(
                            R.string.game_menu_controller_kbm_import_success,
                            imported.getDisplayName()),
                    Toast.LENGTH_SHORT).show();
            showControllerKbmLoadPreset(pendingPresetDevice, mapper);
        }
        catch (Exception e) {
            Toast.makeText(game, R.string.game_menu_controller_kbm_import_failed,
                    Toast.LENGTH_LONG).show();
            showControllerKbmMenu(pendingPresetDevice);
        }
    }

    private void showControllerKbmSavePreset(GameInputDevice device, ControllerKbmMapper mapper) {
        LinearLayout content = new LinearLayout(getThemedContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(8), dp(10), dp(10));

        LinearLayout inputCard = new LinearLayout(getThemedContext());
        inputCard.setOrientation(LinearLayout.VERTICAL);
        inputCard.setPadding(dp(18), dp(14), dp(18), dp(14));
        inputCard.setBackground(roundedBackground(0xCC202630, 16));

        TextView label = new TextView(getThemedContext());
        label.setText(R.string.game_menu_controller_kbm_preset_name);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        inputCard.addView(label);

        EditText nameInput = new EditText(getThemedContext());
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.game_menu_controller_kbm_preset_name);
        nameInput.setTextColor(Color.WHITE);
        nameInput.setHintTextColor(0x88FFFFFF);
        inputCard.addView(nameInput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        content.addView(inputCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(getThemedContext());
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(12), 0, 0);
        Button cancel = createModernDialogButton(
                getString(R.string.game_menu_cancel), false,
                () -> showControllerKbmMenu(device));
        Button save = createModernDialogButton(
                getString(R.string.game_menu_controller_kbm_save_preset), true, () -> {
                    ControllerKbmMapper.Preset preset =
                            mapper.savePreset(nameInput.getText().toString());
                    if (preset != null) {
                        hideMenu();
                        showControllerKbmMenu(device);
                    }
                    else {
                        nameInput.setError(
                                getString(R.string.game_menu_controller_kbm_preset_name));
                    }
        });
        actions.addView(cancel, modernDialogButtonParams());
        actions.addView(save, modernDialogButtonParams());
        content.addView(actions);

        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_controller_kbm_save_preset),
                content, null, () -> showControllerKbmMenu(device));
        showFullscreenDialog(shell, null);
        nameInput.post(nameInput::requestFocus);
    }

    private LinearLayout.LayoutParams modernDialogButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMargins(dp(6), 0, 0, 0);
        return params;
    }

    private Button createModernDialogButton(String text, boolean accent, Runnable action) {
        Button button = new Button(getThemedContext());
        int normalColor = accent ? 0xFFF7A900 : 0xCC2A3440;
        int focusColor = accent ? 0xFFFFC04A : 0xFF435264;
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(roundedBackground(normalColor, 13));
        button.setOnClickListener(view -> action.run());
        button.setFocusable(true);
        button.setOnFocusChangeListener((view, hasFocus) -> {
            view.setBackground(roundedBackground(
                    hasFocus ? focusColor : normalColor, 13));
            view.setScaleX(hasFocus ? 1.04f : 1f);
            view.setScaleY(hasFocus ? 1.04f : 1f);
        });
        return button;
    }

    private void showModernKbmConfirmation(String title, String message,
                                            String confirmText, Runnable confirmAction,
                                            Runnable backAction) {
        LinearLayout content = new LinearLayout(getThemedContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView messageView = new TextView(getThemedContext());
        messageView.setText(message);
        messageView.setTextColor(0xFFE3E7ED);
        messageView.setTextSize(17);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(dp(22), dp(22), dp(22), dp(22));
        messageView.setBackground(roundedBackground(0xCC202630, 18));
        content.addView(messageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(getThemedContext());
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(16), 0, 0);
        actions.addView(createModernDialogButton(
                getString(R.string.game_menu_cancel), false, backAction),
                modernDialogButtonParams());
        actions.addView(createModernDialogButton(confirmText, true, () -> {
            hideMenu();
            confirmAction.run();
        }), modernDialogButtonParams());
        content.addView(actions);

        LinearLayout shell = createFullscreenMenuShell(
                title, content, null, backAction);
        showFullscreenDialog(shell, null);
    }

    private void confirmControllerKbmPresetDelete(GameInputDevice device,
                                                   ControllerKbmMapper mapper,
                                                   ControllerKbmMapper.Preset preset) {
        showModernKbmConfirmation(
                getString(R.string.game_menu_controller_kbm_delete_preset),
                game.getString(R.string.game_menu_controller_kbm_delete_preset_confirm,
                        preset.getDisplayName()),
                getString(R.string.game_menu_controller_kbm_delete_preset), () -> {
                    mapper.deletePreset(preset.id);
                    showControllerKbmLoadPreset(device, mapper);
                }, () -> showControllerKbmLoadPreset(device, mapper));
    }

    private void showControllerKbmLoadPreset(GameInputDevice device, ControllerKbmMapper mapper) {
        List<ControllerKbmMapper.Preset> presets = mapper.getPresets();
        if (presets.isEmpty()) {
            LinearLayout empty = new LinearLayout(getThemedContext());
            empty.setGravity(Gravity.CENTER);
            TextView message = new TextView(getThemedContext());
            message.setText(R.string.game_menu_controller_kbm_no_presets);
            message.setTextColor(0xFFE3E7ED);
            message.setTextSize(18);
            message.setGravity(Gravity.CENTER);
            message.setPadding(dp(24), dp(24), dp(24), dp(24));
            message.setBackground(roundedBackground(0xCC202630, 18));
            empty.addView(message, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout shell = createFullscreenMenuShell(
                    getString(R.string.game_menu_controller_kbm_load_preset),
                    empty, null, () -> showControllerKbmMenu(device));
            showFullscreenDialog(shell, null);
            return;
        }

        ScrollView scrollView = new ScrollView(getThemedContext());
        GridLayout grid = createMenuGrid();
        for (ControllerKbmMapper.Preset preset : presets) {
            LinearLayout card = new LinearLayout(getThemedContext());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(12), dp(12), dp(12), dp(8));
            card.setBackground(roundedBackground(0xCC202630, 18));
            card.setFocusable(true);
            card.setClickable(true);
            card.setOnClickListener(view -> {
                game.loadControllerKbmPreset(preset);
                hideMenu();
                showControllerKbmMenu(device);
            });
            card.setOnFocusChangeListener((view, hasFocus) -> {
                view.setBackground(roundedBackground(
                        hasFocus ? 0xFF354352 : 0xCC202630, 18));
                view.setScaleX(hasFocus ? 1.03f : 1f);
                view.setScaleY(hasFocus ? 1.03f : 1f);
            });

            ImageView loadIcon = new ImageView(getThemedContext());
            loadIcon.setImageResource(R.drawable.ic_qm_download);
            loadIcon.setColorFilter(0xFFF7B52C);
            card.addView(loadIcon, new LinearLayout.LayoutParams(dp(30), dp(30)));

            TextView name = new TextView(getThemedContext());
            name.setText(getControllerKbmPresetDisplayName(preset));
            name.setTextColor(Color.WHITE);
            name.setTextSize(16);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
            nameParams.topMargin = dp(5);
            card.addView(name, nameParams);

            LinearLayout actions = new LinearLayout(getThemedContext());
            actions.setGravity(Gravity.CENTER);
            ImageButton export = createPresetIconButton(
                    R.drawable.ic_qm_share,
                    getString(R.string.game_menu_controller_kbm_export_preset), () -> {
                        hideMenu();
                        exportControllerKbmPreset(device, mapper, preset);
                    });
            ImageButton delete = createPresetIconButton(
                    R.drawable.ic_qm_delete,
                    getString(R.string.game_menu_controller_kbm_delete_preset), () -> {
                        hideMenu();
                        confirmControllerKbmPresetDelete(device, mapper, preset);
                    });
            actions.addView(export, new LinearLayout.LayoutParams(dp(38), dp(38)));
            actions.addView(delete, new LinearLayout.LayoutParams(dp(38), dp(38)));
            card.addView(actions);

            GridLayout.LayoutParams params = createMenuCardLayoutParams();
            params.height = dp(142);
            grid.addView(card, params);
        }
        scrollView.addView(grid);
        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_controller_kbm_load_preset),
                scrollView, null, () -> showControllerKbmMenu(device));
        showFullscreenDialog(shell, null);
    }

    private ImageButton createPresetIconButton(int iconRes, String description,
                                                Runnable action) {
        ImageButton button = new ImageButton(getThemedContext());
        button.setImageResource(iconRes);
        button.setColorFilter(Color.WHITE);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(description);
        button.setOnClickListener(view -> action.run());
        applyControllerFocusStyle(button);
        return button;
    }

    private void confirmControllerKbmReset(GameInputDevice device, ControllerKbmMapper mapper) {
        showModernKbmConfirmation(
                getString(R.string.game_menu_controller_kbm_reset),
                getString(R.string.game_menu_controller_kbm_reset_confirm),
                getString(R.string.game_menu_controller_kbm_reset), () -> {
                    game.resetControllerKbmMappings();
                    showControllerKbmMenu(device);
                }, () -> showControllerKbmMenu(device));
    }

    private void showControllerKbmMenu(GameInputDevice device) {
        ControllerKbmMapper mapper = game.getControllerKbmMapper();
        if (mapper == null) {
            return;
        }
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption("kbm_settings",
                getString(R.string.game_menu_controller_kbm_settings),
                () -> showControllerKbmSettings(device, mapper)));
        for (String source : mapper.getSources()) {
            options.add(new MenuOption("kbm_source_" + source,
                    getControllerKbmSourceLabel(source) + "  →  " +
                    getControllerKbmActionLabel(mapper.getAction(source)),
                    () -> showControllerKbmActionPicker(device, mapper, source)));
        }
        options.add(new MenuOption("kbm_add",
                getString(R.string.game_menu_controller_kbm_add_button),
                () -> showControllerKbmAddButton(device, mapper)));
        ControllerKbmMapper.Preset matchingPreset = mapper.getMatchingPreset();
        TextView presetLabel = new TextView(getThemedContext());
        SpannableStringBuilder presetText = new SpannableStringBuilder(
                getString(R.string.game_menu_controller_kbm_current_preset)
                        .replace("%1$s", ""));
        if (matchingPreset != null) {
            presetText.append(getControllerKbmPresetDisplayName(matchingPreset));
        }
        else {
            presetText.append(getString(R.string.game_menu_controller_kbm_temporary));
        }
        presetLabel.setText(presetText);
        presetLabel.setTextColor(0xFFE3E7ED);
        presetLabel.setTextSize(14);
        presetLabel.setGravity(Gravity.CENTER_VERTICAL);
        presetLabel.setPadding(dp(14), dp(8), dp(14), dp(8));
        presetLabel.setBackground(roundedBackground(0xCC202630, 14));

        LinearLayout headerActions = new LinearLayout(getThemedContext());
        headerActions.setGravity(Gravity.CENTER_VERTICAL);

        boolean playStationLayout = isPlayStationKbmIconLayout();
        Button layoutToggle = createModernDialogButton(
                playStationLayout ? "PS" : "Xbox", false, () -> {
                    PreferenceManager.getDefaultSharedPreferences(game).edit()
                            .putString(CONTROLLER_KBM_ICON_LAYOUT_PREF,
                                    playStationLayout ?
                                            CONTROLLER_KBM_ICON_LAYOUT_XBOX :
                                            CONTROLLER_KBM_ICON_LAYOUT_PLAYSTATION)
                            .apply();
                    hideMenu();
                    showControllerKbmMenu(device);
                });
        layoutToggle.setContentDescription(getString(
                R.string.game_menu_controller_kbm_switch_icon_layout));
        layoutToggle.setPadding(dp(6), 0, dp(6), 0);
        headerActions.addView(layoutToggle,
                new LinearLayout.LayoutParams(dp(58), dp(40)));

        ImageButton savePreset = new ImageButton(getThemedContext());
        savePreset.setImageResource(R.drawable.ic_qm_save);
        savePreset.setColorFilter(Color.WHITE);
        savePreset.setBackgroundColor(0x00000000);
        savePreset.setContentDescription(
                getString(R.string.game_menu_controller_kbm_save_preset));
        savePreset.setOnClickListener(view -> {
            if (currentDialog != null) currentDialog.dismiss();
            showControllerKbmSavePreset(device, mapper);
        });
        applyControllerFocusStyle(savePreset);
        headerActions.addView(savePreset, new LinearLayout.LayoutParams(dp(42), dp(42)));

        ImageButton loadPreset = new ImageButton(getThemedContext());
        loadPreset.setImageResource(R.drawable.ic_qm_download);
        loadPreset.setColorFilter(Color.WHITE);
        loadPreset.setBackgroundColor(0x00000000);
        loadPreset.setContentDescription(
                getString(R.string.game_menu_controller_kbm_load_preset));
        loadPreset.setOnClickListener(view -> {
            if (currentDialog != null) currentDialog.dismiss();
            showControllerKbmLoadPreset(device, mapper);
        });
        applyControllerFocusStyle(loadPreset);
        headerActions.addView(loadPreset, new LinearLayout.LayoutParams(dp(42), dp(42)));

        ImageButton importPreset = new ImageButton(getThemedContext());
        importPreset.setImageResource(R.drawable.ic_qm_add);
        importPreset.setColorFilter(Color.WHITE);
        importPreset.setBackgroundColor(0x00000000);
        importPreset.setContentDescription(
                getString(R.string.game_menu_controller_kbm_import_preset));
        importPreset.setOnClickListener(view -> {
            if (currentDialog != null) currentDialog.dismiss();
            importControllerKbmPreset(device);
        });
        applyControllerFocusStyle(importPreset);
        headerActions.addView(importPreset, new LinearLayout.LayoutParams(dp(42), dp(42)));

        ImageButton reset = new ImageButton(getThemedContext());
        reset.setImageResource(R.drawable.ic_qm_restore);
        reset.setColorFilter(Color.WHITE);
        reset.setBackgroundColor(0x00000000);
        reset.setContentDescription(getString(R.string.game_menu_controller_kbm_reset));
        reset.setOnClickListener(view -> {
            if (currentDialog != null) currentDialog.dismiss();
            confirmControllerKbmReset(device, mapper);
        });
        applyControllerFocusStyle(reset);
        headerActions.addView(reset, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams presetParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        presetParams.setMargins(dp(6), dp(2), dp(6), dp(6));
        layout.addView(presetLabel, presetParams);

        ScrollView scrollView = new ScrollView(getThemedContext());
        GridLayout grid = createMenuGrid();
        for (MenuOption option : options) {
            View card = createQuickMenuCard(option, () -> {
                if (currentDialog != null) {
                    currentDialog.dismiss();
                    currentDialog = null;
                }
                run(option);
            });
            grid.addView(card, createMenuCardLayoutParams());
        }
        scrollView.addView(grid);
        layout.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout shell = createFullscreenMenuShell(
                getString(R.string.game_menu_controller_kbm), layout, null,
                () -> showAdvancedMenu(device), headerActions);
        showFullscreenDialog(shell, null);
    }

    private void showAdvancedMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();
        if (game.allowChangeMouseMode) {
            options.add(new MenuOption(ADV_MOUSE_MODE, getString(R.string.game_menu_select_mouse_mode), true,
                    () -> showMouseModeMenu(device)));
        }

        options.add(new MenuOption(ADV_HUD, getString(R.string.game_menu_toggle_hud), true, game::toggleHUD));
        options.add(new MenuOption(ADV_FLOATING_BUTTON, getString(R.string.game_menu_toggle_floating_button), true,
                game::toggleFloatingButtonVisibility));
        options.add(new MenuOption(ADV_SPECIAL_KEYS_TOGGLE, getString(R.string.game_menu_toggle_keyboard_model), true,
                game::toggleKeyboardController));
        if (!game.isOnExternalDisplay()) {
            options.add(new MenuOption(ADV_OSC_TOGGLE, getString(R.string.game_menu_toggle_virtual_model), true,
                    game::toggleVirtualController));
        }
        options.add(new MenuOption(ADV_FULL_KEYBOARD_TOGGLE, getString(R.string.game_menu_toggle_virtual_keyboard_model), true,
                game::toggleFullKeyboard));
        options.add(new MenuOption(ADV_TASK_MANAGER, getString(R.string.game_menu_task_manager), true,
                () -> sendKeys(new short[]{KeyboardTranslator.VK_LCONTROL, KeyboardTranslator.VK_LSHIFT, KeyboardTranslator.VK_ESCAPE})));
        options.add(new MenuOption(ADV_VOLUME_BUTTONS, getString(R.string.game_menu_volume_button_mode) + ": " +
                game.getVolumeButtonModeLabel(), () -> showVolumeButtonModeMenu(device)));
        if (game.isGyroAimQuickSettingsEnabled()) {
            options.add(new MenuOption(ADV_GYRO_AIM_SETTINGS, getString(R.string.game_menu_gyro_aim_settings),
                    () -> showGyroAimSettingsMenu(device)));
        }
        if (game.isControllerKbmModeEnabled()) {
            options.add(new MenuOption(ADV_CONTROLLER_KBM,
                    getString(R.string.game_menu_controller_kbm),
                    () -> showControllerKbmMenu(device)));
        }
        options.add(new MenuOption(ADV_SEND_KEYS, getString(R.string.game_menu_send_keys), () -> {
            hideMenu();
            showSpecialKeysMenu(device);
        }));
        options.add(new MenuOption(ADV_TOUCH_SENSITIVITY, getString(R.string.game_menu_switch_touch_sensitivity_model), true,
                game::switchTouchSensitivity));
        if (device != null) {
            options.addAll(device.getGameMenuOptions());
        }
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_advanced),
                options.toArray(new MenuOption[0]), () -> showMenu(device));
    }

    private void showVolumeButtonModeMenu(GameInputDevice device) {
        String currentMode = game.getVolumeButtonMode();
        List<MenuOption> options = new ArrayList<>();
        String androidMode = PreferenceConfiguration.VOLUME_BUTTON_MODE_ANDROID;
        String windowsMode = PreferenceConfiguration.VOLUME_BUTTON_MODE_WINDOWS;

        options.add(new MenuOption("volume_mode_android",
                getString(R.string.game_menu_volume_button_mode_android) +
                        (androidMode.equals(currentMode) ? "  ✓" : ""),
                () -> {
                    game.setVolumeButtonMode(androidMode);
                    showAdvancedMenu(device);
                }));
        options.add(new MenuOption("volume_mode_windows",
                getString(R.string.game_menu_volume_button_mode_windows) +
                        (windowsMode.equals(currentMode) ? "  ✓" : ""),
                () -> {
                    game.setVolumeButtonMode(windowsMode);
                    showAdvancedMenu(device);
                }));
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_volume_button_mode_title),
                options.toArray(new MenuOption[0]), () -> showAdvancedMenu(device));
    }

    private void showMouseModeMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();
        for (MouseModeOption mouseMode : game.getAvailableMouseModeOptions()) {
            options.add(new MenuOption("mouse_mode_" + mouseMode.index,
                    mouseMode.label, true,
                    () -> game.selectMouseModeOption(mouseMode.index)));
        }
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_select_mouse_mode),
                options.toArray(new MenuOption[0]), () -> showAdvancedMenu(device));
    }

    private void showServerCmd(GameInputDevice device, ArrayList<String> serverCmds) {
        List<MenuOption> options = new ArrayList<>();

        AtomicInteger index = new AtomicInteger(0);
        for (String str : serverCmds) {
            final int finalI = index.getAndIncrement();
            options.add(new MenuOption("server_" + finalI, "> " + str, true, () -> game.sendExecServerCmd(finalI)));
        }

        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_server_cmd),
                options.toArray(new MenuOption[0]), () -> showMenu(device));
    }

    private List<MenuOption> createDefaultQuickMenuOptions(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();

        options.add(new MenuOption(MENU_DISCONNECT, getString(R.string.game_menu_disconnect), game::disconnect));
        options.add(new MenuOption(MENU_QUIT_SESSION, getString(R.string.game_menu_quit_session), game::quit));
        options.add(new MenuOption(MENU_UPLOAD_CLIPBOARD, getString(R.string.game_menu_upload_clipboard), true,
                () -> game.sendClipboard(true)));
        options.add(new MenuOption(MENU_FETCH_CLIPBOARD, getString(R.string.game_menu_fetch_clipboard), true,
                () -> game.getClipboard(0)));
        options.add(new MenuOption(MENU_SERVER_CMD, getString(R.string.game_menu_server_cmd), true, () -> {
            ArrayList<String> serverCmds = game.getServerCmds();
            if (serverCmds.isEmpty()) {
                new AlertDialog.Builder(getThemedContext())
                        .setTitle(R.string.game_dialog_title_server_cmd_empty)
                        .setMessage(R.string.game_dialog_message_server_cmd_empty)
                        .show();
            } else {
                hideMenu();
                showServerCmd(device, serverCmds);
            }
        }));
        options.add(new MenuOption(MENU_TOGGLE_KEYBOARD, getString(R.string.game_menu_toggle_keyboard), true,
                game::toggleKeyboard));
        options.add(new MenuOption(MENU_ZOOM_MODE, getString(game.isZoomModeEnabled() ?
                R.string.game_menu_disable_zoom_mode : R.string.game_menu_enable_zoom_mode), true, game::toggleZoomMode));
        if (dialogScreenContext == game) {
            options.add(new MenuOption(MENU_ROTATE_SCREEN, getString(R.string.game_menu_rotate_screen), true,
                    game::rotateScreen));
        }
        options.add(new MenuOption(MENU_GYRO_AXIS_MAPPING,
                getString(R.string.game_menu_gyro_axis_mapping),
                () -> showGyroAxisMappingMenu(device)));
        options.add(new MenuOption(MENU_DUALSENSE_BRIDGE,
                DualSenseController.hasActiveController() ?
                        "DualSense USB / HD audio • BETA" : "DualSense Bridge • BETA",
                () -> showDualSenseBridgeMenu(device)));
        options.add(new MenuOption(MENU_DUALSENSE_MICROPHONE,
                "Microphone passthrough\n" + microphoneStatusLabel(),
                () -> showDualSenseMicrophoneMenu(device)));
        options.add(new MenuOption(MENU_DUALSENSE_AUDIO_ROUTE,
                "DualSense audio route: " + audioRouteLabel(),
                () -> showDualSenseAudioRouteMenu(device)));
        options.add(new MenuOption(MENU_ADVANCED, getString(R.string.game_menu_advanced), true,
                () -> showAdvancedMenu(device)));
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));

        return options;
    }

    private String microphoneStatusLabel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(game);
        boolean enabled = prefs.getBoolean(
                PreferenceConfiguration.DUALSENSE_MICROPHONE_ENABLED_PREF_STRING, false);
        if (!enabled) return "Off";
        return "On • Source: " + microphoneSourceLabel();
    }

    private String microphoneSourceLabel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(game);
        String source = prefs.getString(PreferenceConfiguration.DUALSENSE_MICROPHONE_SOURCE_PREF_STRING,
                PreferenceConfiguration.DEFAULT_DUALSENSE_MICROPHONE_SOURCE);
        if (DualSenseMicrophoneBridge.SOURCE_DUALSENSE.equals(source)) return "DualSense controller";
        return "Client microphone";
    }

    private String audioRouteLabel() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(game);
        String route = prefs.getString(PreferenceConfiguration.DUALSENSE_AUDIO_MODE_PREF_STRING,
                PreferenceConfiguration.DEFAULT_DUALSENSE_AUDIO_MODE);
        switch (route) {
            case "usb_speaker": return "USB speaker";
            case "usb_headset": return "USB headset";
            case "haptics_only": return "HD haptics only";
            case "off": return "Off";
            default: return "Automatic";
        }
    }

    private void showDualSenseMicrophoneMenu(GameInputDevice device) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(game);
        boolean enabled = prefs.getBoolean(
                PreferenceConfiguration.DUALSENSE_MICROPHONE_ENABLED_PREF_STRING, false);
        String selectedSource = prefs.getString(
                PreferenceConfiguration.DUALSENSE_MICROPHONE_SOURCE_PREF_STRING,
                PreferenceConfiguration.DEFAULT_DUALSENSE_MICROPHONE_SOURCE);
        if (!DualSenseMicrophoneBridge.SOURCE_DUALSENSE.equals(selectedSource) &&
                !DualSenseMicrophoneBridge.SOURCE_DEVICE.equals(selectedSource)) {
            selectedSource = DualSenseMicrophoneBridge.SOURCE_DUALSENSE;
        }

        LinearLayout content = new LinearLayout(getThemedContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(10), dp(12), dp(12));

        Switch forwarding = new Switch(getThemedContext());
        forwarding.setText("Microphone forwarding: " + (enabled ? "On" : "Off"));
        forwarding.setTextColor(Color.WHITE);
        forwarding.setTextSize(17);
        forwarding.setChecked(enabled);
        forwarding.setPadding(dp(6), dp(4), dp(6), dp(12));
        forwarding.setOnCheckedChangeListener((button, checked) -> {
            if (checked == enabled) return;
            prefs.edit().putBoolean(PreferenceConfiguration.DUALSENSE_MICROPHONE_ENABLED_PREF_STRING,
                    checked).apply();
            game.refreshDualSenseMicrophoneCapture();
            showDualSenseMicrophoneMenu(device);
        });
        applyControllerFocusStyle(forwarding);
        content.addView(forwarding, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView sourceTitle = new TextView(getThemedContext());
        sourceTitle.setText("Source");
        sourceTitle.setTextColor(0xFFBBC7D6);
        sourceTitle.setTextSize(14);
        sourceTitle.setPadding(dp(6), dp(10), dp(6), dp(7));
        content.addView(sourceTitle);

        content.addView(createMicrophoneSourceRow("DualSense controller",
                DualSenseMicrophoneBridge.SOURCE_DUALSENSE.equals(selectedSource), () ->
                setMicrophoneSource(DualSenseMicrophoneBridge.SOURCE_DUALSENSE, device)));
        content.addView(createMicrophoneSourceRow("Client microphone",
                DualSenseMicrophoneBridge.SOURCE_DEVICE.equals(selectedSource), () ->
                setMicrophoneSource(DualSenseMicrophoneBridge.SOURCE_DEVICE, device)));

        ScrollView scroll = new ScrollView(getThemedContext());
        scroll.addView(content);
        LinearLayout shell = createFullscreenMenuShell("Microphone passthrough", scroll,
                null, () -> showMenu(device));
        showFullscreenDialog(shell, null);
    }

    private View createMicrophoneSourceRow(String label, boolean current, Runnable onSelect) {
        LinearLayout row = new LinearLayout(getThemedContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(14), dp(13));
        row.setBackground(roundedBackground(current ? 0xFF34475A : 0xFF242D38, 10));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(view -> onSelect.run());
        row.setOnFocusChangeListener((view, focused) -> {
            hideControllerNavigationHint();
            view.setBackground(roundedBackground(focused ? 0xFF465E74 :
                    (current ? 0xFF34475A : 0xFF242D38), 10));
        });

        TextView labelView = new TextView(getThemedContext());
        labelView.setText(label);
        labelView.setTextColor(Color.WHITE);
        labelView.setTextSize(16);
        row.addView(labelView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (current) {
            TextView currentView = new TextView(getThemedContext());
            currentView.setText("Current");
            currentView.setTextColor(0xFF7CDBFF);
            currentView.setTextSize(14);
            row.addView(currentView);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(4), 0, dp(4));
        row.setLayoutParams(params);
        return row;
    }

    private void setMicrophoneSource(String source, GameInputDevice device) {
        PreferenceManager.getDefaultSharedPreferences(game).edit()
                .putString(PreferenceConfiguration.DUALSENSE_MICROPHONE_SOURCE_PREF_STRING, source)
                .apply();
        game.refreshDualSenseMicrophoneCapture();
        showDualSenseMicrophoneMenu(device);
    }

    private void showDualSenseAudioRouteMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();
        addAudioRouteOption(options, "auto", "Automatic (speaker / headset jack)", device);
        addAudioRouteOption(options, "usb_speaker", "USB: controller speaker + HD haptics", device);
        addAudioRouteOption(options, "usb_headset", "USB: headset jack + HD haptics", device);
        addAudioRouteOption(options, "haptics_only", "HD haptics only", device);
        addAudioRouteOption(options, "off", "Disabled", device);
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel),
                () -> showMenu(device)));
        showMenuDialog("DualSense audio route (Apollo Extended)",
                options.toArray(new MenuOption[0]), () -> showMenu(device));
    }

    private void addAudioRouteOption(List<MenuOption> options, String mode, String label,
                                     GameInputDevice device) {
        options.add(new MenuOption("audio_" + mode, label, () -> {
            PreferenceManager.getDefaultSharedPreferences(game).edit()
                    .putString(PreferenceConfiguration.DUALSENSE_AUDIO_MODE_PREF_STRING, mode).apply();
            DualSenseAudioBridge.configure(mode, PreferenceManager.getDefaultSharedPreferences(game).getInt(
                    PreferenceConfiguration.DUALSENSE_CONTROLLER_VOLUME_PREF_STRING,
                    PreferenceConfiguration.DEFAULT_DUALSENSE_CONTROLLER_VOLUME));
            showDualSenseAudioRouteMenu(device);
        }));
    }

    private void showDualSenseBridgeMenu(GameInputDevice device) {
        Handler handler = new Handler(Looper.getMainLooper());
        List<MenuOption> options = new ArrayList<>();
        boolean wiredConnected = DualSenseController.hasActiveController();
        String state;
        String connectionDetails;
        if (wiredConnected) {
            state = "Connected through USB — input packets: " +
                    DualSenseController.getActiveInputPacketCount();
            int batteryPercent = DualSenseController.getActiveBatteryPercent();
            if (batteryPercent >= 0) {
                state += "\nBattery: " + batteryPercent + "%";
            }
            state += "\nRaw HID age: " + DualSenseController.getActiveInputAgeMs() + " ms" +
                    "\nRead errors: " + DualSenseController.getActiveInputReadErrors() +
                    "\nAudio route: " +
                    (DualSenseController.getActiveHeadphonesConnected() ? "headset jack" : "speaker");
            connectionDetails = "Audio / HD haptics: " + DualSenseAudioBridge.diagnostics();
        }
        else {
            state = DualSenseBridge.getControllerConnected() ?
                    "Connected — input packets: " + DualSenseBridge.getInputPacketCount() :
                    "Not connected";
            DualSenseInput bridgeInput = DualSenseBridge.getLatestInput();
            if (DualSenseBridge.getControllerConnected() && bridgeInput.getBatteryPercent() >= 0) {
                state += "\nBattery: " + bridgeInput.getBatteryPercent() + "% - " +
                        bridgeInput.getBatteryStatus();
            }
            connectionDetails = DualSenseBridge.getStatus();
        }
        options.add(new MenuOption("dualsense_status",
                state + "\n" + connectionDetails, () ->
                showDualSenseBridgeMenu(device)));
        options.add(new MenuOption("dualsense_scan", "Find / rescan USB adapter", () -> {
            DualSenseBridge.scan(game);
            handler.postDelayed(() -> showDualSenseBridgeMenu(device), 350);
        }));
        options.add(new MenuOption("dualsense_reset", "Reset bridge connection", () -> {
            DualSenseBridge.reset(game);
            handler.postDelayed(() -> showDualSenseBridgeMenu(device), 500);
        }));
        boolean diagnosticsOverlay = DualSenseBridge.isConnectionOverlayEnabled();
        options.add(new MenuOption("dualsense_connection_overlay",
                getString(R.string.dualsense_diag_quick_menu) + ": " +
                        (diagnosticsOverlay ? getString(R.string.game_menu_on) :
                                getString(R.string.game_menu_off)), () -> {
            DualSenseBridge.setConnectionOverlayEnabled(!diagnosticsOverlay);
            showDualSenseBridgeMenu(device);
        }));
        for (com.example.usbbtonandroid.hci.HciUsbController.HciDevice controller :
                DualSenseBridge.getDevices()) {
            options.add(new MenuOption("dualsense_device_" + controller.getAddress(),
                    controller.getName() + "\n" + controller.getState(), () -> {
                DualSenseBridge.reconnect(controller.getAddress(), controller.getName());
                handler.postDelayed(() -> showDualSenseBridgeMenu(device), 350);
            }));
            if (controller.getPaired()) {
                options.add(new MenuOption("dualsense_forget_" + controller.getAddress(),
                        "Forget " + controller.getName(), () -> {
                    DualSenseBridge.forgetDevice(controller.getAddress());
                    handler.postDelayed(() -> showDualSenseBridgeMenu(device), 350);
                }));
            }
        }
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(wiredConnected ? "DualSense USB / HD audio • BETA" :
                        "DualSense Bridge • BETA",
                options.toArray(new MenuOption[0]), () -> showMenu(device));
    }

    private void showRestoreQuickMenuDialog(GameInputDevice device, List<MenuOption> allOptions,
                                            List<MenuOption> visibleOptions, Set<String> hiddenIds) {
        List<MenuOption> hiddenOptions = new ArrayList<>();
        for (String id : DEFAULT_QUICK_MENU_ORDER) {
            if (hiddenIds.contains(id)) {
                MenuOption option = findOptionById(allOptions, id);
                if (option != null) {
                    hiddenOptions.add(option);
                }
            }
        }

        if (hiddenOptions.isEmpty()) {
            Toast.makeText(game, R.string.game_menu_edit_nothing_hidden, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[hiddenOptions.size()];
        boolean[] checked = new boolean[hiddenOptions.size()];
        for (int i = 0; i < hiddenOptions.size(); i++) {
            labels[i] = hiddenOptions.get(i).label;
        }

        new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_edit_add_hidden)
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    for (int i = 0; i < hiddenOptions.size(); i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        MenuOption option = hiddenOptions.get(i);
                        hiddenIds.remove(option.id);
                        if (findOptionById(visibleOptions, option.id) == null) {
                            addOptionInDefaultPlace(visibleOptions, option);
                        }
                    }
                    saveQuickMenuState(visibleOptions, hiddenIds);
                    showMenu(device);
                })
                .setNegativeButton(R.string.game_menu_cancel, null)
                .show();
    }

    private void confirmResetQuickMenu(GameInputDevice device) {
        new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_edit_reset)
                .setMessage(R.string.game_menu_edit_reset_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    getMenuPrefs().edit()
                            .remove(QUICK_MENU_ORDER_KEY)
                            .remove(QUICK_MENU_HIDDEN_KEY)
                            .apply();
                    quickMenuEditMode = false;
                    showMenu(device);
                })
                .setNegativeButton(R.string.game_menu_cancel, null)
                .show();
    }

    private int findOptionIndex(List<MenuOption> options, String id) {
        for (int i = 0; i < options.size(); i++) {
            if (id.equals(options.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    private int getQuickMenuInsertIndex(ListView listView, DragEvent event, List<MenuOption> visibleOptions) {
        int target = listView.pointToPosition((int) event.getX(), (int) event.getY());
        if (target == AdapterView.INVALID_POSITION) {
            target = event.getY() < 0 ? 0 : visibleOptions.size();
        } else {
            View child = listView.getChildAt(target - listView.getFirstVisiblePosition());
            if (child != null && event.getY() > child.getTop() + (child.getHeight() / 2f)) {
                target++;
            }
        }

        int cancelIndex = findOptionIndex(visibleOptions, MENU_CANCEL);
        if (cancelIndex >= 0) {
            target = Math.min(target, cancelIndex);
        }
        return Math.max(0, Math.min(target, visibleOptions.size()));
    }

    private void moveDraggedQuickMenuItem(ListView listView, ArrayAdapter<MenuOption> adapter,
                                          List<MenuOption> visibleOptions, DragEvent event,
                                          boolean persist, Set<String> hiddenIds) {
        if (draggedQuickMenuId == null) {
            return;
        }

        int currentIndex = findOptionIndex(visibleOptions, draggedQuickMenuId);
        if (currentIndex < 0) {
            return;
        }

        int insertIndex = getQuickMenuInsertIndex(listView, event, visibleOptions);
        if (insertIndex > currentIndex) {
            insertIndex--;
        }

        insertIndex = Math.max(0, Math.min(insertIndex, visibleOptions.size() - 1));
        if (insertIndex == currentIndex) {
            if (persist) {
                saveQuickMenuState(visibleOptions, hiddenIds);
            }
            return;
        }

        MenuOption option = visibleOptions.remove(currentIndex);
        visibleOptions.add(insertIndex, option);
        refreshList(adapter, visibleOptions);

        if (persist) {
            saveQuickMenuState(visibleOptions, hiddenIds);
        }
    }

    public void showMenu(GameInputDevice device) {
        List<MenuOption> allOptions = createDefaultQuickMenuOptions(device);
        List<String> savedOrder = getSavedQuickMenuOrder();
        Set<String> hiddenIds = getHiddenQuickMenuIds();
        List<MenuOption> visibleOptions = new ArrayList<>();

        for (String id : savedOrder) {
            if (hiddenIds.contains(id)) {
                continue;
            }
            MenuOption option = findOptionById(allOptions, id);
            if (option != null) {
                visibleOptions.add(option);
            }
        }

        if (!quickMenuEditMode) {
            showQuickMenuCards(device, visibleOptions, hiddenIds);
            return;
        }
        showEditableQuickMenuCards(device, allOptions, visibleOptions, hiddenIds);
        return;

        /*
        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(createHeader(getString(R.string.quick_menu_title), quickMenuEditMode,
                () -> showRestoreQuickMenuDialog(device, allOptions, visibleOptions, hiddenIds),
                () -> confirmResetQuickMenu(device)));

        ListView listView = new ListView(getThemedContext());
        final ArrayAdapter<MenuOption>[] adapterRef = new ArrayAdapter[1];
        ArrayAdapter<MenuOption> adapter = createMenuAdapter(visibleOptions, quickMenuEditMode, hiddenIds, () -> {
            saveQuickMenuState(visibleOptions, hiddenIds);
            refreshList(adapterRef[0], visibleOptions);
        });
        adapterRef[0] = adapter;
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= visibleOptions.size()) {
                return;
            }

            MenuOption option = visibleOptions.get(position);
            if (quickMenuEditMode) {
                saveQuickMenuState(visibleOptions, hiddenIds);
            }

            if (currentDialog != null) {
                currentDialog.dismiss();
                currentDialog = null;
            }
            run(option);
        });

        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!quickMenuEditMode || position < 0 || position >= visibleOptions.size()) {
                return false;
            }
            if (MENU_CANCEL.equals(visibleOptions.get(position).id)) {
                return true;
            }

            draggedQuickMenuIndex = position;
            draggedQuickMenuId = visibleOptions.get(position).id;
            ClipData clipData = ClipData.newPlainText("quick_menu_item", visibleOptions.get(position).id);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                view.startDragAndDrop(clipData, new View.DragShadowBuilder(view), null, 0);
            } else {
                view.startDrag(clipData, new View.DragShadowBuilder(view), null, 0);
            }
            return true;
        });

        listView.setOnDragListener((view, event) -> {
            if (!quickMenuEditMode) {
                return false;
            }

            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                adapter.notifyDataSetChanged();
                return true;
            }
            else if (event.getAction() == DragEvent.ACTION_DRAG_LOCATION) {
                moveDraggedQuickMenuItem(listView, adapter, visibleOptions, event, false, hiddenIds);
                return true;
            }
            else if (event.getAction() == DragEvent.ACTION_DROP) {
                moveDraggedQuickMenuItem(listView, adapter, visibleOptions, event, true, hiddenIds);
                draggedQuickMenuIndex = -1;
                draggedQuickMenuId = null;
                adapter.notifyDataSetChanged();
                return true;
            }
            else if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) {
                saveQuickMenuState(visibleOptions, hiddenIds);
                draggedQuickMenuIndex = -1;
                draggedQuickMenuId = null;
                adapter.notifyDataSetChanged();
                return true;
            }

            return true;
        });

        layout.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout editShell = createFullscreenMenuShell(
                getString(R.string.game_menu_edit_quick_menu), layout, view -> {
                    saveQuickMenuState(visibleOptions, hiddenIds);
                    quickMenuEditMode = false;
                    showMenu(device);
                });
        showFullscreenDialog(editShell, () -> {
            if (quickMenuEditMode) {
                saveQuickMenuState(visibleOptions, hiddenIds);
            }
        });
        */
    }

    @Override
    public void hideMenu() {
        setControllerSliderCaptured(null, false);
        if (currentDialog != null && currentDialog.isShowing()) {
            currentDialog.dismiss();
        }
        currentDialog = null;
    }

    @Override
    public boolean isMenuOpen() {
        return currentDialog != null && currentDialog.isShowing();
    }
}
