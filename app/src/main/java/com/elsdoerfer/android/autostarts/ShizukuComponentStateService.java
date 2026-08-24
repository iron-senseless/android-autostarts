package com.elsdoerfer.android.autostarts;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Runs in Shizuku's root UserService process and uses the public PackageManager API.
 */
public class ShizukuComponentStateService extends IComponentStateService.Stub {

    private final PackageManager packageManager;

    /**
     * Shizuku v13 supplies the caller package's application context here.
     */
    public ShizukuComponentStateService(Context context) {
        packageManager = context.getPackageManager();
    }

    @Override
    public int setComponentEnabledState(String packageName, String componentName, boolean enabled) {
        ComponentName component = new ComponentName(packageName, componentName);
        packageManager.setComponentEnabledSetting(
                component,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                0);
        return packageManager.getComponentEnabledSetting(component);
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
