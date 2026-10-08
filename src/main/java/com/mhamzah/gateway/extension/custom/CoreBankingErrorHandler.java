package com.mhamzah.gateway.extension.custom;

import com.mhamzah.gateway.extension.LookupErrorHandler;
import org.springframework.stereotype.Component;

/**
 * Example error mapping: core banking returns {@code responseCode} in its body; the code is translated
 * through lookup table {@code CORE_BANKING_ERRORS}. Reference it as {@code coreBankingErrorHandler}.
 */
@Component("coreBankingErrorHandler")
public class CoreBankingErrorHandler extends LookupErrorHandler {

    @Override
    protected String errorCodePath() {
        return "$.body.responseCode";
    }

    @Override
    protected String lookupCode() {
        return "CORE_BANKING_ERRORS";
    }
}
