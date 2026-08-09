package com.app.idemobile;

import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

/**
 * Перехват запросов из service worker (Android 7+).
 * Без него превью внутри клиента не смогло бы получать файлы из assets.
 */
final class SwCompat {

    private SwCompat() {
    }

    static void install(final AssetWebServer server) {
        ServiceWorkerController c = ServiceWorkerController.getInstance();
        if (c == null) return;
        try {
            c.getServiceWorkerWebSettings().setAllowContentAccess(true);
            c.getServiceWorkerWebSettings().setAllowFileAccess(true);
        } catch (Throwable ignored) {
        }
        c.setServiceWorkerClient(new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                WebResourceResponse r = server.handle(request);
                return r != null ? r : super.shouldInterceptRequest(request);
            }
        });
    }
}
