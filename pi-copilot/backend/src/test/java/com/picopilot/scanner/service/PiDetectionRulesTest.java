package com.picopilot.scanner.service;

import com.picopilot.common.model.Confidence;
import com.picopilot.common.model.PiCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PiDetectionRulesTest {

    @ParameterizedTest
    @CsvSource({
        "customers, email, CONTACT",
        "customers, CustomerEmail, CONTACT",
        "users, mobile_no, CONTACT",
        "users, firstName, DIRECT_IDENTIFIER",
        "employee, customer_name, DIRECT_IDENTIFIER",
        "employee, aadhaar_number, DIRECT_IDENTIFIER",
        "employee, ssn, DIRECT_IDENTIFIER",
        "employee, DOB, DEMOGRAPHIC",
        "orders, shipping_address, LOCATION",
        "orders, pin_code, LOCATION",
        "payments, card_number, FINANCIAL",
        "accounts, password_hash, CREDENTIAL",
        "patients, blood_group, HEALTH",
        "sessions, ip_address, DEVICE",
    })
    void detectsPiFromColumnName(String table, String column, PiCategory expected) {
        assertThat(PiDetectionRules.matchColumn(table, column))
            .hasValueSatisfying(m -> assertThat(m.category()).isEqualTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "created_at", "status", "order_total", "zipper_size", "page", "shipment_id"})
    void ignoresNonPiColumns(String column) {
        assertThat(PiDetectionRules.matchColumn("orders", column)).isEmpty();
    }

    @Test
    void bareNameCountsOnlyInPersonTables() {
        assertThat(PiDetectionRules.matchColumn("customer", "name"))
            .hasValueSatisfying(m -> assertThat(m.confidence()).isEqualTo(Confidence.MEDIUM));
        assertThat(PiDetectionRules.matchColumn("product", "name")).isEmpty();
    }

    @Test
    void detectsEmailsFromSampledValues() {
        List<String> samples = List.of("a@example.com", "b.c@test.org", "x@y.io", "not-an-email");
        assertThat(PiDetectionRules.matchValues(samples))
            .hasValueSatisfying(m -> {
                assertThat(m.category()).isEqualTo(PiCategory.CONTACT);
                assertThat(m.reasoning()).doesNotContain("example.com");
            });
    }

    @Test
    void detectsCardNumbersOnlyWhenLuhnValid() {
        assertThat(PiDetectionRules.matchValues(List.of("4111 1111 1111 1111", "5500-0000-0000-0004", "4012888888881881")))
            .hasValueSatisfying(m -> assertThat(m.category()).isEqualTo(PiCategory.FINANCIAL));
        assertThat(PiDetectionRules.matchValues(List.of("4111111111111112", "1234567890123456", "1111222233334445"))
            .map(PiDetectionRules.Match::category))
            .isNotEqualTo(java.util.Optional.of(PiCategory.FINANCIAL));
    }

    @Test
    void recognisesEncryptedTwinColumns() {
        java.util.Set<String> cols = java.util.Set.of("id", "email", "email_enc", "phone_enc", "first_name");
        assertThat(PiDetectionRules.encryptedTwinBase("email_enc", cols)).isEqualTo("email");
        assertThat(PiDetectionRules.encryptedTwinBase("EMAIL_ENC", java.util.Set.of("email", "email_enc"))).isEqualTo("EMAIL");
        // no plain column next to it, so not a twin
        assertThat(PiDetectionRules.encryptedTwinBase("phone_enc", cols)).isNull();
        assertThat(PiDetectionRules.encryptedTwinBase("email", cols)).isNull();
        assertThat(PiDetectionRules.encryptedTwinBase("_enc", java.util.Set.of(""))).isNull();
    }

    @Test
    void recognisesCiphertextValues() {
        assertThat(PiDetectionRules.looksEncrypted(List.of("enc:v1:abc", "enc:v1:def", "enc:v1:ghi", "plain"))).isTrue();
        assertThat(PiDetectionRules.looksEncrypted(List.of("a@x.io", "enc:v1:def"))).isFalse();
        assertThat(PiDetectionRules.looksEncrypted(List.of())).isFalse();
    }

    @Test
    void recognisesReleasedEncryptedColumnsWithoutTheirPlainColumn() {
        assertThat(PiDetectionRules.encryptedSuffixBase("email_enc")).isEqualTo("email");
        assertThat(PiDetectionRules.encryptedSuffixBase("FIRST_NAME_ENC")).isEqualTo("FIRST_NAME");
        assertThat(PiDetectionRules.encryptedSuffixBase("email")).isNull();
        assertThat(PiDetectionRules.encryptedSuffixBase("_enc")).isNull();
        // ciphertext, or nothing to check yet: already encrypted
        assertThat(PiDetectionRules.isReleasedEncryptedColumn(List.of("enc:v1:abc", "enc:v1:def"))).isTrue();
        assertThat(PiDetectionRules.isReleasedEncryptedColumn(List.of())).isTrue();
        assertThat(PiDetectionRules.isReleasedEncryptedColumn(java.util.Arrays.asList(null, " "))).isTrue();
        // a "_enc" column holding plain text is still plain PI
        assertThat(PiDetectionRules.isReleasedEncryptedColumn(List.of("a@x.io", "b@x.io"))).isFalse();
    }

    @Test
    void ignoresFreeTextValues() {
        assertThat(PiDetectionRules.matchValues(List.of("hello world", "pending", "shipped", "n/a"))).isEmpty();
    }
}
