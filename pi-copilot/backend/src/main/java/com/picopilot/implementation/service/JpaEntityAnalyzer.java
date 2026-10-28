package com.picopilot.implementation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a JPA entity to find the field behind each approved PI column and changes it to write both columns and
 * read the encrypted one: next to each plain field it adds {@code <field>Encrypted}, mapped to
 * {@code <column>_enc} through the generated converter; a {@code @PrePersist/@PreUpdate} callback copies the plain
 * value into it and a {@code @PostLoad} callback reads the value back from it. The plain field keeps its mapping,
 * so the plain column stays current and the base branch remains a working rollback. Text-based and conservative:
 * anything it cannot map is reported.
 */
public final class JpaEntityAnalyzer {

    public static final String ENCRYPTED_SUFFIX = "_enc";
    /** Column outcome prefix for a column mapped to a String field: {@code MAPPED:<field>}. */
    public static final String MAPPED = "MAPPED";
    /**
     * Column outcome prefix for a column whose field an earlier Finalize already moved onto {@code <column>_enc}:
     * {@code RETIRED:<field>}. The code needs no change; only the plain column is left to drop.
     */
    public static final String RETIRED = "RETIRED";
    /** Ends every statement PI Co-Pilot adds to a callback method, so later rounds and Finalize can find it. */
    static final String MARKER = "// PI Co-Pilot";
    static final String WRITE_METHOD = "piCopilotEncrypt";
    static final String READ_METHOD = "piCopilotDecrypt";

