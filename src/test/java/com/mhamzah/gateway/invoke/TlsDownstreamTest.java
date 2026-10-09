package com.mhamzah.gateway.invoke;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mhamzah.gateway.config.GatewayProperties.Tls;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/** HTTPS targets through the real client: VERIFY, INSECURE, CUSTOM trust store, mutual TLS. */
class TlsDownstreamTest {

    private static final String PW = "changeit";
    private static Path dir;
    private static WireMockServer server;
    private static WireMockServer mtlsServer;

    @BeforeAll
    static void start() throws Exception {
        dir = Files.createTempDirectory("tls-test");
        keytool("-genkeypair", "-alias", "server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-storetype", "PKCS12", "-keystore", file("server.p12"), "-storepass", PW, "-keypass", PW);
        keytool("-exportcert", "-rfc", "-alias", "server", "-keystore", file("server.p12"), "-storepass", PW,
                "-file", file("server-ca.pem"));
        keytool("-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                "-dname", "CN=gateway-client", "-storetype", "PKCS12", "-keystore", file("client.p12"),
                "-storepass", PW, "-keypass", PW);
        keytool("-exportcert", "-rfc", "-alias", "client", "-keystore", file("client.p12"), "-storepass", PW,
                "-file", file("client.pem"));
        keytool("-importcert", "-noprompt", "-alias", "client", "-file", file("client.pem"),
                "-storetype", "PKCS12", "-keystore", file("server-trust.p12"), "-storepass", PW);
        writePemKeyStore(file("client.p12"), file("client-key.pem"));

        server = new WireMockServer(wireMockConfig().dynamicPort().dynamicHttpsPort()
                .keystorePath(file("server.p12")).keystorePassword(PW).keyManagerPassword(PW).keystoreType("PKCS12"));
        server.start();
        server.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        mtlsServer = new WireMockServer(wireMockConfig().dynamicPort().dynamicHttpsPort()
                .keystorePath(file("server.p12")).keystorePassword(PW).keyManagerPassword(PW).keystoreType("PKCS12")
                .needClientAuth(true).trustStorePath(file("server-trust.p12")).trustStorePassword(PW)
                .trustStoreType("PKCS12"));
        mtlsServer.start();
        mtlsServer.stubFor(get(urlEqualTo("/ping")).willReturn(aResponse().withStatus(200).withBody("{\"mtls\":true}")));
    }

    @AfterAll
    static void stop() {
        server.stop();
        mtlsServer.stop();
    }

    private static String file(String name) {
        return dir.resolve(name).toString();
    }

    private static void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }

    /** Certificate + PKCS#8 private key in one PEM file, as operators usually have them. */
    private static void writePemKeyStore(String p12, String pem) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(p12)) {
            ks.load(in, PW.toCharArray());
        }
        Certificate cert = ks.getCertificate("client");
        Key key = ks.getKey("client", PW.toCharArray());
        Base64.Encoder b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        Files.writeString(Path.of(pem), "-----BEGIN CERTIFICATE-----\n" + b64.encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n-----BEGIN PRIVATE KEY-----\n" + b64.encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
    }

    private static DownstreamResponse call(WireMockServer s, Tls tls) {
        return new HttpDownstreamClient().call(new DownstreamRequest("CORE", "https://localhost:" + s.httpsPort(),
                HttpMethod.GET, "/ping", Map.of(), null, Duration.ofSeconds(3), Duration.ofSeconds(5),
                TlsContexts.rebuild(tls)));
    }

    @Test
    void verifyRejectsAnUnknownCertificate() {
        assertThatThrownBy(() -> call(server, Tls.VERIFY))
                .isInstanceOf(DownstreamException.class)
                .hasMessageContaining("TLS handshake")
                .hasMessageContaining("tls settings");
    }

    @Test
    void insecureSkipsVerification() {
        assertThat(call(server, new Tls("INSECURE", null, null, null, null)).status()).isEqualTo(200);
    }

    @Test
    void customTrustStoreAsPemFilePemTextOrPkcs12() throws Exception {
        assertThat(call(server, new Tls("CUSTOM", file("server-ca.pem"), null, null, null)).status()).isEqualTo(200);
        String pemText = Files.readString(Path.of(file("server-ca.pem")));
        assertThat(call(server, new Tls("CUSTOM", pemText, null, null, null)).status()).isEqualTo(200);
        assertThat(call(server, new Tls("CUSTOM", file("server.p12"), PW, null, null)).status()).isEqualTo(200);
    }

    @Test
    void mutualTlsWithAClientKeyStore() {
        String trust = file("server-ca.pem");
        assertThat(call(mtlsServer, new Tls("CUSTOM", trust, null, file("client.p12"), PW)).body()).contains("mtls");
        assertThat(call(mtlsServer, new Tls("CUSTOM", trust, null, file("client-key.pem"), null)).body()).contains("mtls");
        assertThatThrownBy(() -> call(mtlsServer, new Tls("CUSTOM", trust, null, null, null)))
                .isInstanceOf(DownstreamException.class);
    }

    @Test
    void badSettingsAreReadableErrorsWithoutKeyMaterial() {
        assertThatThrownBy(() -> TlsContexts.rebuild(new Tls("CUSTOM", null, null, null, null)))
                .hasMessageContaining("needs a trust store, a key store or both");
        assertThatThrownBy(() -> TlsContexts.rebuild(new Tls("CUSTOM", file("missing.pem"), null, null, null)))
                .hasMessageContaining("cannot load the trust store").hasMessageContaining("missing.pem");
        assertThatThrownBy(() -> TlsContexts.rebuild(new Tls("CUSTOM", null, null, file("client.p12"), "wrong")))
                .hasMessageContaining("cannot load the key store");
        assertThatThrownBy(() -> TlsContexts.rebuild(new Tls("CUSTOM", "-----BEGIN CERTIFICATE-----\nnope\n-----END CERTIFICATE-----", null, null, null)))
                .hasMessageContaining("PEM text").hasMessageNotContaining("nope");
        assertThatThrownBy(() -> TlsContexts.rebuild(new Tls("STRICT", null, null, null, null)))
                .hasMessageContaining("VERIFY, INSECURE or CUSTOM");
    }
}
