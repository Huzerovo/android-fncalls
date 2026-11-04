package moe.huzerovo.fncalls.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.ListPopupWindow;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import moe.huzerovo.fncalls.BuildConfig;
import moe.huzerovo.fncalls.R;
import moe.huzerovo.fncalls.log.TraceFiles;

public class LogFragment extends Fragment {

    private static final int MAX_LINES = 3000;
    private static final int KIND_ALL = 0;
    private static final int KIND_CALL = 1;
    private static final int KIND_ERROR = 2;
    private static final int KIND_FRAMEWORK = 3;

    private View appPicker;
    private ImageView imgSelectedAppIcon;
    private TextView tvSelectedAppName;
    private TextView tvSelectedAppPackage;
    private MaterialButton btnRefresh;
    private MaterialButton btnSearch;
    private TextInputLayout searchLayout;
    private TextInputEditText editFilter;
    private ChipGroup chipKind;
    private TextView tvStatus;
    private TextView tvEmpty;
    private RecyclerView recyclerLogs;
    private MaterialButton btnExport;
    private MaterialButton btnShare;
    private MaterialButton btnClear;

    private final List<AppLog> apps = new ArrayList<>();
    private final List<String> allLines = new ArrayList<>();
    private final List<String> visibleLines = new ArrayList<>();
    private LineAdapter lineAdapter;
    private ListPopupWindow appPopup;
    private int selectedIndex = -1;
    private String selectedApp;
    private boolean searchOpen;
    private boolean firstResume = true;
    private File pendingExport;
    private final AtomicInteger loadGeneration = new AtomicInteger();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), this::exportTo);

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logs, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        appPicker = view.findViewById(R.id.appPicker);
        imgSelectedAppIcon = view.findViewById(R.id.imgSelectedAppIcon);
        tvSelectedAppName = view.findViewById(R.id.tvSelectedAppName);
        tvSelectedAppPackage = view.findViewById(R.id.tvSelectedAppPackage);
        btnRefresh = view.findViewById(R.id.btnRefresh);
        btnSearch = view.findViewById(R.id.btnSearch);
        searchLayout = view.findViewById(R.id.searchLayout);
        editFilter = view.findViewById(R.id.editFilter);
        chipKind = view.findViewById(R.id.chipKind);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvEmpty = view.findViewById(R.id.tvEmpty);
        recyclerLogs = view.findViewById(R.id.recyclerLogs);
        btnExport = view.findViewById(R.id.btnExport);
        btnShare = view.findViewById(R.id.btnShare);
        btnClear = view.findViewById(R.id.btnClear);

        appPicker.setOnClickListener(v -> showAppPopup());

        lineAdapter = new LineAdapter(requireContext());
        recyclerLogs.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerLogs.setAdapter(lineAdapter);

        editFilter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        chipKind.setOnCheckedStateChangeListener((group, checkedIds) -> applyFilter());

        btnRefresh.setOnClickListener(v -> reload(true));
        btnSearch.setOnClickListener(v -> setSearchOpen(!searchOpen));
        btnExport.setOnClickListener(v -> startExport());
        btnShare.setOnClickListener(v -> shareCurrent());
        btnClear.setOnClickListener(v -> confirmClear());

        reload(true);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
            return;
        }
        reload(false);
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden && !firstResume) {
            reload(false);
        }
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private void reload(boolean force) {
        String previous = selectedApp;
        List<AppLog> latest = listApps();
        if (!force && sameApps(latest)) {
            File current = currentFile();
            if (current != null && current.isFile()) {
                load(current, false);
            }
            return;
        }

        apps.clear();
        apps.addAll(latest);
        if (apps.isEmpty()) {
            selectedIndex = -1;
            selectedApp = null;
            clearSelectedApp();
            allLines.clear();
            applyFilter();
            tvEmpty.setText(R.string.logs_no_file);
            tvEmpty.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.logs_no_file);
            updateActions();
            return;
        }

        int index = indexOfApp(previous);
        selectApp(index >= 0 ? index : 0);
    }

    private void selectApp(int index) {
        if (index < 0 || index >= apps.size()) {
            return;
        }
        selectedIndex = index;
        AppLog app = apps.get(index);
        selectedApp = app.name;
        updateSelectedApp(app);
        File file = app.preferred();
        if (file == null) {
            allLines.clear();
            applyFilter();
            return;
        }
        load(file, true);
    }

    private List<AppLog> listApps() {
        Map<String, AppLog> grouped = new LinkedHashMap<>();
        for (File file : TraceFiles.listLogs(requireContext())) {
            String name = TraceFiles.appName(file);
            AppLog app = grouped.get(name);
            if (app == null) {
                app = new AppLog(name);
                grouped.put(name, app);
            }
            app.add(file);
        }
        List<AppLog> result = new ArrayList<>(grouped.values());
        for (AppLog app : result) {
            app.loadMeta(requireContext());
        }
        return result;
    }

    private int indexOfApp(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < apps.size(); i++) {
            if (name.equals(apps.get(i).name)) {
                return i;
            }
        }
        return -1;
    }

    private boolean sameApps(List<AppLog> latest) {
        if (latest.size() != apps.size()) {
            return false;
        }
        for (int i = 0; i < latest.size(); i++) {
            if (!apps.get(i).sameAs(latest.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void updateSelectedApp(AppLog app) {
        imgSelectedAppIcon.setImageDrawable(app.icon);
        tvSelectedAppName.setText(app.label);
        tvSelectedAppPackage.setText(app.name);
        tvSelectedAppPackage.setVisibility(View.VISIBLE);
    }

    private void clearSelectedApp() {
        imgSelectedAppIcon.setImageResource(R.drawable.ic_launcher);
        tvSelectedAppName.setText(R.string.logs_no_file);
        tvSelectedAppPackage.setText("");
        tvSelectedAppPackage.setVisibility(View.GONE);
    }

    private void showAppPopup() {
        if (apps.isEmpty()) {
            Toast.makeText(requireContext(), R.string.logs_no_file, Toast.LENGTH_SHORT).show();
            return;
        }
        if (appPopup == null) {
            appPopup = new ListPopupWindow(requireContext());
            appPopup.setAnchorView(requireView().findViewById(R.id.topBar));
            appPopup.setModal(true);
            appPopup.setOnItemClickListener((parent, view, position, id) -> {
                selectApp(position);
                appPopup.dismiss();
            });
        }
        appPopup.setAdapter(new AppAdapter());

        int margin = Math.round(16 * getResources().getDisplayMetrics().density);
        appPopup.setWidth(getResources().getDisplayMetrics().widthPixels - margin * 2);
        appPopup.setHorizontalOffset(margin);
        appPopup.show();
    }

    private void setSearchOpen(boolean open) {
        if (searchOpen == open) {
            return;
        }
        searchOpen = open;
        searchLayout.setVisibility(open ? View.VISIBLE : View.GONE);
        btnSearch.setIconResource(open ? R.drawable.ic_close : R.drawable.ic_search);
        btnSearch.setContentDescription(getString(open ? R.string.close_search : R.string.search));

        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (open) {
            editFilter.requestFocus();
            editFilter.post(() -> {
                if (imm != null) {
                    imm.showSoftInput(editFilter, InputMethodManager.SHOW_IMPLICIT);
                }
            });
        } else {
            if (editFilter.getText() != null && editFilter.getText().length() > 0) {
                editFilter.setText("");
            }
            editFilter.clearFocus();
            if (imm != null) {
                imm.hideSoftInputFromWindow(editFilter.getWindowToken(), 0);
            }
        }
    }

    private void load(File file, boolean scrollToEnd) {
        final int generation = loadGeneration.incrementAndGet();
        tvStatus.setText(R.string.logs_reading);
        io.execute(() -> {
            final List<String> lines = TraceFiles.readTail(file, MAX_LINES);
            main.post(() -> {
                if (generation != loadGeneration.get() || !isAdded()) {
                    return;
                }
                allLines.clear();
                allLines.addAll(lines);
                applyFilter();
                if (scrollToEnd && !visibleLines.isEmpty()) {
                    recyclerLogs.scrollToPosition(visibleLines.size() - 1);
                }
            });
        });
    }

    private void applyFilter() {
        if (lineAdapter == null) {
            return;
        }
        String query = editFilter.getText() == null
                ? "" : editFilter.getText().toString().trim().toLowerCase(Locale.ROOT);
        int kind = selectedKind();

        visibleLines.clear();
        for (String line : allLines) {
            if (kind != KIND_ALL && !matchesKind(line, kind)) {
                continue;
            }
            if (!query.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            visibleLines.add(line);
        }
        lineAdapter.submit(visibleLines);

        boolean empty = visibleLines.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            tvEmpty.setText(allLines.isEmpty() ? R.string.logs_empty : R.string.logs_no_match);
        }
        if (allLines.isEmpty()) {
            tvStatus.setText(R.string.logs_empty);
        } else if (query.isEmpty() && kind == KIND_ALL) {
            tvStatus.setText(getString(R.string.log_line_count, allLines.size()));
        } else {
            tvStatus.setText(getString(R.string.log_filtered_count, visibleLines.size(), allLines.size()));
        }
        updateActions();
    }

    private int selectedKind() {
        int id = chipKind.getCheckedChipId();
        if (id == R.id.chipCall) {
            return KIND_CALL;
        }
        if (id == R.id.chipError) {
            return KIND_ERROR;
        }
        if (id == R.id.chipFramework) {
            return KIND_FRAMEWORK;
        }
        return KIND_ALL;
    }

    private static boolean matchesKind(String line, int kind) {
        switch (kind) {
            case KIND_CALL:
                return line.contains("→") || line.contains("←");
            case KIND_ERROR:
                return line.contains("!!");
            case KIND_FRAMEWORK:
                return line.startsWith("===") || line.contains("[FnCalls]");
            default:
                return true;
        }
    }

    private void updateActions() {
        boolean hasFile = currentFile() != null;
        btnExport.setEnabled(hasFile);
        btnShare.setEnabled(hasFile);
        btnClear.setEnabled(hasFile);
    }

    private void startExport() {
        File file = currentFile();
        if (file == null || !file.isFile()) {
            Toast.makeText(requireContext(), R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        pendingExport = file;
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        exportLauncher.launch(TraceFiles.displayName(file) + "-" + stamp + ".txt");
    }

    private void exportTo(Uri uri) {
        File source = pendingExport;
        pendingExport = null;
        if (uri == null || source == null) {
            return;
        }
        Context appContext = requireContext().getApplicationContext();
        io.execute(() -> {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = appContext.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) {
                    throw new IOException("无法打开目标文件");
                }
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
                main.post(() -> Toast.makeText(appContext, R.string.export_done, Toast.LENGTH_SHORT).show());
            } catch (Throwable t) {
                main.post(() -> Toast.makeText(appContext,
                        getString(R.string.export_failed, String.valueOf(t)), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void confirmClear() {
        File file = currentFile();
        if (file == null) {
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.clear_confirm_title)
                .setMessage(getString(R.string.clear_confirm_message, TraceFiles.displayName(file)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    TraceFiles.delete(file);
                    Toast.makeText(requireContext(), R.string.clear_done, Toast.LENGTH_SHORT).show();
                    reload(true);
                })
                .show();
    }

    private void shareCurrent() {
        File file = currentFile();
        if (file == null || !file.isFile()) {
            Toast.makeText(requireContext(), R.string.share_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    requireContext(), BuildConfig.APPLICATION_ID + ".files", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_title));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.share_title)));
        } catch (Throwable t) {
            Toast.makeText(requireContext(), getString(R.string.share_failed, String.valueOf(t)),
                    Toast.LENGTH_LONG).show();
        }
    }

    private File currentFile() {
        if (selectedIndex < 0 || selectedIndex >= apps.size()) {
            return null;
        }
        return apps.get(selectedIndex).preferred();
    }

    private static final class AppLog {
        final String name;
        File main;
        File backup;
        String label;
        Drawable icon;

        AppLog(String name) {
            this.name = name;
            this.label = name;
        }

        void add(File file) {
            if (TraceFiles.isBackup(file)) {
                backup = file;
            } else {
                main = file;
            }
        }

        void loadMeta(Context context) {
            label = name;
            Drawable fallback = AppCompatResources.getDrawable(context, R.drawable.ic_launcher);
            icon = fallback != null ? fallback : context.getDrawable(R.drawable.ic_launcher);
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(name, 0);
                CharSequence appLabel = pm.getApplicationLabel(info);
                if (appLabel != null && appLabel.length() > 0) {
                    label = appLabel.toString();
                }
                Drawable appIcon = pm.getApplicationIcon(info);
                if (appIcon != null) {
                    icon = appIcon;
                }
            } catch (Throwable ignored) {
            }
        }

        File preferred() {
            return main != null ? main : backup;
        }

        boolean sameAs(AppLog other) {
            if (other == null || !name.equals(other.name)) {
                return false;
            }
            File left = preferred();
            File right = other.preferred();
            return left == null ? right == null : left.equals(right);
        }
    }

    private final class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public AppLog getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_log_app, parent, false);
            }
            AppLog app = getItem(position);
            ImageView icon = view.findViewById(R.id.imgLogAppIcon);
            TextView name = view.findViewById(R.id.tvLogAppName);
            TextView packageName = view.findViewById(R.id.tvLogAppPackage);
            icon.setImageDrawable(app.icon);
            name.setText(app.label);
            packageName.setText(app.name);
            return view;
        }
    }

    private static final class LineAdapter extends RecyclerView.Adapter<LineAdapter.Holder> {

        private final int colorCall;
        private final int colorReturn;
        private final int colorError;
        private final int colorFramework;
        private final int colorDefault;
        private List<String> lines = new ArrayList<>();

        LineAdapter(Context context) {
            colorCall = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE);
            colorReturn = MaterialColors.getColor(context, com.google.android.material.R.attr.colorTertiary, Color.DKGRAY);
            colorError = MaterialColors.getColor(context, com.google.android.material.R.attr.colorError, Color.RED);
            colorFramework = MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
            colorDefault = MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        }

        void submit(List<String> newLines) {
            lines = new ArrayList<>(newLines);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_log_line, parent, false);
            return new Holder((TextView) view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String line = lines.get(position);
            holder.text.setText(line);
            holder.text.setTextColor(colorFor(line));
        }

        @Override
        public int getItemCount() {
            return lines.size();
        }

        private int colorFor(String line) {
            if (line.contains("!!")) {
                return colorError;
            }
            if (line.contains("→")) {
                return colorCall;
            }
            if (line.contains("←")) {
                return colorReturn;
            }
            if (line.startsWith("===") || line.contains("[FnCalls]")) {
                return colorFramework;
            }
            return colorDefault;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView text;

            Holder(TextView view) {
                super(view);
                text = view;
            }
        }
    }
}
