package com.elsdoerfer.android.autostarts;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.elsdoerfer.android.autostarts.db.ComponentInfo;

import rikka.shizuku.Shizuku;

/**
 * Coordinates a root-backed Shizuku/Sui UserService.
 *
 * A Shizuku server started by ADB has the shell UID. Android deliberately
 * prevents that UID from changing arbitrary individual component states, so
 * this backend is intentionally available only when the remote UID is root.
 */
final class ShizukuBackend {

    static final int PERMISSION_REQUEST_CODE = 0x5348;
    private static final long CONNECT_TIMEOUT_MILLIS = 10_000L;

    enum Status {
        READY,
        NOT_RUNNING,
        OUTDATED,
        ADB,
        NOT_ROOT,
        PERMISSION_DENIED
    }

    private static volatile ShizukuBackend instance;

    static ShizukuBackend get(Context context) {
        if (instance == null) {
            synchronized (ShizukuBackend.class) {
                if (instance == null) {
                    instance = new ShizukuBackend();
                }
            }
        }
        return instance;
    }

    private final Object serviceLock = new Object();
    private IComponentStateService service;
    private boolean binding;

    private final Shizuku.UserServiceArgs userServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName(
                    BuildConfig.APPLICATION_ID, ShizukuComponentStateService.class.getName()))
                    .processNameSuffix("component-state")
                    .tag("component-state")
                    .version(BuildConfig.VERSION_CODE)
                    .daemon(false)
                    .debuggable(BuildConfig.DEBUG);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            synchronized (serviceLock) {
                service = binder != null && binder.pingBinder()
                        ? IComponentStateService.Stub.asInterface(binder)
                        : null;
                binding = false;
                serviceLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            clearService();
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = new Shizuku.OnBinderDeadListener() {
        @Override
        public void onBinderDead() {
            clearService();
        }
    };

    private ShizukuBackend() {
        // The backend is process-scoped. Keep the listener for the process lifetime so a
        // restarted Shizuku server cannot leave us holding a stale UserService binder.
        Shizuku.addBinderDeadListener(binderDeadListener);
    }

    Status getStatus() {
        if (!Shizuku.pingBinder()) {
            return Status.NOT_RUNNING;
        }

        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                return Status.OUTDATED;
            }

            int uid = Shizuku.getUid();
            if (uid == Process.SHELL_UID) {
                return Status.ADB;
            }
            if (uid != Process.ROOT_UID) {
                return Status.NOT_ROOT;
            }

            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    ? Status.READY
                    : Status.PERMISSION_DENIED;
        } catch (RuntimeException e) {
            Log.w(Utils.TAG, "Unable to inspect Shizuku state", e);
            return Status.NOT_RUNNING;
        }
    }

    boolean requestPermission() {
        if (getStatus() != Status.PERMISSION_DENIED) {
            return false;
        }

        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                return false;
            }
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
            return true;
        } catch (RuntimeException e) {
            Log.w(Utils.TAG, "Unable to request Shizuku permission", e);
            return false;
        }
    }

    boolean toggleState(ComponentInfo component, boolean enabled) {
        if (getStatus() != Status.READY) {
            return false;
        }

        IComponentStateService remoteService = getService();
        if (remoteService == null) {
            return false;
        }

        try {
            component.currentEnabledState = remoteService.setComponentEnabledState(
                    component.packageInfo.packageName, component.componentName, enabled);
            return component.isCurrentlyEnabled() == enabled;
        } catch (RemoteException | RuntimeException e) {
            Log.e(Utils.TAG, "Shizuku failed to change component state", e);
            clearService();
            return false;
        }
    }

    private IComponentStateService getService() {
        synchronized (serviceLock) {
            if (isServiceAlive()) {
                return service;
            }
            if (!binding) {
                binding = true;
            } else {
                return awaitServiceLocked();
            }
        }

        try {
            Shizuku.bindUserService(userServiceArgs, serviceConnection);
        } catch (RuntimeException e) {
            Log.e(Utils.TAG, "Unable to bind Shizuku UserService", e);
            synchronized (serviceLock) {
                binding = false;
                serviceLock.notifyAll();
            }
            return null;
        }

        synchronized (serviceLock) {
            return awaitServiceLocked();
        }
    }

    private IComponentStateService awaitServiceLocked() {
        long deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MILLIS;
        while (binding && !isServiceAlive()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                binding = false;
                break;
            }
            try {
                serviceLock.wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return isServiceAlive() ? service : null;
    }

    private boolean isServiceAlive() {
        return service != null && service.asBinder().isBinderAlive();
    }

    private void clearService() {
        synchronized (serviceLock) {
            service = null;
            binding = false;
            serviceLock.notifyAll();
        }
    }
}
