package com.picopilot.scanner.service;

import com.picopilot.common.model.Confidence;
import com.picopilot.common.model.PiCategory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic PI detection from column metadata and, optionally, sampled values.
 * Sampled values are only matched in memory; they are never stored or logged.
 */
public final class PiDetectionRules {

    public record Match(PiCategory category, Confidence confidence, String rule, String reasoning) {}

    private record NameRule(String rule, Pattern pattern, PiCategory category, Confidence confidence, String what) {}

    private record ValueRule(String rule, Pattern pattern, PiCategory category, String what) {}

    /** Tables whose rows usually describe a person; a bare "name" column only counts in these. */
    private static final Pattern PERSON_TABLE = Pattern.compile(
        "(user|customer|client|employee|person|people|patient|member|contact|student|account|applicant|"
            + "candidate|staff|profile|guest|subscriber|tenant|owner|driver|beneficiar|nominee|emp)");

    // Column names are normalized to lowercase with "_" between words before matching.
    private static final List<NameRule> NAME_RULES = List.of(
        rule("EMAIL", "(^|_)e_?mail(_?addr(ess)?)?($|_)", PiCategory.CONTACT, Confidence.HIGH, "an email address"),
        rule("PHONE", "(^|_)(phone|mobile|msisdn|cell|telephone|tel|fax|whatsapp)(_?(no|num|number))?($|_)",
            PiCategory.CONTACT, Confidence.HIGH, "a phone number"),
        rule("NATIONAL_ID", "(^|_)(ssn|social_security|national_id|nid|aadhaa?r|pan_?(no|number|card)?|passport|"
            + "tax_id|tin|nin|driving_licen[cs]e|driver_licen[cs]e|dl_?no|voter_id|uid_?no)($|_)",
            PiCategory.DIRECT_IDENTIFIER, Confidence.HIGH, "a government-issued identifier"),
        rule("PERSON_NAME", "(^|_)(first|last|middle|full|given|family|sur|maiden|father|mother|spouse|nick|"
                + "customer|client|employee|emp|patient|member|contact|person|student|applicant|guardian|nominee)_?name($|_)",
            PiCategory.DIRECT_IDENTIFIER, Confidence.HIGH, "a person's name"),
        rule("BIRTH_DATE", "(^|_)(dob|date_of_birth|birth_?date|birthday|birth_?day)($|_)",
            PiCategory.DEMOGRAPHIC, Confidence.HIGH, "a date of birth"),
        rule("SENSITIVE_DEMOGRAPHIC", "(^|_)(religion|caste|ethnicity|race|sexual_orientation|political)($|_)",
            PiCategory.SENSITIVE_PERSONAL, Confidence.HIGH, "special-category personal data"),
        rule("DEMOGRAPHIC", "(^|_)(gender|sex|nationality|marital_status|age)($|_)",
            PiCategory.DEMOGRAPHIC, Confidence.MEDIUM, "demographic data"),
        // Before ADDRESS so "ip_address" / "mac_address" are not read as postal addresses
        rule("DEVICE", "(^|_)(ip|ip_?address|mac_?address|device_?id|imei|imsi|advertising_?id)($|_)",
            PiCategory.DEVICE, Confidence.MEDIUM, "a device or network identifier"),
        rule("ADDRESS", "(^|_)(address|addr|street|address_line_?\\d?|house_no|landmark)($|_)",
            PiCategory.LOCATION, Confidence.HIGH, "a postal address"),
        rule("LOCATION", "(^|_)(city|zip|zip_?code|postal_?code|post_?code|pin_?code|latitude|longitude|lat|lng|geo_?location)($|_)",
            PiCategory.LOCATION, Confidence.MEDIUM, "location data"),
        rule("PAYMENT_CARD", "(^|_)(card_?(no|num|number)|credit_?card|debit_?card|cc_?num(ber)?|pan_?token|cvv|cvc)($|_)",
            PiCategory.FINANCIAL, Confidence.HIGH, "payment card data"),
        rule("BANK_ACCOUNT", "(^|_)(iban|account_?(no|num|number)|bank_?account|routing_?(no|number)|ifsc|swift|upi_?id)($|_)",
            PiCategory.FINANCIAL, Confidence.HIGH, "bank account details"),
        rule("INCOME", "(^|_)(salary|income|wage|ctc|net_worth|credit_score)($|_)",
            PiCategory.FINANCIAL, Confidence.MEDIUM, "financial information about a person"),
        rule("CREDENTIAL", "(^|_)(password|passwd|pwd|secret|api_?key|access_?token|refresh_?token|otp|pin|security_answer)($|_)",
            PiCategory.CREDENTIAL, Confidence.HIGH, "a credential or secret"),
        rule("HEALTH", "(^|_)(diagnosis|medical|health|blood_?group|blood_?type|allergy|allergies|prescription|"
            + "disease|disability|medication|treatment|insurance_?(no|number|id))($|_)",
            PiCategory.HEALTH, Confidence.HIGH, "health information"),
        rule("BIOMETRIC", "(^|_)(biometric|fingerprint|face_?(id|template|encoding)|retina|iris|voice_?print)($|_)",
            PiCategory.BIOMETRIC, Confidence.HIGH, "biometric data"),
        rule("USERNAME", "(^|_)(user_?name|login|login_?id|screen_?name|handle)($|_)",
            PiCategory.INDIRECT_IDENTIFIER, Confidence.MEDIUM, "an account identifier"),
        rule("EMPLOYMENT", "(^|_)(employee_?(id|no|number)|emp_?(id|no|code)|designation|job_?title)($|_)",
            PiCategory.EMPLOYMENT, Confidence.LOW, "employment information")
    );

