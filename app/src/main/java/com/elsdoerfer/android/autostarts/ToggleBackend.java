package com.elsdoerfer.android.autostarts;

/**
 * The privileged transport selected for a component-state change.
 */
enum ToggleBackend {
    AUTO("auto"),
    SHIZUKU("shizuku"),
    ROOT("root");

    static final String EXTRA = "backend";

    private final String preferenceValue;

    ToggleBackend(String preferenceValue) {
        this.preferenceValue = preferenceValue;
    }

    String preferenceValue() {
        return preferenceValue;
    }

    static ToggleBackend fromPreference(String value) {
        for (ToggleBackend backend : values()) {
            if (backend.preferenceValue.equals(value)) {
                return backend;
            }
        }
        return AUTO;
    }
}
