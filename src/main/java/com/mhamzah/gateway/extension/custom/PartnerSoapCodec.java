package com.mhamzah.gateway.extension.custom;

import com.mhamzah.gateway.codec.SoapCodec;
import com.mhamzah.gateway.extension.BodyCodec;
import com.mhamzah.gateway.extension.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Example {@link BodyCodec}: SOAP 1.1 like the built-in {@code soapCodec}, plus the WS-Security UsernameToken header
 * many partner SOAP services require.
 * <pre>
 *   &lt;soapenv:Header>
 *     &lt;wsse:Security xmlns:wsse="...">
 *       &lt;wsse:UsernameToken>&lt;wsse:Username>..&lt;/wsse:Username>&lt;wsse:Password Type="...#PasswordText">..&lt;/wsse:Password>
 *       &lt;/wsse:UsernameToken>
 *     &lt;/wsse:Security>
 *   &lt;/soapenv:Header>
 * </pre>
 * Use it on a target system: {@code gw_target_system.body_codec = 'partnerSoapCodec'}.
 * Credentials: {@code custom.partner-soap.username} / {@code .password} (set them from environment variables).
 * The SOAP header is not part of the audited JSON body, so the password never reaches the audit tables.
 */
@Component("partnerSoapCodec")
public class PartnerSoapCodec extends SoapCodec {

    static final String WSSE = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd";
    static final String PASSWORD_TEXT =
            "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordText";

    private final String username;
    private final String password;

    public PartnerSoapCodec(@Value("${custom.partner-soap.username:dev-user}") String username,
            @Value("${custom.partner-soap.password:dev-password}") String password) {
        super(Version.SOAP_1_1);
        this.username = username;
        this.password = password;
    }

    @Override
    protected JsonNode header(ExecutionContext ctx) {
        ObjectNode header = object();
        ObjectNode security = header.putObject("wsse:Security");
        security.put("@xmlns:wsse", WSSE);
        ObjectNode token = security.putObject("wsse:UsernameToken");
        token.put("wsse:Username", username);
        ObjectNode pwd = token.putObject("wsse:Password");
        pwd.put("@Type", PASSWORD_TEXT);
        pwd.put("#text", password);
        return header;
    }
}
