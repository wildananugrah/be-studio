package com.mhamzah.gateway.studio.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The substring checks give the same answers as the regexes they replaced. */
class TestCaseGeneratorMatchingTest {

    private static final String[] NAMES = {"", "amount", "totalamount", "toaccount", "beneficiaryaccountno",
            "destacc", "accountto", "fromaccount", "cif", "cifno", "pagesize", "page", "rowcount", "limit", "currency",
            "ccy", "mobilephone", "msisdn", "remark", "description", "memo", "note", "channel", "username", "balance",
            "fee", "price", "tox", "to_acc", "acc_to", "dest", "stoacc", "a"};

    @Test
    void containsAnyMatchesTheOldRegexes() {
        for (String n : NAMES) {
            assertThat(TestCaseGenerator.containsAny(n, "amount", "amt", "bal", "price", "fee", "total")).as(n)
                    .isEqualTo(n.matches(".*(amount|amt|bal|price|fee|total).*"));
            assertThat(TestCaseGenerator.containsAny(n, "limit", "size", "count")).as(n)
                    .isEqualTo(n.matches(".*(limit|size|count).*"));
            assertThat(TestCaseGenerator.containsAny(n, "acc", "cif")).as(n)
                    .isEqualTo(n.matches(".*acc.*|.*account.*|.*cif.*"));
            assertThat(TestCaseGenerator.containsAny(n, "currency", "ccy")).as(n)
                    .isEqualTo(n.matches(".*(currency|ccy).*"));
            assertThat(TestCaseGenerator.containsAny(n, "phone", "mobile", "msisdn")).as(n)
                    .isEqualTo(n.matches(".*(phone|mobile|msisdn).*"));
            assertThat(TestCaseGenerator.containsAny(n, "note", "remark", "desc", "memo")).as(n)
                    .isEqualTo(n.matches(".*(note|remark|desc|memo).*"));
        }
    }

    @Test
    void accountAfterMatchesTheOldRegex() {
        for (String n : NAMES) {
            assertThat(TestCaseGenerator.accountAfter(n, "to", "beneficiary", "dest")).as(n)
                    .isEqualTo(n.matches(".*(to|beneficiary|dest).*acc.*"));
        }
    }
}
