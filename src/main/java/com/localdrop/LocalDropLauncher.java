package com.localdrop;

import com.localdrop.util.BootstrapDiagnostics;

public final class LocalDropLauncher {
    private LocalDropLauncher() {
    }

    public static void main(String[] args) {
        BootstrapDiagnostics.installGlobalHandler();
        SingleInstanceService singleInstanceService = null;
        try {
            singleInstanceService = SingleInstanceService.acquireOrNotifyExisting();
            if (singleInstanceService == null) {
                return;
            }
            LocalDropApp.setSingleInstanceService(singleInstanceService);
            LocalDropApp.launchApp(args);
        } catch (Throwable throwable) {
            BootstrapDiagnostics.reportFailure("LocalDrop startup error", throwable);
        } finally {
            if (singleInstanceService != null) {
                singleInstanceService.close();
            }
        }
    }
}
