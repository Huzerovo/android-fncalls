package moe.huzerovo.fncalls.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import moe.huzerovo.fncalls.R;

/**
 * Single-screen host with two tabs: the start/stop control page and the log
 * viewer. Each tab is a fragment so switching does not recreate the screen.
 */
public class MainActivity extends AppCompatActivity {

    private static final String STATE_TAB = "selected_tab";
    private static final String TAG_CONTROL = "control";
    private static final String TAG_LOGS = "logs";

    private MaterialToolbar toolbar;
    private BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        UiInsets.apply(this);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        bottomNav = findViewById(R.id.bottomNav);
        bottomNav.setOnItemSelectedListener(item -> {
            showTab(item.getItemId());
            return true;
        });

        int initial = savedInstanceState == null
                ? R.id.nav_control
                : savedInstanceState.getInt(STATE_TAB, R.id.nav_control);
        showTab(initial);
        bottomNav.setSelectedItemId(initial);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, bottomNav.getSelectedItemId());
    }

    public void selectTab(int itemId) {
        bottomNav.setSelectedItemId(itemId);
        showTab(itemId);
    }

    private void showTab(int itemId) {
        boolean logs = itemId == R.id.nav_logs;
        String tag = logs ? TAG_LOGS : TAG_CONTROL;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction().setReorderingAllowed(true);

        Fragment other = fm.findFragmentByTag(logs ? TAG_CONTROL : TAG_LOGS);
        if (other != null && !other.isHidden()) {
            tx.hide(other);
        }
        Fragment target = fm.findFragmentByTag(tag);
        if (target == null) {
            tx.add(R.id.fragmentContainer, logs ? new LogFragment() : new ControlFragment(), tag);
        } else if (target.isHidden()) {
            tx.show(target);
        }
        tx.commitNow();

        toolbar.setTitle(logs ? R.string.title_logs : R.string.title_settings);
        toolbar.setSubtitle(R.string.app_subtitle);
    }
}
