package com.limelight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.limelight.binding.input.GameInputDevice;
import com.limelight.binding.input.KeyboardTranslator;
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
    private static final String ADV_SEND_KEYS = "advanced_send_keys";
    private static final String ADV_TOUCH_SENSITIVITY = "advanced_touch_sensitivity";

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