    private static final List<ValueRule> VALUE_RULES = List.of(
        new ValueRule("VALUE_EMAIL", Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"),
            PiCategory.CONTACT, "email addresses"),
        new ValueRule("VALUE_SSN", Pattern.compile("^\\d{3}-\\d{2}-\\d{4}$"),
            PiCategory.DIRECT_IDENTIFIER, "US social security numbers"),
        new ValueRule("VALUE_AADHAAR", Pattern.compile("^\\d{4}\\s?\\d{4}\\s?\\d{4}$"),
            PiCategory.DIRECT_IDENTIFIER, "12-digit national ID numbers"),
        new ValueRule("VALUE_PAN", Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$"),
            PiCategory.DIRECT_IDENTIFIER, "PAN numbers"),
        new ValueRule("VALUE_PHONE", Pattern.compile("^\\+?[\\d\\s().-]{10,16}$"),
            PiCategory.CONTACT, "phone numbers")
    );

    private static final Pattern CARD_CANDIDATE = Pattern.compile("^[\\d\\s-]{13,23}$");

    /** Minimum share of sampled non-null values that must match a value rule. */
    private static final double VALUE_MATCH_THRESHOLD = 0.6;

    private PiDetectionRules() {}

    private static NameRule rule(String rule, String regex, PiCategory category, Confidence confidence, String what) {
        return new NameRule(rule, Pattern.compile(regex), category, confidence, what);
    }

    public static Optional<Match> matchColumn(String tableName, String columnName) {
        String column = normalize(columnName);
        for (NameRule r : NAME_RULES) {
            if (r.pattern().matcher(column).find()) {
                return Optional.of(new Match(r.category(), r.confidence(), "NAME:" + r.rule(),
                    "Column name '" + columnName + "' suggests " + r.what() + "."));
            }
        }
        if (column.equals("name") && PERSON_TABLE.matcher(normalize(tableName)).find()) {
            return Optional.of(new Match(PiCategory.DIRECT_IDENTIFIER, Confidence.MEDIUM, "NAME:PERSON_NAME",
                "Column 'name' in table '" + tableName + "', which appears to hold people, suggests a person's name."));
        }
        return Optional.empty();
    }

    public static boolean isTextType(String dataType) {
        String t = dataType.toLowerCase(Locale.ROOT);
        return t.contains("char") || t.equals("text") || t.equals("citext");
    }

    public static Optional<Match> matchValues(List<String> samples) {
        List<String> values = samples.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
        if (values.size() < 3) {
            return Optional.empty();
        }
        long cards = values.stream().filter(PiDetectionRules::isCardNumber).count();
        if ((double) cards / values.size() >= VALUE_MATCH_THRESHOLD) {
            return Optional.of(valueMatch(PiCategory.FINANCIAL, "VALUE_CARD", "payment card numbers", cards, values.size()));
        }
        for (ValueRule r : VALUE_RULES) {
            long hits = values.stream().filter(v -> r.pattern().matcher(v).matches()).count();
            if ((double) hits / values.size() >= VALUE_MATCH_THRESHOLD) {
                return Optional.of(valueMatch(r.category(), r.rule(), r.what(), hits, values.size()));
            }
        }
        return Optional.empty();
    }

    private static Match valueMatch(PiCategory category, String rule, String what, long hits, int total) {
        return new Match(category, Confidence.HIGH, rule,
            hits + " of " + total + " sampled values look like " + what + ".");
    }

    private static boolean isCardNumber(String value) {
        if (!CARD_CANDIDATE.matcher(value).matches()) return false;
        String digits = value.replaceAll("[\\s-]", "");
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /** "CustomerEmail" / "customer-email" / "CUSTOMER_EMAIL" -> "customer_email". */
    static String normalize(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
            .replaceAll("[^A-Za-z0-9]+", "_")
            .toLowerCase(Locale.ROOT)
            .replaceAll("^_+|_+$", "");
    }
}
