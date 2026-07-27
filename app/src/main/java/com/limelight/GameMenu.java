package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.binding.input.ControllerKbmMapper;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.KeyConfigHelper;
import com.limelight.utils.KeyMapper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class GameMenu implements Game.GameMenuCallbacks {

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

    private static final List<String> DEFAULT_QUICK_MENU_ORDER = Arrays.asList(
            MENU_DISCONNECT,
            MENU_QUIT_SESSION,
            MENU_UPLOAD_CLIPBOARD,
            MENU_FETCH_CLIPBOARD,
            MENU_SERVER_CMD,
            MENU_TOGGLE_KEYBOARD,
            MENU_ZOOM_MODE,
            MENU_ROTATE_SCREEN,
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
    private boolean quickMenuEditMode;
    private int draggedQuickMenuIndex = -1;
    private String draggedQuickMenuId;

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
                    deleteButton.setFocusable(false);
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
                                          Set<String> hiddenIds) {
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

        new AlertDialog.Builder(getThemedContext())
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
                .show();
    }

    private void showMenuDialog(String title, MenuOption[] allOptions) {
        Set<String> hiddenIds = getHiddenSubmenuIds(title);
        List<MenuOption> visibleOptions = new ArrayList<>();
        for (MenuOption option : allOptions) {
            if (quickMenuEditMode && option.id != null && hiddenIds.contains(option.id)) {
                continue;
            }
            visibleOptions.add(option);
        }

        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);

        boolean editableSubmenu = quickMenuEditMode;
        layout.addView(createHeader(title, editableSubmenu,
                () -> showRestoreSubmenuDialog(title, allOptions, visibleOptions, hiddenIds),
                () -> {
                    getMenuPrefs().edit().remove(submenuHiddenKey(title)).apply();
                    showMenuDialog(title, allOptions);
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
        layout.addView(listView);

        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        currentDialog = new AlertDialog.Builder(getThemedContext()).setView(layout).create();
        currentDialog.show();

        Window window = currentDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showSpecialKeysMenu() {
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
        showMenuDialog(getString(R.string.game_menu_send_keys), options.toArray(new MenuOption[0]));
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
        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (18 * game.getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding / 2, padding, 0);

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

        linkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(PreferenceConfiguration.GYRO_AIM_LINK_SIDE_AXES_PREF_STRING, isChecked).apply();
            verticalSeekBar.setEnabled(!isChecked);
            verticalLabel.setEnabled(!isChecked);
            game.reloadGyroAimSettings();
        });

        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        currentDialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_gyro_aim_settings)
                .setView(layout)
                .setPositiveButton(R.string.game_menu_done, (dialog, which) -> showAdvancedMenu(device))
                .create();
        currentDialog.show();
    }

    private String getControllerKbmSourceLabel(String source) {
        switch (source) {
            case ControllerKbmMapper.SOURCE_A: return "A / Cross";
            case ControllerKbmMapper.SOURCE_B: return "B / Circle";
            case ControllerKbmMapper.SOURCE_X: return "X / Square";
            case ControllerKbmMapper.SOURCE_Y: return "Y / Triangle";
            case ControllerKbmMapper.SOURCE_DPAD_UP: return "D-pad Up";
            case ControllerKbmMapper.SOURCE_DPAD_DOWN: return "D-pad Down";
            case ControllerKbmMapper.SOURCE_DPAD_LEFT: return "D-pad Left";
            case ControllerKbmMapper.SOURCE_DPAD_RIGHT: return "D-pad Right";
            case ControllerKbmMapper.SOURCE_LB: return "LB / L1";
            case ControllerKbmMapper.SOURCE_RB: return "RB / R1";
            case ControllerKbmMapper.SOURCE_L3: return "Left Stick Click";
            case ControllerKbmMapper.SOURCE_R3: return "Right Stick Click";
            case ControllerKbmMapper.SOURCE_START: return "Start / Options";
            case ControllerKbmMapper.SOURCE_SELECT: return "Select / Create";
            case ControllerKbmMapper.SOURCE_GUIDE: return "Guide / PS";
            case ControllerKbmMapper.SOURCE_SHARE: return "Share / Capture";
            case ControllerKbmMapper.SOURCE_TOUCHPAD: return "Touchpad Click";
            case ControllerKbmMapper.SOURCE_PADDLE_1: return "Rear Paddle 1";
            case ControllerKbmMapper.SOURCE_PADDLE_2: return "Rear Paddle 2";
            case ControllerKbmMapper.SOURCE_PADDLE_3: return "Rear Paddle 3";
            case ControllerKbmMapper.SOURCE_PADDLE_4: return "Rear Paddle 4";
            case ControllerKbmMapper.SOURCE_LT: return "LT / L2";
            case ControllerKbmMapper.SOURCE_RT: return "RT / R2";
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

    private String getControllerKbmActionLabel(String action) {
        if (action == null || action.isEmpty()) {
            return getString(R.string.game_menu_controller_kbm_unassigned);
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
        EditText input = new EditText(getThemedContext());
        input.setFocusableInTouchMode(true);
        input.setHint(R.string.game_menu_controller_kbm_press_keyboard_key);
        final boolean[] captured = { false };

        AlertDialog dialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_keyboard_key)
                .setView(input)
                .setNegativeButton(R.string.game_menu_cancel,
                        (ignored, which) -> showControllerKbmMenu(device))
                .create();
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            if (captured[0] || event.getAction() != KeyEvent.ACTION_DOWN) {
                return true;
            }
            captured[0] = true;
            mapper.setAction(source, ControllerKbmMapper.ACTION_KEY_PREFIX + keyCode);
            dialog.dismiss();
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
                        dialog.dismiss();
                        showControllerKbmMenu(device);
                        return;
                    }
                }
            }
        });
        dialog.setOnShowListener(ignored -> input.requestFocus());
        currentDialog = dialog;
        dialog.show();
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
                    ControllerKbmMapper.ACTION_MOUSE_LEFT,
                    ControllerKbmMapper.ACTION_MOUSE_RIGHT,
                    ControllerKbmMapper.ACTION_MOUSE_MIDDLE,
                    ControllerKbmMapper.ACTION_MOUSE_BACK,
                    ControllerKbmMapper.ACTION_MOUSE_FORWARD,
                    ControllerKbmMapper.ACTION_WHEEL_UP,
                    ControllerKbmMapper.ACTION_WHEEL_DOWN
            };
        }

        currentDialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(getControllerKbmSourceLabel(source))
                .setItems(labels, (dialog, which) -> {
                    if (ControllerKbmMapper.ACTION_KEY_PREFIX.equals(actions[which])) {
                        showControllerKbmKeyboardCapture(device, mapper, source);
                    }
                    else if (CONTROLLER_KBM_PICK_FUNCTION_KEY.equals(actions[which])) {
                        showControllerKbmFunctionKeyPicker(device, mapper, source);
                    }
                    else {
                        mapper.setAction(source, actions[which]);
                        showControllerKbmMenu(device);
                    }
                })
                .setNegativeButton(R.string.game_menu_cancel,
                        (dialog, which) -> showControllerKbmMenu(device))
                .create();
        currentDialog.show();
    }

    private void showControllerKbmFunctionKeyPicker(GameInputDevice device,
                                                     ControllerKbmMapper mapper,
                                                     String source) {
        String[] labels = new String[12];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = "F" + (i + 1);
        }

        currentDialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_function_keys)
                .setItems(labels, (dialog, which) -> {
                    mapper.setAction(source, ControllerKbmMapper.ACTION_KEY_PREFIX +
                            (KeyEvent.KEYCODE_F1 + which));
                    showControllerKbmMenu(device);
                })
                .setNegativeButton(R.string.game_menu_cancel,
                        (dialog, which) -> showControllerKbmActionPicker(device, mapper, source))
                .create();
        currentDialog.show();
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

        Switch gyroSwitch = new Switch(getThemedContext());
        gyroSwitch.setText(R.string.game_menu_controller_kbm_gyro);
        gyroSwitch.setChecked(prefs.getBoolean(ControllerKbmMapper.PREF_GYRO_ENABLED, false));
        layout.addView(gyroSwitch);
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

        gyroLabel.setEnabled(gyroSwitch.isChecked());
        gyroSensitivity.setEnabled(gyroSwitch.isChecked());
        gyroInvertX.setEnabled(gyroSwitch.isChecked());
        gyroInvertY.setEnabled(gyroSwitch.isChecked());
        gyroInvertZ.setEnabled(gyroSwitch.isChecked());
        gyroSwitch.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(ControllerKbmMapper.PREF_GYRO_ENABLED, checked).apply();
            gyroLabel.setEnabled(checked);
            gyroSensitivity.setEnabled(checked);
            gyroInvertX.setEnabled(checked);
            gyroInvertY.setEnabled(checked);
            gyroInvertZ.setEnabled(checked);
            game.refreshControllerKbmGyro();
        });

        currentDialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_settings)
                .setView(scrollView)
                .setPositiveButton(R.string.game_menu_done,
                        (dialog, which) -> showControllerKbmMenu(device))
                .create();
        currentDialog.show();
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

    private void showControllerKbmSavePreset(GameInputDevice device, ControllerKbmMapper mapper) {
        EditText nameInput = new EditText(getThemedContext());
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.game_menu_controller_kbm_preset_name);

        AlertDialog dialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_save_preset)
                .setView(nameInput)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.game_menu_cancel,
                        (ignored, which) -> showControllerKbmMenu(device))
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                ControllerKbmMapper.Preset preset =
                        mapper.savePreset(nameInput.getText().toString());
                if (preset != null) {
                    dialog.dismiss();
                    showControllerKbmMenu(device);
                }
                else {
                    nameInput.setError(getString(R.string.game_menu_controller_kbm_preset_name));
                }
            });
            nameInput.requestFocus();
        });
        currentDialog = dialog;
        dialog.show();
    }

    private void confirmControllerKbmPresetDelete(GameInputDevice device,
                                                   ControllerKbmMapper mapper,
                                                   ControllerKbmMapper.Preset preset) {
        new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_delete_preset)
                .setMessage(game.getString(R.string.game_menu_controller_kbm_delete_preset_confirm,
                        preset.getDisplayName()))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mapper.deletePreset(preset.id);
                    showControllerKbmLoadPreset(device, mapper);
                })
                .setNegativeButton(R.string.game_menu_cancel,
                        (dialog, which) -> showControllerKbmLoadPreset(device, mapper))
                .show();
    }

    private void showControllerKbmLoadPreset(GameInputDevice device, ControllerKbmMapper mapper) {
        List<ControllerKbmMapper.Preset> presets = mapper.getPresets();
        if (presets.isEmpty()) {
            new AlertDialog.Builder(getThemedContext())
                    .setTitle(R.string.game_menu_controller_kbm_load_preset)
                    .setMessage(R.string.game_menu_controller_kbm_no_presets)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> showControllerKbmMenu(device))
                    .show();
            return;
        }

        ListView list = new ListView(getThemedContext());
        ArrayAdapter<ControllerKbmMapper.Preset> adapter =
                new ArrayAdapter<ControllerKbmMapper.Preset>(
                        getThemedContext(), android.R.layout.simple_list_item_1, presets) {
                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        ControllerKbmMapper.Preset preset = getItem(position);
                        LinearLayout row = new LinearLayout(getThemedContext());
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        int padding = (int) (12 *
                                game.getResources().getDisplayMetrics().density);

                        TextView name = new TextView(getThemedContext());
                        name.setText(getControllerKbmPresetDisplayName(preset));
                        name.setTextSize(18);
                        name.setPadding(padding, padding, padding, padding);
                        row.addView(name, new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

                        View.OnClickListener loadPreset = view -> {
                            game.loadControllerKbmPreset(preset);
                            if (currentDialog != null) {
                                currentDialog.dismiss();
                            }
                            showControllerKbmMenu(device);
                        };
                        row.setOnClickListener(loadPreset);
                        name.setOnClickListener(loadPreset);

                        ImageButton delete = new ImageButton(getThemedContext());
                        delete.setImageResource(android.R.drawable.ic_menu_delete);
                        delete.setBackgroundColor(0x00000000);
                        delete.setFocusable(false);
                        delete.setFocusableInTouchMode(false);
                        delete.setContentDescription(
                                getString(R.string.game_menu_controller_kbm_delete_preset));
                        delete.setOnClickListener(view -> {
                            if (currentDialog != null) {
                                currentDialog.dismiss();
                            }
                            confirmControllerKbmPresetDelete(device, mapper, preset);
                        });
                        row.addView(delete);
                        return row;
                    }
                };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            ControllerKbmMapper.Preset preset = adapter.getItem(position);
            if (preset != null) {
                game.loadControllerKbmPreset(preset);
            }
            if (currentDialog != null) {
                currentDialog.dismiss();
            }
            showControllerKbmMenu(device);
        });

        currentDialog = new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_load_preset)
                .setView(list)
                .setNegativeButton(R.string.game_menu_cancel,
                        (dialog, which) -> showControllerKbmMenu(device))
                .create();
        currentDialog.show();
    }

    private void confirmControllerKbmReset(GameInputDevice device, ControllerKbmMapper mapper) {
        new AlertDialog.Builder(getThemedContext())
                .setTitle(R.string.game_menu_controller_kbm_reset)
                .setMessage(R.string.game_menu_controller_kbm_reset_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    game.resetControllerKbmMappings();
                    showControllerKbmMenu(device);
                })
                .setNegativeButton(R.string.game_menu_cancel,
                        (dialog, which) -> showControllerKbmMenu(device))
                .show();
    }

    private void showControllerKbmMenu(GameInputDevice device) {
        ControllerKbmMapper mapper = game.getControllerKbmMapper();
        if (mapper == null) {
            return;
        }
        List<MenuOption> options = new ArrayList<>();
        options.add(new MenuOption(getString(R.string.game_menu_controller_kbm_settings),
                () -> showControllerKbmSettings(device, mapper)));
        for (String source : mapper.getSources()) {
            options.add(new MenuOption(getControllerKbmSourceLabel(source) + "  →  " +
                    getControllerKbmActionLabel(mapper.getAction(source)),
                    () -> showControllerKbmActionPicker(device, mapper, source)));
        }
        options.add(new MenuOption(getString(R.string.game_menu_controller_kbm_add_button),
                () -> showControllerKbmAddButton(device, mapper)));
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel),
                () -> showAdvancedMenu(device)));

        LinearLayout layout = new LinearLayout(getThemedContext());
        layout.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(getThemedContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padding = (int) (12 * game.getResources().getDisplayMetrics().density);
        header.setPadding(padding, padding / 2, padding, padding / 2);

        ControllerKbmMapper.Preset matchingPreset = mapper.getMatchingPreset();
        TextView title = new TextView(getThemedContext());
        SpannableStringBuilder titleText = new SpannableStringBuilder(
                getString(R.string.game_menu_controller_kbm));
        titleText.append("\n").append(getString(R.string.game_menu_controller_kbm_current_preset)
                .replace("%1$s", ""));
        if (matchingPreset != null) {
            titleText.append(getControllerKbmPresetDisplayName(matchingPreset));
        }
        else {
            titleText.append(getString(R.string.game_menu_controller_kbm_temporary));
        }
        title.setText(titleText);
        title.setTextSize(18);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton savePreset = new ImageButton(getThemedContext());
        savePreset.setImageResource(android.R.drawable.ic_menu_save);
        savePreset.setBackgroundColor(0x00000000);
        savePreset.setContentDescription(
                getString(R.string.game_menu_controller_kbm_save_preset));
        savePreset.setOnClickListener(view -> {
            if (currentDialog != null) currentDialog.dismiss();
            showControllerKbmSavePreset(device, mapper);
        });
        header.addView(savePreset);

        ImageButton loadPreset = new ImageButton(getThemedContext());
        loadPreset.setImageResource(android.R.drawable.ic_menu_upload);
        loadPreset.setBackgroundColor(0x00000000);
        loadPreset.setContentDescription(
                getString(R.string.game_menu_controller_kbm_load_preset));
        loadPreset.setOnClickListener(view -> {
            if (currentDialog != null) currentDialog.dismiss();
            showControllerKbmLoadPreset(device, mapper);
        });
        header.addView(loadPreset);

        ImageButton reset = new ImageButton(getThemedContext());
        reset.setImageResource(android.R.drawable.ic_menu_revert);
        reset.setBackgroundColor(0x00000000);
        reset.setContentDescription(getString(R.string.game_menu_controller_kbm_reset));
        reset.setOnClickListener(view -> {
            if (currentDialog != null) currentDialog.dismiss();
            confirmControllerKbmReset(device, mapper);
        });
        header.addView(reset);
        layout.addView(header);

        ListView list = new ListView(getThemedContext());
        ArrayAdapter<MenuOption> adapter = createMenuAdapter(
                options, false, new HashSet<>(), () -> {});
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            MenuOption option = adapter.getItem(position);
            if (option == null) return;
            if (currentDialog != null) {
                currentDialog.dismiss();
                currentDialog = null;
            }
            run(option);
        });
        layout.addView(list);

        if (currentDialog != null) currentDialog.dismiss();
        currentDialog = new AlertDialog.Builder(getThemedContext()).setView(layout).create();
        currentDialog.show();
        Window window = currentDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void showAdvancedMenu(GameInputDevice device) {
        List<MenuOption> options = new ArrayList<>();
        if (game.allowChangeMouseMode) {
            options.add(new MenuOption(ADV_MOUSE_MODE, getString(R.string.game_menu_select_mouse_mode), true,
                    () -> game.selectMouseMode(dialogScreenContext)));
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
                game.getVolumeButtonModeLabel(), game::selectVolumeButtonMode));
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
            showSpecialKeysMenu();
        }));
        options.add(new MenuOption(ADV_TOUCH_SENSITIVITY, getString(R.string.game_menu_switch_touch_sensitivity_model), true,
                game::switchTouchSensitivity));
        if (device != null) {
            options.addAll(device.getGameMenuOptions());
        }
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_advanced), options.toArray(new MenuOption[0]));
    }

    private void showServerCmd(ArrayList<String> serverCmds) {
        List<MenuOption> options = new ArrayList<>();

        AtomicInteger index = new AtomicInteger(0);
        for (String str : serverCmds) {
            final int finalI = index.getAndIncrement();
            options.add(new MenuOption("server_" + finalI, "> " + str, true, () -> game.sendExecServerCmd(finalI)));
        }

        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));
        showMenuDialog(getString(R.string.game_menu_server_cmd), options.toArray(new MenuOption[0]));
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
                showServerCmd(serverCmds);
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
        options.add(new MenuOption(MENU_ADVANCED, getString(R.string.game_menu_advanced), true,
                () -> showAdvancedMenu(device)));
        options.add(new MenuOption(MENU_CANCEL, getString(R.string.game_menu_cancel), null));

        return options;
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

        layout.addView(listView);

        if (currentDialog != null) {
            currentDialog.dismiss();
        }
        currentDialog = new AlertDialog.Builder(getThemedContext()).setView(layout).create();
        currentDialog.setOnDismissListener(dialog -> {
            if (quickMenuEditMode) {
                saveQuickMenuState(visibleOptions, hiddenIds);
            }
        });
        currentDialog.show();

        Window window = currentDialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    public void hideMenu() {
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
