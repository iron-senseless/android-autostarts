package com.elsdoerfer.android.autostarts;

interface IComponentStateService {
    // Reserved Shizuku UserService teardown transaction.
    void destroy() = 16777114;

    int setComponentEnabledState(String packageName, String componentName, boolean enabled) = 1;
}
