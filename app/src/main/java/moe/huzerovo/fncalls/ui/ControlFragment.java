package moe.huzerovo.fncalls.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.Fragment;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;
import moe.huzerovo.fncalls.FnCallsApp;
import moe.huzerovo.fncalls.R;
import moe.huzerovo.fncalls.config.Config;

public class ControlFragment extends Fragment {

    private static final int MAX_VISIBLE_SCOPE = 5;

    private MaterialCardView cardHero;
    private TextView tvServiceStatus;
    private TextView tvServiceHint;
    private TextView tvRunState;
    private TextView tvRunHint;
    private MaterialButton btnToggle;
    private MaterialButton btnLaunch;
    private MaterialButton btnViewLogs;
    private LinearLayout listScope;
    private MaterialButton btnToggleScope;
    private TextView tvScopeCount;
    private TextView tvScopeWarning;
    private MaterialSwitch switchApp;
    private MaterialSwitch switchEagerScan;
    private EditText editMaxMethods;
    private MaterialSwitch switchConstructors;
    private TextView tvAppRuleSummary;
    private MaterialButton btnEditAppRules;
    private MaterialSwitch switchSystem;
    private TextView tvRuleSummary;
    private MaterialButton btnEditRules;
    private MaterialSwitch switchSecondary;
    private MaterialSwitch switchArgs;
    private MaterialSwitch switchReturn;
    private MaterialSwitch switchStack;
    private EditText editStackDepth;
    private EditText editMaxDepth;
    private MaterialSwitch switchXposed;
    private EditText editMaxLogMb;
    private MaterialButton btnSave;
    private MaterialButton btnReset;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<Intent> rulesLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), this::onRulesResult);

    private SharedPreferences prefs;
    private FnCallsApp.ServiceListener serviceListener;
    private boolean configLoaded;
    private boolean recording;
    private List<String> scopeTargets = new ArrayList<>();
    private List<String> currentRules = new ArrayList<>();
    private List<String> currentAppRules = new ArrayList<>();
    private List<String> currentAppPackages = new ArrayList<>();
    private boolean scopeExpanded;

    private final Runnable serviceTimeout = () -> {
        if (isAdded() && FnCallsApp.getService() == null) {
            showServiceUnavailable(null);
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_control, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        bindViews(view);

        btnToggle.setOnClickListener(v -> toggleRecording());
        btnLaunch.setOnClickListener(v -> launchScopedApp());
        btnViewLogs.setOnClickListener(v -> ((MainActivity) requireActivity()).selectTab(R.id.nav_logs));
        btnEditRules.setOnClickListener(v -> openRulesEditor(false));
        btnEditAppRules.setOnClickListener(v -> openRulesEditor(true));
        btnToggleScope.setOnClickListener(v -> {
            scopeExpanded = !scopeExpanded;
            renderScope();
        });
        btnSave.setOnClickListener(v -> {
            if (saveConfig(readConfigFromUi(), true)) {
                Snackbar.make(v, R.string.save_ok, Snackbar.LENGTH_SHORT).show();
            }
        });
        btnReset.setOnClickListener(v -> confirmReset());

        tvServiceStatus.setText(R.string.service_connecting);
        tvServiceHint.setVisibility(View.GONE);
        renderRunState();
        serviceListener = new FnCallsApp.ServiceListener() {
            @Override
            public void onService(XposedService service) {
                main.post(() -> {
                    if (isAdded()) {
                        onServiceReady(service);
                    }
                });
            }

            @Override
            public void onServiceLost() {
                main.post(() -> {
                    if (isAdded() && FnCallsApp.getService() == null) {
                        showServiceUnavailable(null);
                    }
                });
            }
        };
        main.postDelayed(serviceTimeout, 2000L);
    }

    @Override
    public void onStart() {
        super.onStart();
        FnCallsApp.addServiceListener(serviceListener);
    }

    @Override
    public void onStop() {
        FnCallsApp.removeServiceListener(serviceListener);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacks(serviceTimeout);
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        super.onResume();
        XposedService service = FnCallsApp.getService();
        if (service != null && configLoaded) {
            refreshScope(service, true);
        }
    }

    private void bindViews(View view) {
        cardHero = view.findViewById(R.id.cardHero);
        tvServiceStatus = view.findViewById(R.id.tvServiceStatus);
        tvServiceHint = view.findViewById(R.id.tvServiceHint);
        tvRunState = view.findViewById(R.id.tvRunState);
        tvRunHint = view.findViewById(R.id.tvRunHint);
        btnToggle = view.findViewById(R.id.btnToggle);
        btnLaunch = view.findViewById(R.id.btnLaunch);
        btnViewLogs = view.findViewById(R.id.btnViewLogs);
        listScope = view.findViewById(R.id.listScope);
        btnToggleScope = view.findViewById(R.id.btnToggleScope);
        tvScopeCount = view.findViewById(R.id.tvScopeCount);
        tvScopeWarning = view.findViewById(R.id.tvScopeEmpty);
        switchApp = view.findViewById(R.id.switchApp);
        switchEagerScan = view.findViewById(R.id.switchEagerScan);
        editMaxMethods = view.findViewById(R.id.editMaxMethods);
        switchConstructors = view.findViewById(R.id.switchConstructors);
        tvAppRuleSummary = view.findViewById(R.id.tvAppRuleSummary);
        btnEditAppRules = view.findViewById(R.id.btnEditAppRules);
        switchSystem = view.findViewById(R.id.switchSystem);
        tvRuleSummary = view.findViewById(R.id.tvRuleSummary);
        btnEditRules = view.findViewById(R.id.btnEditRules);
        switchSecondary = view.findViewById(R.id.switchSecondary);
        switchArgs = view.findViewById(R.id.switchArgs);
        switchReturn = view.findViewById(R.id.switchReturn);
        switchStack = view.findViewById(R.id.switchStack);
        editStackDepth = view.findViewById(R.id.editStackDepth);
        editMaxDepth = view.findViewById(R.id.editMaxDepth);
        switchXposed = view.findViewById(R.id.switchXposed);
        editMaxLogMb = view.findViewById(R.id.editMaxLogMb);
        btnSave = view.findViewById(R.id.btnSave);
        btnReset = view.findViewById(R.id.btnReset);
    }

    private void onServiceReady(XposedService service) {
        try {
            main.removeCallbacks(serviceTimeout);
            SharedPreferences remote = service.getRemotePreferences(Config.PREFS_NAME);
            prefs = remote;
            tvServiceStatus.setText(R.string.service_connected);
            tvServiceHint.setVisibility(View.GONE);
            if (configLoaded) {
                refreshScope(service, true);
                return;
            }
            List<String> scope = new ArrayList<>(service.getScope());
            scopeTargets = scope;
            Config config = remote.getAll().isEmpty()
                    ? Config.defaults() : Config.from(remote);
            Config scoped = config.withTargets(scopeTargets);
            populate(scoped);
            configLoaded = true;
            if (!config.targets.equals(scopeTargets)) {
                saveConfig(scoped, false);
            }
        } catch (Throwable t) {
            showServiceUnavailable(t);
        }
    }

    private void showServiceUnavailable(Throwable error) {
        prefs = null;
        tvServiceStatus.setText(R.string.service_unavailable_title);
        tvServiceHint.setText(R.string.service_unavailable_hint);
        tvServiceHint.setVisibility(View.VISIBLE);
        if (error != null) {
            tvRunHint.setText(getString(R.string.service_error, String.valueOf(error)));
        }
    }

    private void refreshScope(XposedService service, boolean persist) {
        try {
            List<String> previous = new ArrayList<>(scopeTargets);
            scopeTargets = new ArrayList<>(service.getScope());
            renderScope();
            if (persist && !previous.equals(scopeTargets)) {
                saveConfig(readConfigFromUi(), false);
            }
        } catch (Throwable t) {
            tvRunHint.setText("读取 Vector 作用域失败：" + t);
        }
    }

    private void populate(Config config) {
        recording = config.enabled;
        switchApp.setChecked(config.traceApp);
        switchEagerScan.setChecked(config.eagerScan);
        editMaxMethods.setText(String.valueOf(config.maxMethodsPerClass));
        switchConstructors.setChecked(config.includeConstructors);
        switchSystem.setChecked(config.traceSystem);
        switchSecondary.setChecked(config.traceSecondary);
        switchArgs.setChecked(config.logArgs);
        switchReturn.setChecked(config.logReturn);
        switchStack.setChecked(config.logStack);
        editStackDepth.setText(String.valueOf(config.stackDepth));
        editMaxDepth.setText(String.valueOf(config.maxDepth));
        switchXposed.setChecked(config.logXposed);
        editMaxLogMb.setText(String.valueOf(config.maxLogMb));
        currentRules = new ArrayList<>(config.systemRules);
        currentAppRules = new ArrayList<>(config.appRules);
        currentAppPackages = new ArrayList<>(config.appPackages);
        scopeTargets = new ArrayList<>(config.targets);
        renderScope();
        updateRuleSummary();
        updateAppRuleSummary();
        tvServiceStatus.setText(R.string.service_connected);
        renderRunState();
    }

    private Config readConfigFromUi() {
        return new Config(
                recording,
                new ArrayList<>(scopeTargets),
                switchSecondary.isChecked(),
                switchApp.isChecked(),
                new ArrayList<>(currentAppPackages),
                new ArrayList<>(currentAppRules),
                switchEagerScan.isChecked(),
                switchSystem.isChecked(),
                new ArrayList<>(currentRules),
                switchArgs.isChecked(),
                switchReturn.isChecked(),
                switchStack.isChecked(),
                switchXposed.isChecked(),
                switchConstructors.isChecked(),
                parseInt(editStackDepth, Config.DEFAULT_STACK_DEPTH),
                parseInt(editMaxDepth, Config.DEFAULT_MAX_DEPTH),
                parseInt(editMaxMethods, Config.DEFAULT_MAX_METHODS),
                parseInt(editMaxLogMb, Config.DEFAULT_MAX_LOG_MB)
        );
    }

    private void toggleRecording() {
        boolean start = !recording;
        if (!saveConfig(readConfigFromUi().withEnabled(start), true)) {
            return;
        }
        if (!start) {
            Snackbar.make(btnToggle, R.string.stopped_done, Snackbar.LENGTH_SHORT).show();
        } else if (scopeTargets.isEmpty()) {
            Snackbar.make(btnToggle, R.string.started_no_scope, Snackbar.LENGTH_LONG).show();
        } else {
            Snackbar.make(btnToggle, R.string.started_done, Snackbar.LENGTH_SHORT).show();
        }
    }

    private void renderRunState() {
        boolean running = recording;
        tvRunState.setText(running ? R.string.run_active : R.string.run_idle);
        tvRunHint.setText(running ? R.string.run_active_hint : R.string.run_idle_hint);
        btnToggle.setText(running ? R.string.btn_stop : R.string.btn_start);
        btnToggle.setIcon(AppCompatResources.getDrawable(requireContext(),
                running ? R.drawable.ic_stop : R.drawable.ic_play));
        btnToggle.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(btnToggle,
                running ? com.google.android.material.R.attr.colorError
                        : androidx.appcompat.R.attr.colorPrimary)));
        int onColor = MaterialColors.getColor(btnToggle,
                running ? com.google.android.material.R.attr.colorOnError
                        : com.google.android.material.R.attr.colorOnPrimary);
        btnToggle.setTextColor(onColor);
        btnToggle.setIconTint(ColorStateList.valueOf(onColor));
        cardHero.setCardBackgroundColor(MaterialColors.getColor(cardHero,
                running ? com.google.android.material.R.attr.colorPrimaryContainer
                        : com.google.android.material.R.attr.colorSurfaceContainerHigh));
    }

    private void renderScope() {
        listScope.removeAllViews();
        int total = scopeTargets.size();
        if (total <= MAX_VISIBLE_SCOPE) {
            scopeExpanded = false;
        }
        int visible = scopeExpanded ? total : Math.min(total, MAX_VISIBLE_SCOPE);
        for (int i = 0; i < visible; i++) {
            String target = scopeTargets.get(i);
            View row = getLayoutInflater().inflate(R.layout.item_scope_app, listScope, false);
            ImageView icon = row.findViewById(R.id.imgScopeIcon);
            TextView name = row.findViewById(R.id.tvScopeName);
            TextView packageName = row.findViewById(R.id.tvScopePackage);
            name.setText(loadAppLabel(target));
            packageName.setText(target);
            icon.setImageDrawable(loadAppIcon(target));
            listScope.addView(row);
        }

        if (total > MAX_VISIBLE_SCOPE) {
            btnToggleScope.setVisibility(View.VISIBLE);
            btnToggleScope.setText(scopeExpanded
                    ? getString(R.string.scope_show_less)
                    : getString(R.string.scope_show_more, total - MAX_VISIBLE_SCOPE));
            btnToggleScope.setIconResource(scopeExpanded
                    ? R.drawable.ic_arrow_drop_up
                    : R.drawable.ic_arrow_drop_down);
        } else {
            btnToggleScope.setVisibility(View.GONE);
        }

        tvScopeCount.setText(getString(R.string.scope_summary, total));
        if (total == 0) {
            tvScopeWarning.setText(R.string.scope_none);
            tvScopeWarning.setVisibility(View.VISIBLE);
        } else if (scopeTargets.contains("*")) {
            tvScopeWarning.setText(R.string.scope_wildcard);
            tvScopeWarning.setVisibility(View.VISIBLE);
        } else {
            tvScopeWarning.setVisibility(View.GONE);
        }
    }

    private String loadAppLabel(String packageName) {
        try {
            PackageManager pm = requireContext().getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            CharSequence label = pm.getApplicationLabel(info);
            if (label != null && label.length() > 0) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return packageName;
    }

    private android.graphics.drawable.Drawable loadAppIcon(String packageName) {
        try {
            return requireContext().getPackageManager().getApplicationIcon(packageName);
        } catch (Throwable t) {
            return AppCompatResources.getDrawable(requireContext(), R.drawable.ic_launcher);
        }
    }

    private void updateRuleSummary() {
        tvRuleSummary.setText(getString(R.string.rule_summary, currentRules.size()));
    }

    private void updateAppRuleSummary() {
        tvAppRuleSummary.setText(getString(R.string.rule_summary, currentAppRules.size()));
    }

    private boolean saveConfig(Config config, boolean notify) {
        if (prefs == null) {
            if (notify) {
                Toast.makeText(requireContext(), R.string.service_unavailable, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        SharedPreferences.Editor editor = prefs.edit();
        config.saveTo(editor);
        editor.putBoolean("__initialized", true);
        boolean ok;
        try {
            ok = editor.commit();
        } catch (Throwable t) {
            if (notify) {
                Toast.makeText(requireContext(), getString(R.string.save_failed, String.valueOf(t)),
                        Toast.LENGTH_LONG).show();
            }
            return false;
        }
        if (!ok) {
            if (notify) {
                Toast.makeText(requireContext(), R.string.save_failed_commit, Toast.LENGTH_LONG).show();
            }
            return false;
        }
        recording = config.enabled;
        renderRunState();
        return true;
    }

    private void openRulesEditor(boolean appRules) {
        Intent intent = new Intent(requireContext(), RulesActivity.class);
        intent.putExtra(RulesActivity.EXTRA_MODE,
                appRules ? RulesActivity.MODE_APP : RulesActivity.MODE_SYSTEM);
        intent.putExtra(RulesActivity.EXTRA_RULES,
                Config.join(appRules ? currentAppRules : currentRules));
        rulesLauncher.launch(intent);
    }

    private void onRulesResult(ActivityResult result) {
        Intent data = result.getData();
        if (result.getResultCode() != Activity.RESULT_OK || data == null) {
            return;
        }
        // RulesActivity has already persisted the list; mirror it locally.
        String returned = data.getStringExtra(RulesActivity.EXTRA_RESULT);
        if (RulesActivity.MODE_APP.equals(data.getStringExtra(RulesActivity.EXTRA_MODE))) {
            currentAppRules = Config.splitRules(returned);
            updateAppRuleSummary();
        } else {
            currentRules = Config.splitRules(returned);
            updateRuleSummary();
        }
    }

    private void launchScopedApp() {
        if (scopeTargets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.scope_empty, Toast.LENGTH_LONG).show();
            return;
        }
        if (scopeTargets.size() == 1) {
            launchPackage(scopeTargets.get(0));
            return;
        }
        String[] packages = scopeTargets.toArray(new String[0]);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.launch_choose_title)
                .setItems(packages, (dialog, which) -> launchPackage(packages[which]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchPackage(String packageName) {
        try {
            Intent launch = requireContext().getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                Toast.makeText(requireContext(), R.string.no_launcher, Toast.LENGTH_SHORT).show();
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Throwable t) {
            Toast.makeText(requireContext(), R.string.no_launcher, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Config defaults = Config.defaults()
                            .withTargets(new ArrayList<>(scopeTargets))
                            .withEnabled(recording);
                    populate(defaults);
                    saveConfig(defaults, false);
                    Toast.makeText(requireContext(), R.string.reset_done, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static int parseInt(EditText editText, int fallback) {
        String value = text(editText);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String text(EditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}
