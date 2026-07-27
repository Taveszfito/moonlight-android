package com.limelight;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.limelight.binding.input.ControllerKbmMapper;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.UiHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ControllerKbmPresetsActivity extends AppCompatActivity {
    private static final int REQUEST_IMPORT_PRESET = 781;
    private static final int REQUEST_EXPORT_PRESET = 782;
    private static final int MAX_PRESET_FILE_SIZE = 1024 * 1024;
    private static final String STATE_PENDING_PRESET_EXPORT = "pendingPresetExport";

    private ControllerKbmMapper mapper;
    private LinearLayout presetContainer;
    private View presetScrollView;
    private View emptyState;
    private String pendingPresetExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.controller_kbm_presets_title);
        setContentView(R.layout.activity_controller_kbm_presets);

        mapper = new ControllerKbmMapper(this, null,
                PreferenceConfiguration.readPreferences(this));
        if (savedInstanceState != null) {
            pendingPresetExport = savedInstanceState.getString(STATE_PENDING_PRESET_EXPORT);
        }
        presetContainer = findViewById(R.id.controllerKbmPresetsContainer);
        presetScrollView = findViewById(R.id.controllerKbmPresetsScrollView);
        emptyState = findViewById(R.id.controllerKbmPresetsEmptyState);
        findViewById(R.id.importControllerKbmPresetFab)
                .setOnClickListener(view -> importPreset());

        refreshPresets();
        UiHelper.notifyNewRootView(this);
    }

    private void refreshPresets() {
        presetContainer.removeAllViews();
        List<ControllerKbmMapper.Preset> presets = mapper.getPresets();
        boolean empty = presets.isEmpty();
        presetScrollView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        for (ControllerKbmMapper.Preset preset : presets) {
            presetContainer.addView(createPresetRow(preset));
        }
    }

    private View createPresetRow(ControllerKbmMapper.Preset preset) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(4), dp(8));

        TextView name = new TextView(this);
        name.setText(preset.getDisplayName());
        name.setTextColor(Color.WHITE);
        name.setTextSize(17);
        name.setSingleLine(true);
        name.setOnClickListener(view -> {
            mapper.loadPreset(preset);
            Toast.makeText(this, getString(
                            R.string.controller_kbm_presets_loaded,
                            preset.getDisplayName()),
                    Toast.LENGTH_SHORT).show();
        });
        row.addView(name, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ImageButton export = new ImageButton(this);
        export.setImageResource(android.R.drawable.ic_menu_share);
        export.setBackgroundColor(Color.TRANSPARENT);
        export.setContentDescription(getString(R.string.controller_kbm_presets_export_named,
                preset.getDisplayName()));
        export.setOnClickListener(view -> exportPreset(preset));
        row.addView(export, new LinearLayout.LayoutParams(dp(48), dp(48)));

        ImageButton delete = new ImageButton(this);
        delete.setImageResource(android.R.drawable.ic_menu_delete);
        delete.setBackgroundColor(Color.TRANSPARENT);
        delete.setContentDescription(getString(R.string.controller_kbm_presets_delete_named,
                preset.getDisplayName()));
        delete.setOnClickListener(view -> confirmDeletePreset(preset));
        row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private void importPreset() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Some Android document providers report JSON files as text/plain or
        // application/octet-stream, even when this app created the file.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                "application/json", "text/json", "text/plain", "application/octet-stream"
        });
        try {
            startActivityForResult(intent, REQUEST_IMPORT_PRESET);
        }
        catch (Exception e) {
            Toast.makeText(this, R.string.game_menu_controller_kbm_import_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void exportPreset(ControllerKbmMapper.Preset preset) {
        try {
            pendingPresetExport = mapper.exportPreset(preset);
            String safeName = preset.getDisplayName().replaceAll("[\\\\/:*?\"<>|]", "_");
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, safeName + ".artemis-kbm.json");
            startActivityForResult(intent, REQUEST_EXPORT_PRESET);
        }
        catch (Exception e) {
            pendingPresetExport = null;
            Toast.makeText(this, R.string.game_menu_controller_kbm_export_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void confirmDeletePreset(ControllerKbmMapper.Preset preset) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.controller_kbm_presets_delete)
                .setMessage(getString(R.string.controller_kbm_presets_delete_confirm,
                        preset.getDisplayName()))
                .setPositiveButton(R.string.profile_manager_delete, (dialog, which) -> {
                    mapper.deletePreset(preset.id);
                    refreshPresets();
                    Toast.makeText(this, getString(
                                    R.string.controller_kbm_presets_deleted,
                                    preset.getDisplayName()),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_PRESET && requestCode != REQUEST_EXPORT_PRESET) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingPresetExport = null;
            return;
        }

        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_PRESET) {
            writePreset(uri);
        }
        else {
            readPreset(uri);
        }
    }

    private void writePreset(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null || pendingPresetExport == null) {
                throw new IllegalStateException("Unable to open preset output");
            }
            output.write(pendingPresetExport.getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, R.string.game_menu_controller_kbm_export_success,
                    Toast.LENGTH_SHORT).show();
        }
        catch (Exception e) {
            Toast.makeText(this, R.string.game_menu_controller_kbm_export_failed,
                    Toast.LENGTH_LONG).show();
        }
        finally {
            pendingPresetExport = null;
        }
    }

    private void readPreset(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri);
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
            String serialized = output.toString(StandardCharsets.UTF_8.name());
            if (!serialized.isEmpty() && serialized.charAt(0) == '\uFEFF') {
                serialized = serialized.substring(1);
            }
            ControllerKbmMapper.Preset imported = mapper.importPreset(serialized);
            Toast.makeText(this, getString(
                            R.string.game_menu_controller_kbm_import_success,
                            imported.getDisplayName()),
                    Toast.LENGTH_SHORT).show();
            refreshPresets();
        }
        catch (Exception e) {
            Toast.makeText(this, R.string.game_menu_controller_kbm_import_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_PENDING_PRESET_EXPORT, pendingPresetExport);
    }
}
