package me.meiser79.intercomsatellite;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class SettingsActivity extends AppCompatActivity {
    private static final String[] THEME_VALUES = {"system", "light", "dark"};
    private static final String[] VIDEO_QUALITY_VALUES = {"low", "medium", "high"};

    private final PermissionHelper permissions = new PermissionHelper(this);
    private Config config;
    private TextInputLayout nameLayout, keyLayout;
    private static final String REPOSITORY = "github.com/meiser79/intercom-satellite";

    private TextInputEditText fieldName, fieldKey;
    private MaterialSwitch switchTls, switchAnnouncements, switchVideo, switchBoot, switchAndroidDnd,
            switchPushToTalk, switchSpeakerDefault;
    private View rowPhone, rowBattery, rowRingtone, rowQuiet, rowFullScreen, rowTheme, rowVideoQuality,
            rowExport, rowImport, rowVersion, rowMaintainer, rowGithub;
    /** Name, key or TLS changed: the intercom restarts with them when the screen is left. */
    private boolean identityChanged;
    /** Without a key the intercom never ran; the first key starts it. */
    private boolean firstSetup;

    private final ActivityResultLauncher<String> exportFile =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument("application/json"),
                    uri -> {
                        if (uri != null) exportTo(uri);
                    });
    private final ActivityResultLauncher<String[]> importFile =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) confirmImport(uri);
                    });

    private final ActivityResultLauncher<Intent> ringtonePicker =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() != RESULT_OK || result.getData() == null)
                            return;
                        config.setAppRingtone(
                                Ringtones.picked(
                                        result.getData(), Settings.System.DEFAULT_RINGTONE_URI));
                        bindRows();
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        config = new Config(this);
        setContentView(R.layout.activity_settings);
        Ui.setupSubScreen(this, R.string.settings_title);
        Ui.applyBottomInsets(findViewById(R.id.scroll));

        nameLayout = findViewById(R.id.layout_name);
        keyLayout = findViewById(R.id.layout_key);
        fieldName = findViewById(R.id.field_name);
        fieldKey = findViewById(R.id.field_key);
        switchTls = findViewById(R.id.switch_tls);
        switchAnnouncements = findViewById(R.id.switch_announcements);
        switchVideo = findViewById(R.id.switch_video);
        switchBoot = findViewById(R.id.switch_boot);
        switchAndroidDnd = findViewById(R.id.switch_android_dnd);
        switchPushToTalk = findViewById(R.id.switch_push_to_talk);
        switchSpeakerDefault = findViewById(R.id.switch_speaker_default);
        rowVideoQuality = findViewById(R.id.row_video_quality);
        rowPhone = findViewById(R.id.row_phone);
        rowBattery = findViewById(R.id.row_battery);
        rowRingtone = findViewById(R.id.row_ringtone);
        rowQuiet = findViewById(R.id.row_quiet);
        rowFullScreen = findViewById(R.id.row_fullscreen);
        rowTheme = findViewById(R.id.row_theme);
        rowExport = findViewById(R.id.row_export);
        rowImport = findViewById(R.id.row_import);
        rowVersion = findViewById(R.id.row_version);
        rowMaintainer = findViewById(R.id.row_maintainer);
        rowGithub = findViewById(R.id.row_github);

        if (savedInstanceState == null) {
            fieldName.setText(config.name());
            fieldKey.setText(config.key());
            switchTls.setChecked(config.tls());
            switchAnnouncements.setChecked(config.acceptAnnouncements());
            switchVideo.setChecked(config.video());
            switchBoot.setChecked(config.autoStart());
            switchAndroidDnd.setChecked(config.followAndroidDnd());
            switchPushToTalk.setChecked(!config.handsFreeDefault());
            switchSpeakerDefault.setChecked(config.speakerDefault());
        } else {
            identityChanged = savedInstanceState.getBoolean("identityChanged");
        }
        firstSetup =
                config.key().isEmpty()
                        || (savedInstanceState != null
                                && savedInstanceState.getBoolean("firstSetup"));

        // Every change is saved as it is made.
        TextWatcher identity =
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                    public void onTextChanged(CharSequence s, int start, int before, int count) {}

                    public void afterTextChanged(Editable s) {
                        saveIdentity();
                    }
                };
        fieldName.addTextChangedListener(identity);
        fieldKey.addTextChangedListener(identity);
        switchTls.setOnCheckedChangeListener((b, on) -> saveIdentity());
        switchBoot.setOnCheckedChangeListener((b, on) -> config.setAutoStart(on));
        switchAnnouncements.setOnCheckedChangeListener((b, on) -> config.setAcceptAnnouncements(on));
        switchVideo.setOnCheckedChangeListener((b, on) -> config.setVideo(on));
        switchAndroidDnd.setOnCheckedChangeListener(
                (b, on) -> {
                    config.setFollowAndroidDnd(on);
                    refreshEngine();
                });
        switchPushToTalk.setOnCheckedChangeListener((b, on) -> config.setHandsFreeDefault(!on));
        switchSpeakerDefault.setOnCheckedChangeListener((b, on) -> config.setSpeakerDefault(on));
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("identityChanged", identityChanged);
        out.putBoolean("firstSetup", firstSetup);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (!identityChanged || isChangingConfigurations()) return;
        identityChanged = false;
        if (IntercomService.instance != null && IntercomService.instance.engine != null)
            IntercomControl.reload(this);
        else if (firstSetup && !config.key().isEmpty()) {
            firstSetup = false;
            IntercomControl.enable(this, permissions);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindRows();
    }

    private void bindRows() {
        Ui.bindRow(
                rowPhone,
                R.string.row_phone,
                getString(
                        PhoneIntegration.ready(this)
                                ? R.string.state_active
                                : R.string.state_not_set_up),
                () -> PhoneSetupFlow.start(this, permissions));
        // Background reception and calls on the lock screen only show while they are missing.
        rowBattery.setVisibility(SetupChecklist.batteryMissing(this) ? View.VISIBLE : View.GONE);
        Ui.bindRow(
                rowBattery,
                R.string.row_battery,
                getString(R.string.state_optimized),
                () -> SetupChecklist.battery(this));
        // With the phone app ringing, Android plays its own ringtone and ignores this one.
        rowRingtone.setVisibility(PhoneIntegration.ready(this) ? View.GONE : View.VISIBLE);
        Ui.bindRow(
                rowRingtone,
                R.string.row_ringtone,
                Ringtones.title(this, config.appRingtone(), R.string.ringtone_default),
                () ->
                        ringtonePicker.launch(
                                Ringtones.picker(
                                        this,
                                        config.appRingtone(),
                                        Settings.System.DEFAULT_RINGTONE_URI)));
        Ui.bindRow(
                rowQuiet,
                R.string.row_quiet,
                config.quietEnabled()
                        ? clock(config.quietStart()) + " – " + clock(config.quietEnd())
                        : getString(R.string.state_off),
                this::quietDialog);
        rowFullScreen.setVisibility(
                SetupChecklist.fullScreenMissing(this) ? View.VISIBLE : View.GONE);
        Ui.bindRow(
                rowFullScreen,
                R.string.row_fullscreen,
                getString(R.string.state_not_allowed),
                () -> SetupChecklist.fullScreen(this));
        String[] qualityLabels = getResources().getStringArray(R.array.video_quality_options);
        Ui.bindRow(
                rowVideoQuality,
                R.string.row_video_quality,
                qualityLabels[videoQualityIndex()],
                this::videoQualityDialog);
        String[] themeLabels = getResources().getStringArray(R.array.theme_options);
        Ui.bindRow(rowTheme, R.string.row_theme, themeLabels[themeIndex()], this::themeDialog);
        Ui.bindRow(
                rowExport,
                R.string.row_export,
                getString(R.string.row_export_hint),
                () -> exportFile.launch("intercom-satellite-settings.json"));
        Ui.bindRow(
                rowImport,
                R.string.row_import,
                getString(R.string.row_import_hint),
                () ->
                        importFile.launch(
                                new String[] {
                                    "application/json", "application/octet-stream", "text/plain"
                                }));
        ((ImageView) rowVersion.findViewById(R.id.row_icon)).setImageResource(R.drawable.ic_info);
        ((ImageView) rowMaintainer.findViewById(R.id.row_icon))
                .setImageResource(R.drawable.ic_person);
        ((ImageView) rowGithub.findViewById(R.id.row_icon)).setImageResource(R.drawable.ic_github);
        Ui.bindRow(rowVersion, R.string.row_version, versionName(), null);
        Ui.bindRow(rowMaintainer, R.string.row_maintainer, "Peter Meiser", null);
        Ui.bindRow(
                rowGithub,
                R.string.row_github,
                REPOSITORY,
                () -> {
                    try {
                        startActivity(
                                new Intent(Intent.ACTION_VIEW, Uri.parse("https://" + REPOSITORY)));
                    } catch (ActivityNotFoundException e) {
                        // No browser: the address stays on screen.
                    }
                });
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- export and import

    private void exportTo(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new Exception("No file");
            out.write(
                    SettingsTransfer.export(config.prefs, config.key())
                            .toString(2)
                            .getBytes(StandardCharsets.UTF_8));
            Ui.toast(this, R.string.toast_export_done);
        } catch (Exception e) {
            Ui.toast(this, R.string.toast_export_failed);
        }
    }

    private void confirmImport(Uri uri) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.import_title)
                .setMessage(R.string.import_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.import_action, (d, w) -> importFrom(uri))
                .show();
    }

    private void importFrom(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("No file");
            // A settings file is a few kilobytes; anything past a megabyte is not one.
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                data.write(buffer, 0, n);
                if (data.size() > 1 << 20) throw new Exception("Too large");
            }
            String key =
                    SettingsTransfer.apply(
                            config.prefs,
                            new JSONObject(data.toString(StandardCharsets.UTF_8.name())));
            config.save(config.name(), key, config.tls());
        } catch (Exception e) {
            Ui.toast(this, R.string.toast_import_failed);
            return;
        }
        // The screen shows the new values; the listeners find nothing changed to save again.
        fieldName.setText(config.name());
        fieldKey.setText(config.key());
        switchTls.setChecked(config.tls());
        switchAnnouncements.setChecked(config.acceptAnnouncements());
        switchVideo.setChecked(config.video());
        switchBoot.setChecked(config.autoStart());
        switchAndroidDnd.setChecked(config.followAndroidDnd());
        switchPushToTalk.setChecked(!config.handsFreeDefault());
        switchSpeakerDefault.setChecked(config.speakerDefault());
        bindRows();
        identityChanged = true;
        Shortcuts.update(this);
        KioskWidget.refreshAll(this);
        Ui.toast(this, R.string.toast_import_done);
        App.applyThemeMode(this);
    }

    // ---------------------------------------------------------------- save

    /** Saves name, key and TLS while both fields are valid; otherwise the field says why. */
    private void saveIdentity() {
        String name = String.valueOf(fieldName.getText()).trim();
        String key = String.valueOf(fieldKey.getText()).trim();
        boolean nameOk = !name.isEmpty() && name.length() <= 48;
        boolean keyOk = !key.isEmpty() && key.length() <= 4096;
        nameLayout.setError(nameOk ? null : getString(R.string.error_name));
        keyLayout.setError(keyOk ? null : getString(R.string.error_key));
        if (!nameOk || !keyOk) return;
        boolean tls = switchTls.isChecked();
        if (name.equals(config.name()) && key.equals(config.key()) && tls == config.tls()) return;
        try {
            config.save(name, key, tls);
            identityChanged = true;
        } catch (Exception e) {
            Ui.toast(this, R.string.toast_settings_save_failed);
        }
    }

    /** Settings the running intercom reads as it goes; the home screen and tiles show them. */
    private void refreshEngine() {
        IntercomService service = IntercomService.instance;
        if (service != null && service.engine != null) service.engine.changed();
    }

    // ---------------------------------------------------------------- video

    private int videoQualityIndex() {
        String quality = config.videoQuality();
        for (int i = 0; i < VIDEO_QUALITY_VALUES.length; i++)
            if (VIDEO_QUALITY_VALUES[i].equals(quality)) return i;
        return 1;
    }

    private void videoQualityDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.row_video_quality)
                .setSingleChoiceItems(
                        R.array.video_quality_options,
                        videoQualityIndex(),
                        (d, which) -> {
                            config.setVideoQuality(VIDEO_QUALITY_VALUES[which]);
                            d.dismiss();
                            bindRows();
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- theme

    private int themeIndex() {
        String mode = config.themeMode();
        for (int i = 0; i < THEME_VALUES.length; i++) if (THEME_VALUES[i].equals(mode)) return i;
        return 0;
    }

    private void themeDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.row_theme)
                .setSingleChoiceItems(
                        getResources().getStringArray(R.array.theme_options),
                        themeIndex(),
                        (dialog, choice) -> {
                            config.setThemeMode(THEME_VALUES[choice]);
                            dialog.dismiss();
                            App.applyThemeMode(this); // recreates visible activities
                        })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- quiet hours

    private static String clock(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private void quietDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_quiet_hours, null);
        MaterialSwitch enabled = content.findViewById(R.id.switch_quiet);
        MaterialButton from = content.findViewById(R.id.btn_quiet_start);
        MaterialButton until = content.findViewById(R.id.btn_quiet_end);
        ChipGroup dayGroup = content.findViewById(R.id.day_group);
        MaterialButton exceptionsButton = content.findViewById(R.id.btn_quiet_exceptions);

        enabled.setChecked(config.quietEnabled());
        int[] start = {config.quietStart()};
        int[] end = {config.quietEnd()};
        from.setText(getString(R.string.quiet_from, clock(start[0])));
        until.setText(getString(R.string.quiet_until, clock(end[0])));
        from.setOnClickListener(
                v ->
                        pickTime(
                                start,
                                () ->
                                        from.setText(
                                                getString(R.string.quiet_from, clock(start[0])))));
        until.setOnClickListener(
                v ->
                        pickTime(
                                end,
                                () ->
                                        until.setText(
                                                getString(R.string.quiet_until, clock(end[0])))));

        String[] days = getResources().getStringArray(R.array.weekdays_short);
        int mask = config.quietDays();
        for (int i = 0; i < days.length; i++) {
            Chip chip =
                    (Chip) LayoutInflater.from(this).inflate(R.layout.chip_choice, dayGroup, false);
            chip.setText(days[i]);
            chip.setChecked((mask & (1 << i)) != 0);
            dayGroup.addView(chip);
        }

        Set<String> exceptions = new HashSet<>(config.exceptions());
        exceptionsButton.setOnClickListener(v -> chooseExceptions(exceptions));

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.row_quiet)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(
                        R.string.save,
                        (d, w) -> {
                            int daysMask = 0;
                            for (int i = 0; i < dayGroup.getChildCount(); i++) {
                                if (((Chip) dayGroup.getChildAt(i)).isChecked()) daysMask |= 1 << i;
                            }
                            config.saveQuietHours(
                                    enabled.isChecked(), daysMask, start[0], end[0], exceptions);
                            bindRows();
                        })
                .show();
    }

    private void pickTime(int[] minutes, Runnable changed) {
        MaterialTimePicker picker =
                new MaterialTimePicker.Builder()
                        .setTitleText(R.string.pick_time)
                        .setTimeFormat(TimeFormat.CLOCK_24H)
                        .setHour(minutes[0] / 60)
                        .setMinute(minutes[0] % 60)
                        .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                        .build();
        picker.addOnPositiveButtonClickListener(
                v -> {
                    minutes[0] = picker.getHour() * 60 + picker.getMinute();
                    changed.run();
                });
        picker.show(getSupportFragmentManager(), "time_picker");
    }

    private void chooseExceptions(Set<String> exceptions) {
        JSONArray known = config.known();
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (int i = 0; i < known.length(); i++) {
            JSONObject p = known.optJSONObject(i);
            if (p == null || p.optString("id").isEmpty()) continue;
            ids.add(p.optString("id"));
            names.add(config.display(Peer.from(p, p.optString("host"))));
        }
        if (ids.isEmpty()) {
            Ui.toast(this, R.string.toast_connect_kiosk_first);
            return;
        }
        boolean[] selected = new boolean[ids.size()];
        for (int i = 0; i < selected.length; i++) selected[i] = exceptions.contains(ids.get(i));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.quiet_exceptions_title)
                .setMultiChoiceItems(
                        names.toArray(new String[0]),
                        selected,
                        (d, i, on) -> {
                            if (on) exceptions.add(ids.get(i));
                            else exceptions.remove(ids.get(i));
                        })
                .setPositiveButton(R.string.done, null)
                .show();
    }
}