    /**
     * @param entityName     fully qualified entity class name (Hibernate's default entity name)
     * @param fields         approved column -> entity field, for the columns that could be mapped
     * @param columnOutcomes approved column -> MAPPED:field, RETIRED:field, ALREADY_PRESENT, NO_FIELD or
     *                       UNSUPPORTED_TYPE:type
     */
    public record Result(String entityName, Map<String, String> fields, Map<String, String> columnOutcomes) {}

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_NAME = Pattern.compile("\\bclass\\s+(\\w+)");
    private static final Pattern TABLE_NAME = Pattern.compile("@Table\\s*\\([^)]*?\\bname\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);
    /** Field declaration with the annotations directly above it. */
    private static final Pattern FIELD = Pattern.compile(
        "((?:[ \\t]*@[\\w.]+(?:\\((?:[^()]|\\([^()]*\\))*\\))?[ \\t]*\\R)*)[ \\t]*(?:private|protected|public)\\s+(?!static)([\\w.<>,\\s]+?)\\s+(\\w+)\\s*(?:=[^;]*)?;[ \\t]*\\R?");
    private static final Pattern COLUMN_NAME = Pattern.compile("@Column\\s*\\([^)]*?\\bname\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);
    /** {@code @Column} with or without attributes (group 1), not {@code @ColumnTransformer}. */
    private static final Pattern COLUMN_ANNOTATION = Pattern.compile("@Column\\b(?:\\s*\\(((?:[^()]|\\([^()]*\\))*)\\))?");
    private static final List<String> WRITE_EVENTS = List.of("PrePersist", "PreUpdate");
    private static final String READ_EVENT = "PostLoad";

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

    /** A field declaration; {@code annStart}/{@code annEnd} delimit the annotation lines above it. */
    private record Field(String annotations, String type, String name, int annStart, int annEnd, int end) {}

    private static List<Field> fields(String source) {
        List<Field> declared = new ArrayList<>();
        Matcher fm = FIELD.matcher(source);
        while (fm.find()) {
            declared.add(new Field(fm.group(1), fm.group(2).trim(), fm.group(3), fm.start(1), fm.end(1), fm.end()));
        }
        return declared;
    }

    private static Field fieldFor(List<Field> declared, String column) {
        return declared.stream()
            .filter(f -> column.equalsIgnoreCase(explicitColumn(f.annotations()))
                || (explicitColumn(f.annotations()) == null && snake(f.name()).equalsIgnoreCase(column)))
            .findFirst().orElse(null);
    }

    /** The String field mapped to {@code <column>_enc} through a converter, as a successful Finalize leaves it. */
    private static Field retiredField(List<Field> declared, String column) {
        return declared.stream()
            .filter(f -> (column + ENCRYPTED_SUFFIX).equalsIgnoreCase(explicitColumn(f.annotations()))
                && f.annotations().contains("@Convert")
                && (f.type().equals("String") || f.type().equals("java.lang.String")))
            .findFirst().orElse(null);
    }

    /** @param columns plain column names approved for encryption */
    public static Result analyze(String source, List<String> columns) {
        List<Field> declared = fields(source);
        Map<String, String> fields = new LinkedHashMap<>();
        Map<String, String> outcomes = new LinkedHashMap<>();
        for (String column : columns) {
            Field field = fieldFor(declared, column);
            Field retired = field == null ? retiredField(declared, column) : null;
            if (retired != null) {
                // An earlier release already moved the field onto the encrypted column; the plain one is a leftover
                outcomes.put(column, RETIRED + ":" + retired.name());
            } else if (field == null) {
                outcomes.put(column, "NO_FIELD");
            } else if (!field.type().equals("String") && !field.type().equals("java.lang.String")) {
                outcomes.put(column, "UNSUPPORTED_TYPE:" + field.type());
            } else if (source.contains("\"" + column + ENCRYPTED_SUFFIX + "\"") && !hasEncryptedField(source, column, field.name())) {
                // The entity maps the encrypted column some other way; changing it could break existing data
                outcomes.put(column, "ALREADY_PRESENT");
            } else {
                fields.put(column, field.name());
                outcomes.put(column, MAPPED + ":" + field.name());
            }
        }
        return new Result(className(source), fields, outcomes);
    }

    /**
     * Adds {@code <field>Encrypted} after each mapped field and the callbacks that keep it current and read from it.
     * Existing callback methods ({@code @PrePersist}, {@code @PreUpdate}, {@code @PostLoad}) get the statements
     * added at the top, since JPA allows one method per event; otherwise new methods are added at the end of the
     * class. Running it again on its own output changes nothing, and new columns are added to the same methods.
     *
     * @param fields    approved column -> entity field, as returned by {@link #analyze}
     * @param converter fully qualified name of the generated converter
     */
    public static String withEncryptedFields(String source, Map<String, String> fields, String converter) {
        if (fields.isEmpty()) return source;
        String converterClass = converter.substring(converter.lastIndexOf('.') + 1);
        String out = source;
        // Fields, from the bottom up so earlier offsets stay valid
        List<Field> declared = fields(out);
        record Insert(int at, String text) {}
        List<Insert> inserts = new ArrayList<>();
        fields.forEach((column, fieldName) -> {
            if (hasEncryptedField(source, column, fieldName)) return;
            Field field = declared.stream().filter(f -> f.name().equals(fieldName)).findFirst().orElse(null);
            if (field == null) return;
            String indent = indentOf(source, field.end());
            inserts.add(new Insert(field.end(), "\n" + indent + "// PI Co-Pilot: encrypted copy of \"" + column
                + "\". Written on every save; " + fieldName + " is read from it.\n"
                + indent + "@Column(name = \"" + column + ENCRYPTED_SUFFIX + "\")\n"
                + indent + "@Convert(converter = " + converterClass + ".class)\n"
                + indent + "private String " + encryptedName(fieldName) + ";\n"));
        });
        inserts.sort((a, b) -> Integer.compare(b.at(), a.at()));
        StringBuilder sb = new StringBuilder(out);
        inserts.forEach(i -> sb.insert(i.at(), i.text()));
        out = sb.toString();

        List<String> writes = new ArrayList<>();
        List<String> reads = new ArrayList<>();
        fields.values().forEach(f -> {
            writes.add("this." + encryptedName(f) + " = this." + f + "; " + MARKER);
            reads.add("if (this." + encryptedName(f) + " != null) this." + f + " = this." + encryptedName(f) + "; " + MARKER);
        });
        List<String> missingWrites = new ArrayList<>();
        for (String event : WRITE_EVENTS) {
            String updated = addToCallback(out, event, writes);
            if (updated == null) missingWrites.add(event);
            else out = updated;
        }
        String updated = addToCallback(out, READ_EVENT, reads);
        boolean missingRead = updated == null;
        if (!missingRead) out = updated;

        String indent = "    ";
        StringBuilder methods = new StringBuilder();
        if (!missingWrites.isEmpty()) {
            methods.append('\n').append(indent).append("// PI Co-Pilot: keeps the encrypted copies of personal data current.\n");
            missingWrites.forEach(e -> methods.append(indent).append('@').append(e).append('\n'));
            methods.append(indent).append("void ").append(WRITE_METHOD).append("() {\n");
            writes.forEach(w -> methods.append(indent).append(indent).append(w).append('\n'));
            methods.append(indent).append("}\n");
        }
        if (missingRead) {
            methods.append('\n').append(indent).append("// PI Co-Pilot: personal data is read from the encrypted copies.\n")
                .append(indent).append('@').append(READ_EVENT).append('\n')
                .append(indent).append("void ").append(READ_METHOD).append("() {\n");
            reads.forEach(r -> methods.append(indent).append(indent).append(r).append('\n'));
            methods.append(indent).append("}\n");
        }
        if (!methods.isEmpty()) {
            int close = out.lastIndexOf('}');
            String before = out.substring(0, close).replaceAll("\\s+$", "\n");
            out = before + methods + out.substring(close);
        }
        if (out.equals(source)) return source;

        String persistence = persistence(out);
        out = addImport(out, persistence + ".Column");
        out = addImport(out, persistence + ".Convert");
        for (String event : missingWrites) out = addImport(out, persistence + "." + event);
        if (missingRead) out = addImport(out, persistence + "." + READ_EVENT);
        if (!packageName(out).equals(packageOf(converter))) out = addImport(out, converter);
        return out;
    }

    /**
     * Adds the statements at the top of the method annotated with {@code @<event>}, skipping those it already has.
     *
     * @return the updated source, or null if no method has that annotation
     */
    private static String addToCallback(String source, String event, List<String> statements) {
        Matcher m = Pattern.compile("@" + event + "\\b(?!\\s*\\()").matcher(source);
        if (!m.find()) return null;
        int open = source.indexOf('{', m.end());
        int close = open < 0 ? -1 : NativeQueryEncryptor.closingBrace(source, open);
        if (close < 0) return null;
        String body = source.substring(open, close);
        String indent = indentOf(source, m.start() + 1) + "    ";
        StringBuilder added = new StringBuilder();
        for (String s : statements) {
            if (!body.contains(s)) added.append('\n').append(indent).append(s);
        }
        if (added.isEmpty()) return source;
        return source.substring(0, open + 1) + added + source.substring(open + 1);
    }

    static String encryptedName(String fieldName) {
        return fieldName + "Encrypted";
    }

    /** True if the entity has the generated {@code <field>Encrypted} field for {@code <column>_enc}. */
    private static boolean hasEncryptedField(String source, String column, String fieldName) {
        return fields(source).stream().anyMatch(f -> f.name().equals(encryptedName(fieldName))
            && (column + ENCRYPTED_SUFFIX).equalsIgnoreCase(explicitColumn(f.annotations())));
    }

    private static String persistence(String source) {
        return source.contains("javax.persistence") ? "javax.persistence" : "jakarta.persistence";
    }

    private static String packageOf(String type) {
        return type.substring(0, Math.max(0, type.lastIndexOf('.')));
    }

    /** Adds {@code import <type>;} after the package line unless the type or its package is already imported. */
    private static String addImport(String source, String type) {
        String pkg = packageOf(type);
        if (source.contains("import " + pkg + ".*;") || source.contains("import " + type + ";")) {
            return source;
        }
        Matcher m = PACKAGE.matcher(source);
        int at = m.find() ? m.end() : 0;
        return source.substring(0, at) + "\n\nimport " + type + ";" + source.substring(at).replaceFirst("^\\R+", "\n");
    }

    /**
     * Release cleanup: the entity stops mapping the plain columns. Each mapped field moves to its
     * {@code <column>_enc} column through the converter (encrypted on write, decrypted on read); the
     * {@code <field>Encrypted} copy and the generated callback code are removed, and table constraints on the plain
     * column (unique keys and indexes, meaningless on ciphertext) are dropped. Getters, setters and callers are
     * unchanged. Running it again on its own output changes nothing.
     *
     * @param fields    plain column -> entity field, for the columns mapped in Implementation
     * @param converter fully qualified name of the generated converter
     */
    public static String withPlainColumnsRemoved(String source, Map<String, String> fields, String converter) {
        String out = withoutGeneratedCallbacks(source);
        for (Map.Entry<String, String> e : fields.entrySet()) {
            out = withoutEncryptedCopy(out, e.getKey(), e.getValue());
            out = mappedToEncryptedColumn(out, e.getKey(), e.getValue(), converter.substring(converter.lastIndexOf('.') + 1));
            out = withoutTableConstraints(out, e.getKey());
        }
        if (out.equals(source)) return source;
        String persistence = persistence(out);
        out = addImport(out, persistence + ".Column");
        out = addImport(out, persistence + ".Convert");
        if (!packageName(out).equals(packageOf(converter))) out = addImport(out, converter);
        return out;
    }

    /** Removes the generated callback methods (with their comment) and the marked statements added to others. */
    private static String withoutGeneratedCallbacks(String source) {
        String out = source;
        for (String method : List.of(WRITE_METHOD, READ_METHOD)) {
            Matcher m = Pattern.compile("\\bvoid\\s+" + method + "\\s*\\(\\s*\\)\\s*\\{").matcher(out);
            if (!m.find()) continue;
            int close = NativeQueryEncryptor.closingBrace(out, m.end() - 1);
            if (close < 0) continue;
            int from = lineStart(out, m.start());
            // The annotations and the "// PI Co-Pilot: ..." comment above, and the blank line before them
            while (from > 0) {
                int prev = lineStart(out, from - 1);
                String line = out.substring(prev, from).trim();
                if (!line.startsWith("@") && !line.startsWith("//")) break;
                from = prev;
            }
            if (from > 0 && out.substring(lineStart(out, from - 1), from).isBlank()) from = lineStart(out, from - 1);
            int to = close + 1;
            if (to < out.length() && out.charAt(to) == '\n') to++;
            out = out.substring(0, from) + out.substring(to);
        }
        return out.replaceAll("(?m)^[ \\t]*[^\\n]*" + Pattern.quote(MARKER) + "[ \\t]*\\R", "");
    }

    /** Removes the {@code <field>Encrypted} copy added by {@link #withEncryptedFields}, with its comment and blank line. */
    private static String withoutEncryptedCopy(String source, String column, String fieldName) {
        String encrypted = column + ENCRYPTED_SUFFIX;
        Field copy = fields(source).stream()
            .filter(f -> !f.name().equals(fieldName) && encrypted.equalsIgnoreCase(explicitColumn(f.annotations())))
            .findFirst().orElse(null);
        if (copy == null) return source;
        int from = lineStart(source, copy.annStart());
        while (from > 0) {
            int prev = lineStart(source, from - 1);
            if (!source.substring(prev, from).trim().startsWith("//")) break;
            from = prev;
        }
        if (from > 0) {
            int prev = lineStart(source, from - 1);
            if (source.substring(prev, from).isBlank()) from = prev;
        }
        return source.substring(0, from) + source.substring(copy.end());
    }

    /** Points the plain field at {@code <column>_enc} with the converter, keeping its other column settings. */
    private static String mappedToEncryptedColumn(String source, String column, String fieldName, String converterClass) {
        String encrypted = column + ENCRYPTED_SUFFIX;
        Field field = fields(source).stream().filter(f -> f.name().equals(fieldName)).findFirst().orElse(null);
        if (field == null || encrypted.equalsIgnoreCase(explicitColumn(field.annotations()))) return source;
        // Annotations written on the declaration line are not rewritten; mapsEncryptedColumn reports the field
        if (!source.substring(lineStart(source, field.annStart()), field.annStart()).isBlank()) return source;
        String indent = indentOf(source, field.end());
        String annotations = field.annotations();
        Matcher col = COLUMN_ANNOTATION.matcher(annotations);
        boolean hasColumn = col.find();
        String columnAnnotation = "@Column(" + columnAttributes(hasColumn ? col.group(1) : null, encrypted) + ")";
        String updated = hasColumn
            ? annotations.substring(0, col.start()) + columnAnnotation + annotations.substring(col.end())
            : annotations + indent + columnAnnotation + "\n";
        String replacement = indent + "// PI Co-Pilot: stored encrypted in \"" + encrypted + "\"; the plain column \""
            + column + "\" is retired.\n"
            + updated + indent + "@Convert(converter = " + converterClass + ".class)\n";
        return source.substring(0, field.annStart()) + replacement + source.substring(field.annEnd());
    }

    /** True if the field is mapped to {@code <column>_enc} through a converter, as {@link #withPlainColumnsRemoved} leaves it. */
    public static boolean mapsEncryptedColumn(String source, String fieldName, String column) {
        Field field = fields(source).stream().filter(f -> f.name().equals(fieldName)).findFirst().orElse(null);
        return field != null && (column + ENCRYPTED_SUFFIX).equalsIgnoreCase(explicitColumn(field.annotations()))
            && field.annotations().contains("@Convert");
    }

    /**
     * {@code name = "<column>_enc"} plus the original attributes that still apply: length, uniqueness and the column
     * definition describe the plain value, not its ciphertext, so they are dropped.
     */
    private static String columnAttributes(String original, String encryptedColumn) {
        List<String> kept = new ArrayList<>();
        kept.add("name = \"" + encryptedColumn + "\"");
        if (original != null) {
            for (String attribute : splitTopLevel(original)) {
                String name = attribute.contains("=") ? attribute.substring(0, attribute.indexOf('=')).trim() : "";
                if (name.isEmpty() || Set.of("name", "length", "unique", "columnDefinition", "precision", "scale").contains(name)) {
                    continue;
                }
                kept.add(attribute.trim());
            }
        }
        return String.join(", ", kept);
    }

    /** Removes {@code @UniqueConstraint}/{@code @Index} entries of {@code @Table} that use the plain column. */
    private static String withoutTableConstraints(String source, String column) {
        Matcher table = Pattern.compile("@Table\\s*\\(").matcher(source);
        if (!table.find()) return source;
        int close = NativeQueryEncryptor.closingParen(source, table.end() - 1);
        if (close < 0) return source;
        String annotation = source.substring(table.start(), close + 1);
        Pattern uses = Pattern.compile("\"[^\"]*(?<![\\w])" + Pattern.quote(column) + "(?![\\w])[^\"]*\"", Pattern.CASE_INSENSITIVE);
        String updated = annotation;
        for (String kind : List.of("@UniqueConstraint", "@Index")) {
            Matcher m = Pattern.compile(Pattern.quote(kind) + "\\s*\\(").matcher(updated);
            int from = 0;
            while (m.find(from)) {
                int end = NativeQueryEncryptor.closingParen(updated, m.end() - 1);
                if (end < 0) break;
                if (uses.matcher(updated.substring(m.start(), end + 1)).find()) {
                    updated = updated.substring(0, m.start()) + updated.substring(end + 1);
                    from = m.start();
                } else {
                    from = end + 1;
                }
            }
        }
        if (updated.equals(annotation)) return source;
        updated = updated
            .replaceAll("\\{\\s*,\\s*", "{").replaceAll(",\\s*}", "}").replaceAll(",(\\s*),", ",$1")
            .replaceAll(",?\\s*(?:uniqueConstraints|indexes)\\s*=\\s*(?:\\{\\s*})?(?=\\s*[,)])", "")
            .replaceAll("\\(\\s*,\\s*", "(");
        return source.substring(0, table.start()) + updated + source.substring(close + 1);
    }

    /** Splits annotation attributes on commas outside strings and parentheses. */
    private static List<String> splitTopLevel(String attributes) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        boolean quoted = false;
        for (int i = 0; i < attributes.length(); i++) {
            char c = attributes.charAt(i);
            if (c == '"' && (i == 0 || attributes.charAt(i - 1) != '\\')) quoted = !quoted;
            else if (!quoted && (c == '(' || c == '{')) depth++;
            else if (!quoted && (c == ')' || c == '}')) depth--;
            else if (!quoted && depth == 0 && c == ',') {
                parts.add(attributes.substring(start, i));
                start = i + 1;
            }
        }
        if (!attributes.substring(start).isBlank()) parts.add(attributes.substring(start));
        return parts;
    }

    private static int lineStart(String source, int offset) {
        return source.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
    }

    private static String indentOf(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', Math.max(0, offset - 2)) + 1;
        int i = lineStart;
        while (i < source.length() && (source.charAt(i) == ' ' || source.charAt(i) == '\t')) i++;
        String indent = source.substring(lineStart, i);
        return indent.isEmpty() ? "    " : indent;
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
