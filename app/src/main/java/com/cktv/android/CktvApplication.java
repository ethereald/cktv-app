package com.cktv.android;

import android.app.Application;
import android.util.Log;

import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Many video and poster hosts now use Let's Encrypt's newer ISRG Root YE/YR hierarchy, which
 * older Android TV system trust stores do not contain. Without it every HTTPS request to those
 * hosts fails with a connection error, so the roots are bundled and trusted alongside the system
 * roots for all HttpsURLConnection traffic (including ExoPlayer's HTTP data source).
 */
public final class CktvApplication extends Application {
    private static final String TAG = "CKTV";
    private static final int[] EXTRA_ROOTS = {
            R.raw.isrg_root_ye, R.raw.isrg_root_yr, R.raw.isrg_root_x2
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            installExtendedTrust();
        } catch (Exception error) {
            Log.w(TAG, "Could not install the bundled certificate roots.", error);
        }
    }

    private void installExtendedTrust() throws Exception {
        KeyStore extraStore = KeyStore.getInstance(KeyStore.getDefaultType());
        extraStore.load(null, null);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < EXTRA_ROOTS.length; i++) {
            try (InputStream stream = getResources().openRawResource(EXTRA_ROOTS[i])) {
                Certificate certificate = factory.generateCertificate(stream);
                extraStore.setCertificateEntry("cktv-extra-" + i, certificate);
            }
        }
        X509TrustManager system = trustManager(null);
        X509TrustManager extra = trustManager(extraStore);
        X509TrustManager combined = new CombinedTrustManager(system, extra);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{combined}, null);
        HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
    }

    private static X509TrustManager trustManager(KeyStore store) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new IllegalStateException("No X509TrustManager available");
    }

    private static final class CombinedTrustManager implements X509TrustManager {
        private final X509TrustManager system;
        private final X509TrustManager extra;

        CombinedTrustManager(X509TrustManager system, X509TrustManager extra) {
            this.system = system;
            this.extra = extra;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkClientTrusted(chain, authType);
            } catch (CertificateException error) {
                extra.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemError) {
                try {
                    extra.checkServerTrusted(chain, authType);
                } catch (CertificateException extraError) {
                    throw systemError;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<>();
            for (X509Certificate certificate : system.getAcceptedIssuers()) issuers.add(certificate);
            for (X509Certificate certificate : extra.getAcceptedIssuers()) issuers.add(certificate);
            return issuers.toArray(new X509Certificate[0]);
        }
    }
}
