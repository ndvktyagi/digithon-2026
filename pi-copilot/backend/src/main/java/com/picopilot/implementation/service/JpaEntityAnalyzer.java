package com.picopilot.implementation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a JPA entity to find the field behind each approved PI column. The entity itself is never changed:
 * the generated PiEncryptionListener writes and reads the {@code <column>_enc} twins through Hibernate events,
 * and only while the encrypted-read flag is on. Text-based and conservative: anything it cannot map is reported.
 */
public final class JpaEntityAnalyzer {

    public static final String ENCRYPTED_SUFFIX = "_enc";
    /** Column outcome prefix for a column mapped to a String field: {@code MAPPED:<field>}. */
    public static final String MAPPED = "MAPPED";
    public static final String FLAGS_CLASS = "PiEncryptionFlags";

    /**
     * @param entityName     fully qualified entity class name (Hibernate's default entity name)
     * @param fields         approved column -> entity field, for the columns that could be mapped
     * @param columnOutcomes approved column -> MAPPED:field, ALREADY_PRESENT, NO_FIELD or UNSUPPORTED_TYPE:type
     */
    public record Result(String entityName, Map<String, String> fields, Map<String, String> columnOutcomes) {}

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_NAME = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern TABLE_NAME = Pattern.compile("@Table\\s*\\([^)]*?\\bname\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);
    /** Field declaration with the annotations directly above it. */
    private static final Pattern FIELD = Pattern.compile(
        "((?:[ \\t]*@[\\w.]+(?:\\((?:[^()]|\\([^()]*\\))*\\))?[ \\t]*\\R)*)[ \\t]*(?:private|protected|public)\\s+(?!static)([\\w.<>,\\s]+?)\\s+(\\w+)\\s*(?:=[^;]*)?;[ \\t]*\\R?");
    private static final Pattern COLUMN_NAME = Pattern.compile("@Column\\s*\\([^)]*?\\bname\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    private JpaEntityAnalyzer() {}

    public static boolean isEntity(String source) {
        return source.contains("@Entity");
    }

    /** Table the entity maps to: {@code @Table(name)} or the snake_case class name. */
    public static String tableName(String source) {
        Matcher m = TABLE_NAME.matcher(source);
        if (m.find()) return m.group(1);
        Matcher c = CLASS_NAME.matcher(source);
        return c.find() ? snake(c.group(1)) : null;
    }

    public static String packageName(String source) {
        Matcher m = PACKAGE.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    public static String className(String source) {
        Matcher c = CLASS_NAME.matcher(source);
        if (!c.find()) return null;
        String pkg = packageName(source);
        return pkg.isEmpty() ? c.group(1) : pkg + "." + c.group(1);
    }

    /** True if the table name refers to this entity, allowing a simple plural ("employees" / Employee). */
    public static boolean mapsTable(String source, String table) {
        String mapped = tableName(source);
        if (mapped == null) return false;
        String t = table.toLowerCase(Locale.ROOT);
        String m = mapped.toLowerCase(Locale.ROOT);
        return m.equals(t) || (m + "s").equals(t) || (m + "es").equals(t);
    }

    /** @param columns plain column names approved for encryption */
    public static Result analyze(String source, List<String> columns) {
        record Field(String annotations, String type, String name) {}
        List<Field> declared = new ArrayList<>();
        Matcher fm = FIELD.matcher(source);
        while (fm.find()) {
            declared.add(new Field(fm.group(1), fm.group(2).trim(), fm.group(3)));
        }

        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String column : columns) {
            if (source.contains("\"" + column + ENCRYPTED_SUFFIX + "\"")) {
                // The entity maps the encrypted column itself; writing it from the listener would fight Hibernate
                outcomes.put(column, "ALREADY_PRESENT");
                continue;
            }
            Field field = declared.stream()
                .filter(f -> column.equalsIgnoreCase(explicitColumn(f.annotations()))
                    || (explicitColumn(f.annotations()) == null && snake(f.name()).equalsIgnoreCase(column)))
                .findFirst().orElse(null);
            if (field == null) {
                outcomes.put(column, "NO_FIELD");
            } else if (!field.type().equals("String") && !field.type().equals("java.lang.String")) {
                outcomes.put(column, "UNSUPPORTED_TYPE:" + field.type());
            } else {
                fields.put(column, field.name());
                outcomes.put(column, MAPPED + ":" + field.name());
            }
        }
        return new Result(className(source), fields, outcomes);
    }

    private static String explicitColumn(String annotations) {
        Matcher m = COLUMN_NAME.matcher(annotations);
        return m.find() ? m.group(1) : null;
    }

    /** firstName -> first_name, EmployeeRecord -> employee_record. */
    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
