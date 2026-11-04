package moe.huzerovo.fncalls.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.LinkedHashSet;
import java.util.List;

import io.github.libxposed.service.XposedService;
import moe.huzerovo.fncalls.FnCallsApp;
import moe.huzerovo.fncalls.R;
import moe.huzerovo.fncalls.config.Config;
import moe.huzerovo.fncalls.config.Presets;
import moe.huzerovo.fncalls.hook.HookSpec;

/**
 * Dedicated rule editor. The main screen only shows a summary and an edit
 * button; rules are committed when the user taps "保存规则".
 */
public class RulesActivity extends AppCompatActivity {

    public static final String EXTRA_RULES = "rules";
    public static final String EXTRA_RESULT = "result_rules";
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_SYSTEM = "system";
    public static final String MODE_APP = "app";

    private TextInputEditText editRules;
    private TextInputLayout rulesInputLayout;
    private android.widget.TextView tvRulesStatus;
    private SharedPreferences prefs;
    private FnCallsApp.ServiceListener serviceListener;
    private boolean saving;
    private boolean appMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_rules);
        UiInsets.apply(this);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        editRules = findViewById(R.id.editRules);
        rulesInputLayout = findViewById(R.id.rulesInputLayout);
        tvRulesStatus = findViewById(R.id.tvRulesStatus);

        appMode = MODE_APP.equals(getIntent().getStringExtra(EXTRA_MODE));
        toolbar.setTitle(appMode ? R.string.title_app_rules : R.string.title_rules);
        rulesInputLayout.setHelperText(appMode
                ? getString(R.string.app_rules_hint) : getString(R.string.rules_hint));

        String initial = getIntent().getStringExtra(EXTRA_RULES);
        editRules.setText(initial == null ? "" : initial);
        editRules.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateRuleStatus();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        serviceListener = service -> runOnUiThread(() -> onServiceReady(service));
        FnCallsApp.addServiceListener(serviceListener);

        MaterialButton presets = findViewById(R.id.btnRulesPresets);
        if (appMode) {
            presets.setVisibility(android.view.View.GONE);
        } else {
            presets.setOnClickListener(v -> showPresetDialog());
        }
        findViewById(R.id.btnSaveRules).setOnClickListener(v -> saveRules());

        updateRuleStatus();
    }

    @Override
    protected void onDestroy() {
        if (serviceListener != null) {
            FnCallsApp.removeServiceListener(serviceListener);
        }
        super.onDestroy();
    }

    private void onServiceReady(XposedService service) {
        try {
            prefs = service.getRemotePreferences(Config.PREFS_NAME);
            updateRuleStatus();
        } catch (Throwable t) {
            prefs = null;
            tvRulesStatus.setText("Xposed 服务不可用：" + t);
        }
    }

    private void updateRuleStatus() {
        List<String> rules = Config.splitRules(text(editRules));
        int invalid = 0;
        for (String rule : rules) {
            if (HookSpec.parse(rule) == null) {
                invalid++;
            }
        }
        if (invalid > 0) {
            tvRulesStatus.setText(getString(R.string.rules_invalid, invalid));
        } else {
            tvRulesStatus.setText(getString(R.string.rules_count, rules.size()));
        }
    }

    private void showPresetDialog() {
        final List<Presets.Preset> presets = Presets.ALL;
        String[] labels = new String[presets.size()];
        final boolean[] checked = new boolean[presets.size()];
        for (int i = 0; i < presets.size(); i++) {
            labels[i] = presets.get(i).name + "\n" + presets.get(i).description;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.presets_title)
                .setMultiChoiceItems(labels, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    LinkedHashSet<String> rules = new LinkedHashSet<>(
                            Config.splitRules(text(editRules)));
                    int before = rules.size();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) {
                            rules.addAll(presets.get(i).rules);
                        }
                    }
                    editRules.setText(String.join("\n", rules));
                    Toast.makeText(this,
                            getString(R.string.presets_added, rules.size() - before),
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void saveRules() {
        if (saving) {
            return;
        }
        List<String> rules = Config.splitRules(text(editRules));
        for (String rule : rules) {
            if (HookSpec.parse(rule) == null) {
                Toast.makeText(this, getString(R.string.rules_invalid, 1),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (prefs == null) {
            Toast.makeText(this, R.string.service_connecting, Toast.LENGTH_LONG).show();
            return;
        }
        saving = true;
        boolean ok = appMode
                ? Config.saveAppRules(prefs, rules)
                : Config.saveSystemRules(prefs, rules);
        saving = false;
        if (!ok) {
            Toast.makeText(this, getString(R.string.rules_save_failed, "commit 失败"),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Intent result = new Intent();
        result.putExtra(EXTRA_RESULT, Config.join(rules));
        result.putExtra(EXTRA_MODE, appMode ? MODE_APP : MODE_SYSTEM);
        setResult(RESULT_OK, result);
        Toast.makeText(this, R.string.rules_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private static String text(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}
