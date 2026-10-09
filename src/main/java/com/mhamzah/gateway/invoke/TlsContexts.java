package com.mhamzah.gateway.invoke;

import com.mhamzah.gateway.config.GatewayProperties.Tls;
import java.net.Socket;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreDetails;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;

/**
 * Builds the {@link SSLContext} of a target system's {@link Tls} settings (placeholders already resolved) and keeps
 * one per distinct setting, so a reload compiles it once and every call reuses it. {@code VERIFY} needs none: the
 * HTTP client's default context is used.
 */
public final class TlsContexts {

    private static final Map<Tls, SSLContext> CACHE = new ConcurrentHashMap<>();

    private TlsContexts() {}

    /**
     * Builds the context for {@code tls} again (re-reading its files, so rotated certificates are picked up) and
     * makes it the one {@link #of} returns. Called for every target system on each configuration load.
     *
     * @throws IllegalArgumentException with a readable reason when a store cannot be loaded or the mode is unknown
     */
    public static SSLContext rebuild(Tls tls) {
        if (tls == null || "VERIFY".equals(tls.mode())) {
            return null;
        }
        SSLContext built = build(tls);
        CACHE.put(tls, built);
        return built;
    }

    /**
     * The context for {@code tls}, or null for {@code VERIFY}.
     *
     * @throws IllegalArgumentException with a readable reason when a store cannot be loaded or the mode is unknown
     */
    public static SSLContext of(Tls tls) {
        if (tls == null || "VERIFY".equals(tls.mode())) {
            return null;
        }
        SSLContext cached = CACHE.get(tls);
        if (cached != null) {
            return cached;
        }
        SSLContext built = build(tls);
        CACHE.put(tls, built);
        return built;
    }

    private static SSLContext build(Tls tls) {
        switch (tls.mode()) {
            case "INSECURE":
                try {
                    SSLContext context = SSLContext.getInstance("TLS");
                    context.init(null, new TrustManager[] {new TrustAll()}, new SecureRandom());
                    return context;
                } catch (java.security.GeneralSecurityException e) {
                    throw new IllegalArgumentException("cannot create a TLS context: " + e.getMessage(), e);
                }
            case "CUSTOM":
                if (blank(tls.trustStore()) && blank(tls.keyStore())) {
                    throw new IllegalArgumentException("tls_mode CUSTOM needs a trust store, a key store or both");
                }
                KeyStore trust = blank(tls.trustStore()) ? null : load(tls.trustStore(), tls.trustStorePassword(), false);
                KeyStore keys = blank(tls.keyStore()) ? null : load(tls.keyStore(), tls.keyStorePassword(), true);
                String keyPassword = blank(tls.keyStorePassword()) ? null : tls.keyStorePassword();
                // no trust store: the JVM's default CAs stay in charge
                SslBundle bundle = SslBundle.of(SslStoreBundle.of(keys, keyPassword, trust),
                        keyPassword == null ? SslBundleKey.NONE : SslBundleKey.of(keyPassword));
                try {
                    return bundle.createSslContext();
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("cannot create a TLS context: " + rootMessage(e), e);
                }
            default:
                throw new IllegalArgumentException("tls_mode '" + tls.mode() + "' must be VERIFY, INSECURE or CUSTOM");
        }
    }

    /** PEM text or file, or a PKCS12 / JKS file by extension. */
    private static KeyStore load(String store, String password, boolean withKey) {
        String what = withKey ? "key store" : "trust store";
        String value = store.strip();
        String pw = blank(password) ? null : password;
        try {
            String lower = value.toLowerCase(Locale.ROOT);
            if (!value.contains("-----BEGIN") && (lower.endsWith(".p12") || lower.endsWith(".pfx") || lower.endsWith(".jks"))) {
                String type = lower.endsWith(".jks") ? "JKS" : "PKCS12";
                JksSslStoreDetails details = new JksSslStoreDetails(type, null, location(value), pw);
                JksSslStoreBundle bundle = withKey ? new JksSslStoreBundle(details, null) : new JksSslStoreBundle(null, details);
                return withKey ? bundle.getKeyStore() : bundle.getTrustStore();
            }
            String pem = value.contains("-----BEGIN") ? value : location(value);
            PemSslStoreDetails details = withKey
                    ? new PemSslStoreDetails(null, pem, pem, pw)       // certificate chain + private key
                    : PemSslStoreDetails.forCertificates(pem);
            PemSslStoreBundle bundle = withKey ? new PemSslStoreBundle(details, null) : new PemSslStoreBundle(null, details);
            KeyStore keyStore = withKey ? bundle.getKeyStore() : bundle.getTrustStore();
            if (keyStore == null) {
                throw new IllegalArgumentException("no certificate found");
            }
            return keyStore;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("cannot load the " + what + " (" + describe(value) + "): " + rootMessage(e), e);
        }
    }

    /** A plain path becomes a {@code file:} location; {@code classpath:}, {@code file:} and URLs are kept. */
    private static String location(String value) {
        return value.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*") && !value.matches("^[a-zA-Z]:[\\\\/].*") ? value : "file:" + value;
    }

    /** Never echo key material into error messages. */
    private static String describe(String value) {
        return value.contains("-----BEGIN") ? "PEM text" : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Accepts every certificate and host name: an X509ExtendedTrustManager, so the JDK skips its own checks. */
    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
