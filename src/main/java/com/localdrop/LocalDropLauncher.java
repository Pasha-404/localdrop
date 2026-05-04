package com.localdrop;

import com.localdrop.util.BootstrapDiagnostics;

public final class LocalDropLauncher {
    private LocalDropLauncher() {
    }

    public static void main(String[] args) {
        BootstrapDiagnostics.installGlobalHandler();
        try {
            LocalDropApp.launchApp(args);
        } catch (Throwable throwable) {
            BootstrapDiagnostics.reportFailure("LocalDrop startup error", throwable);
        }
    }
}
