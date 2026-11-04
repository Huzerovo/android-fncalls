package moe.huzerovo.fncalls;

import android.app.Application;

import androidx.annotation.NonNull;

import com.google.android.material.color.DynamicColors;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Module UI process. The framework binds the libxposed service through the
 * provider merged from the service AAR; the UI uses that service to read/write
 * remote preferences shared with hooked processes.
 */
public final class FnCallsApp extends Application {

    public interface ServiceListener {
        void onService(@NonNull XposedService service);

        default void onServiceLost() {
        }
    }

    private static volatile XposedService service;
    private static final List<ServiceListener> listeners = new CopyOnWriteArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(@NonNull XposedService bound) {
                service = bound;
                notifyService(bound);
            }

            @Override
            public void onServiceDied(@NonNull XposedService died) {
                // A newer binder may already have replaced the dying one. In that
                // case the old death notification must not mark the UI as lost.
                if (service != died) {
                    return;
                }
                service = null;
                notifyServiceLost();
            }
        });
    }

    public static XposedService getService() {
        return service;
    }

    private static void notifyService(@NonNull XposedService bound) {
        for (ServiceListener listener : listeners) {
            try {
                listener.onService(bound);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void notifyServiceLost() {
        for (ServiceListener listener : listeners) {
            try {
                listener.onServiceLost();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void addServiceListener(@NonNull ServiceListener listener) {
        listeners.add(listener);
        XposedService current = service;
        if (current != null) {
            listener.onService(current);
        }
    }

    public static void removeServiceListener(@NonNull ServiceListener listener) {
        listeners.remove(listener);
    }
}
