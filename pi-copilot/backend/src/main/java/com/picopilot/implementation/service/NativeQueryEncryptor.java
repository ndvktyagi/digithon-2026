package com.picopilot.implementation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native SQL queries ({@code @Query(nativeQuery = true)}) read columns without loading the entity, so the entity's
 * converter never sees them. Each one that selects an approved column is rewritten in place to read the encrypted
 * copy, falling back to the plain value for rows not encrypted yet:
 * {@code CASE WHEN e.email_enc IS NOT NULL THEN e.email_enc ELSE e.email END AS email}. The generated
 * PiNativeQueryDecryptor decrypts the "enc:v1:" values in their results. Queries it cannot rewrite safely are
 * reported, not guessed.
 */
public final class NativeQueryEncryptor {

    public static final String UPDATED = "NATIVE_QUERY_UPDATED";
    public static final String UNSUPPORTED = "NATIVE_QUERY_UNSUPPORTED";
    /** Suffix of the twin methods an earlier, flag-gated version generated; the release cleanup removes them. */
    public static final String METHOD_SUFFIX = "PiEncrypted";

    /** @param columnOutcomes column -> UPDATED/UNSUPPORTED, for the approved columns some native query reads */
    public record Result(String source, Map<String, String> columnOutcomes, boolean changed) {}

    /** @param outcomes table -> (column -> UPDATED/UNSUPPORTED) */
    public record MultiResult(String source, Map<String, Map<String, String>> outcomes, boolean changed) {}

    /** {@code @Query(} up to its closing parenthesis, allowing text blocks and nested parentheses in strings. */
    private static final Pattern QUERY_START = Pattern.compile("@Query\\s*\\(");
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NATIVE = Pattern.compile("\\bnativeQuery\\s*=\\s*true\\b");
    /** Method name in a declaration: an identifier followed by "(" that is not an annotation. */
    private static final Pattern METHOD_NAME = Pattern.compile("(?<![@\\w.])(\\w+)\\s*\\(");
    private static final Set<String> NOT_ALIAS = Set.of("where", "join", "inner", "left", "right", "full", "cross",
        "on", "order", "group", "limit", "offset", "having", "union", "natural", "using", "for", "window");

    private NativeQueryEncryptor() {}

    public static boolean hasNativeQuery(String source) {
        return source.contains("@Query") && NATIVE.matcher(source).find();
    }

    /**
     * Rewrites every native query that selects approved columns of the table to read the encrypted copies.
     * Running it again on its own output changes nothing.
     *
     * @param table   approved table (as scanned; may be plural of the entity)
     * @param columns approved plain columns of that table
     */
    public static Result encrypt(String source, String table, List<String> columns) {
        MultiResult r = encrypt(source, Map.of(table, columns));
        return new Result(r.source(), r.outcomes().getOrDefault(table, Map.of()), r.changed());
    }

    /**
     * Same for several approved tables at once, so a query joining two of them is rewritten for both.
     *
     * @param columnsByTable approved table -> its approved plain columns
     */
    public static MultiResult encrypt(String source, Map<String, List<String>> columnsByTable) {
        Map<String, Map<String, String>> outcomes = new LinkedHashMap<>();
        record Span(int end, String sql) {}
        TreeMap<Integer, Span> spans = new TreeMap<>(); // sql start -> sql end and its rewrite
        Matcher start = QUERY_START.matcher(source);
        while (start.find()) {
            int end = closingParen(source, start.end() - 1);
            if (end < 0) break;
            String annotation = source.substring(start.start(), end + 1);
            if (!NATIVE.matcher(annotation).find()) continue;

            // The SQL is the first string literal: a text block or one plain string (concatenation is not rewritten)
            Matcher block = TEXT_BLOCK.matcher(annotation);
            Matcher plain = STRING.matcher(annotation);
            int sqlStart, sqlEnd;
            boolean concatenated;
            if (block.find()) {
                sqlStart = block.start(1);
                sqlEnd = block.end(1);
                concatenated = false;
            } else if (plain.find()) {
                sqlStart = plain.start(1);
                sqlEnd = plain.end(1);
                concatenated = annotation.substring(plain.end()).trim().startsWith("+");
            } else {
                continue;
            }
            String sql = annotation.substring(sqlStart, sqlEnd);

            // The method the annotation belongs to: everything up to the ";" that ends its declaration
            int declEnd = source.indexOf(';', end);
            Matcher name = declEnd < 0 ? null : METHOD_NAME.matcher(source.substring(end + 1, declEnd));
            String method = name != null && name.find() ? name.group(1) : null;

            String rewritten = concatenated || method == null ? null : sql;
            Map<String, List<String>> usedByTable = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> t : columnsByTable.entrySet()) {
                String alias = alias(sql, t.getKey());
                if (alias == null) continue; // query does not read this table
                List<String> used = t.getValue().stream().filter(c -> referenced(selectList(sql), alias, c)).toList();
                if (used.isEmpty()) continue;
                usedByTable.put(t.getKey(), used);
                if (rewritten != null) rewritten = rewrite(rewritten, alias, used);
            }
            if (usedByTable.isEmpty()) continue;
            for (Map.Entry<String, List<String>> t : usedByTable.entrySet()) {
                Map<String, String> tableOutcomes = outcomes.computeIfAbsent(t.getKey(), k -> new LinkedHashMap<>());
                for (String c : t.getValue()) {
                    if (rewritten == null) {
                        tableOutcomes.put(c, UNSUPPORTED);
                    } else {
                        tableOutcomes.putIfAbsent(c, UPDATED);
                    }
                }
            }
            if (rewritten != null && !rewritten.equals(sql)) {
                spans.put(start.start() + sqlStart, new Span(start.start() + sqlEnd, rewritten));
            }
        }
        StringBuilder out = new StringBuilder(source);
        for (Map.Entry<Integer, Span> e : spans.descendingMap().entrySet()) {
            out.replace(e.getKey(), e.getValue().end(), e.getValue().sql());
        }
        return new MultiResult(out.toString(), outcomes, !spans.isEmpty());
    }

    /** Alias the table has in FROM/JOIN (the table name itself if it has none), or null if not read. */
    static String alias(String sql, String table) {
        Matcher m = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+(?:\"?\\w+\"?\\.)?\"?" + Pattern.quote(table)
            + "\"?(?:\\s+(?:AS\\s+)?(\\w+))?").matcher(sql);
        if (!m.find()) return null;
        String alias = m.group(1);
        return alias == null || NOT_ALIAS.contains(alias.toLowerCase(Locale.ROOT)) ? table : alias;
    }

    /** Text between the first SELECT and the first FROM, or null if the query has no simple select list. */
    private static String selectList(String sql) {
        Matcher m = Pattern.compile("(?is)^\\s*SELECT\\s+(?:DISTINCT\\s+)?(.*?)\\bFROM\\b").matcher(sql);
        return m.find() ? m.group(1) : null;
    }

    private static boolean referenced(String selectList, String alias, String column) {
        if (selectList == null) return false;
        return selectList.trim().equals("*") || selectList.contains(alias + ".*")
            || ref(alias, column).matcher(selectList).find();
    }

    /** {@code alias.column} or a bare {@code column}, not part of a longer name. */
    private static Pattern ref(String alias, String column) {
        return Pattern.compile("(?i)(?<![\\w.])(?:" + Pattern.quote(alias) + "\\.)?\"?" + Pattern.quote(column) + "\"?(?![\\w(])");
    }

    /** Rewrites the select list, or returns null when it is not safe to (star selects, subqueries). */
    private static String rewrite(String sql, String alias, List<String> columns) {
        Matcher sel = Pattern.compile("(?is)^(\\s*SELECT\\s+(?:DISTINCT\\s+)?)(.*?)(\\bFROM\\b.*)$").matcher(sql);
        if (!sel.find()) return null;
        String list = sel.group(2);
        if (list.contains("*") || list.toUpperCase(Locale.ROOT).contains("SELECT")) return null;
        String rewritten = list;
        for (String column : columns) {
            if (Pattern.compile("(?i)(?<![\\w.])" + Pattern.quote(alias + "." + column + JpaEntityAnalyzer.ENCRYPTED_SUFFIX)
                + "(?!\\w)").matcher(rewritten).find()) {
                continue; // rewritten earlier
            }
            String plain = alias + "." + column;
            String enc = alias + "." + column + JpaEntityAnalyzer.ENCRYPTED_SUFFIX;
            String expr = "CASE WHEN " + enc + " IS NOT NULL THEN " + enc + " ELSE " + plain + " END";
            // Keep an explicit alias; otherwise name the result after the column so mappings by name still work
            Matcher m = Pattern.compile("(?i)(?<![\\w.])(?:" + Pattern.quote(alias) + "\\.)?\"?" + Pattern.quote(column)
                + "\"?(?![\\w(])(\\s+AS\\s+\\w+)?").matcher(rewritten);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String as = m.group(1) != null ? m.group(1) : " AS " + column;
                m.appendReplacement(sb, Matcher.quoteReplacement(expr + as));
            }
            m.appendTail(sb);
            rewritten = sb.toString();
        }
        return sel.group(1) + rewritten + sel.group(3);
    }

    /**
     * @param blockers queries that cannot run once the plain columns are dropped (they filter, join or sort on
     *                 them, select {@code *}, or build their SQL by concatenation)
     */
    public record FinalizeResult(String source, List<String> blockers, boolean changed) {}

    /**
     * Finalize: removes the {@code <name>PiEncrypted} twins and makes every native query read the encrypted
     * columns itself ({@code e.email} becomes {@code e.email_enc AS email}), so it still returns the same row shape
     * once the plain columns are gone; the generated PiNativeQueryDecryptor decrypts the returned values.
     * Running it again on its own output changes nothing.
     *
     * @param columnsByTable approved table -> its approved plain columns
     */
    public static FinalizeResult finalizeQueries(String source, Map<String, List<String>> columnsByTable) {
        String stripped = CASE_FALLBACK.matcher(withoutTwins(source)).replaceAll("$1" + JpaEntityAnalyzer.ENCRYPTED_SUFFIX);
        List<String> blockers = new ArrayList<>();
        record Span(int end, String sql) {}
        TreeMap<Integer, Span> spans = new TreeMap<>(); // sql start -> sql end and its rewrite
        Matcher start = QUERY_START.matcher(stripped);
        while (start.find()) {
            int end = closingParen(stripped, start.end() - 1);
            if (end < 0) break;
            String annotation = stripped.substring(start.start(), end + 1);
            if (!NATIVE.matcher(annotation).find()) continue;
            Matcher block = TEXT_BLOCK.matcher(annotation);
            Matcher plain = STRING.matcher(annotation);
            int sqlStart, sqlEnd;
            boolean concatenated;
            if (block.find()) {
                sqlStart = block.start(1);
                sqlEnd = block.end(1);
                concatenated = false;
            } else if (plain.find()) {
                sqlStart = plain.start(1);
                sqlEnd = plain.end(1);
                concatenated = annotation.substring(plain.end()).trim().startsWith("+");
            } else {
                continue;
            }
            String sql = annotation.substring(sqlStart, sqlEnd);
            int declEnd = stripped.indexOf(';', end);
            Matcher name = declEnd < 0 ? null : METHOD_NAME.matcher(stripped.substring(end + 1, declEnd));
            String method = name != null && name.find() ? name.group(1) : "a native query";

            String rewritten = sql;
            for (Map.Entry<String, List<String>> t : columnsByTable.entrySet()) {
                String alias = alias(sql, t.getKey());
                if (alias == null) continue;
                String selectList = selectList(sql);
                boolean star = selectList != null && (selectList.trim().equals("*") || selectList.contains(alias + ".*"));
                List<String> used = t.getValue().stream().filter(c -> star || plainRef(alias, c).matcher(sql).find()).toList();
                if (used.isEmpty()) continue;
                Matcher sel = Pattern.compile("(?is)^(\\s*SELECT\\s+(?:DISTINCT\\s+)?)(.*?)(\\bFROM\\b.*)$").matcher(rewritten);
                String table = t.getKey();
                if (concatenated || !sel.find() || sel.group(2).contains("*")
                    || sel.group(2).toUpperCase(Locale.ROOT).contains("SELECT")) {
                    used.forEach(c -> blockers.add(method + " reads " + table + "." + c
                        + " in a form that cannot be rewritten (SELECT *, a subquery or concatenated SQL)"));
                    continue;
                }
                for (String c : used) {
                    if (plainRef(alias, c).matcher(sel.group(3)).find()) {
                        blockers.add(method + " filters, joins or sorts on " + table + "." + c
                            + ", which cannot be done on encrypted values");
                    }
                }
                String list = sel.group(2);
                for (String c : used) {
                    Matcher m = Pattern.compile("(?i)(?<![\\w.])(?<!\\bAS\\s{1,8})(?:" + Pattern.quote(alias) + "\\.)?\"?"
                        + Pattern.quote(c) + "\"?(?![\\w(])(\\s+AS\\s+\\w+)?").matcher(list);
                    StringBuilder sb = new StringBuilder();
                    while (m.find()) {
                        String as = m.group(1) != null ? m.group(1) : " AS " + c;
                        m.appendReplacement(sb, Matcher.quoteReplacement(alias + "." + c + JpaEntityAnalyzer.ENCRYPTED_SUFFIX + as));
                    }
                    m.appendTail(sb);
                    list = sb.toString();
                }
                rewritten = sel.group(1) + list + sel.group(3);
            }
            if (!rewritten.equals(sql)) {
                spans.put(start.start() + sqlStart, new Span(start.start() + sqlEnd, rewritten));
            }
        }
        StringBuilder out = new StringBuilder(stripped);
        for (Map.Entry<Integer, Span> e : spans.descendingMap().entrySet()) {
            out.replace(e.getKey(), e.getValue().end(), e.getValue().sql());
        }
        String result = out.toString();
        return new FinalizeResult(result, blockers, !result.equals(source));
    }

    /** The fallback written by {@link #encrypt}: {@code CASE WHEN x_enc IS NOT NULL THEN x_enc ELSE x END} (group 1 = x). */
    private static final Pattern CASE_FALLBACK = Pattern.compile("(?i)CASE\\s+WHEN\\s+([\\w.]+)"
        + JpaEntityAnalyzer.ENCRYPTED_SUFFIX + "\\s+IS\\s+NOT\\s+NULL\\s+THEN\\s+\\1" + JpaEntityAnalyzer.ENCRYPTED_SUFFIX
        + "\\s+ELSE\\s+\\1\\s+END");

    /** {@code alias.column} or a bare {@code column} outside an {@code AS} alias, not part of a longer name. */
    private static Pattern plainRef(String alias, String column) {
        return Pattern.compile("(?i)(?<![\\w.])(?<!\\bAS\\s{1,8})(?:" + Pattern.quote(alias) + "\\.)?\"?"
            + Pattern.quote(column) + "\"?(?![\\w(])");
    }

    /** Removes the generated {@code <name>PiEncrypted} twins (javadoc, annotations and declaration). */
    static String withoutTwins(String source) {
        TreeMap<Integer, Integer> remove = new TreeMap<>();
        Matcher start = QUERY_START.matcher(source);
        while (start.find()) {
            int end = closingParen(source, start.end() - 1);
            if (end < 0) break;
            int declEnd = source.indexOf(';', end);
            if (declEnd < 0) break;
            Matcher name = METHOD_NAME.matcher(source.substring(end + 1, declEnd));
            if (!name.find() || !name.group(1).endsWith(METHOD_SUFFIX)) continue;
            int doc = source.lastIndexOf("/** PI Co-Pilot:", start.start());
            int previous = source.lastIndexOf(';', start.start());
            int from;
            if (doc > previous) {
                // Twins are inserted right after the original's ";", separated by a blank line
                from = source.substring(previous + 1, doc).isBlank() ? previous + 1 : source.lastIndexOf('\n', doc) + 1;
            } else {
                from = source.lastIndexOf('\n', start.start()) + 1;
            }
            remove.put(from, declEnd + 1);
        }
        StringBuilder out = new StringBuilder(source);
        for (Map.Entry<Integer, Integer> e : remove.descendingMap().entrySet()) {
            out.delete(e.getKey(), e.getValue());
        }
        return out.toString();
    }

    private static final Pattern DERIVED_QUERY = Pattern.compile(
        "\\b((?:find|read|get|query|search|stream|exists|count|delete|remove)\\w*?By(\\w+))\\s*\\(");

    /**
     * Repository queries on fields that are now stored encrypted. They still run, but compare against ciphertext
     * with a random IV, so equality, LIKE and sorting no longer work: derived queries such as
     * {@code existsByEmailIgnoreCase} and JPQL conditions on the field.
     *
     * @param entity simple class name of the entity
     * @param fields entity fields now stored encrypted
     */
    public static List<String> encryptedFieldQueries(String source, String entity, List<String> fields) {
        if (!Pattern.compile("<\\s*" + Pattern.quote(entity) + "\\s*,").matcher(source).find()
            && !Pattern.compile("(?i)\\bfrom\\s+" + Pattern.quote(entity) + "\\b").matcher(source).find()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher derived = DERIVED_QUERY.matcher(source);
        while (derived.find()) {
            for (String field : fields) {
                String cap = Character.toUpperCase(field.charAt(0)) + field.substring(1);
                if (Pattern.compile("(?:^|And|Or|OrderBy)" + Pattern.quote(cap) + "(?![a-z0-9])").matcher(derived.group(2)).find()) {
                    found.add(derived.group(1) + " queries " + entity + "." + field);
                }
            }
        }
        Matcher query = QUERY_START.matcher(source);
        while (query.find()) {
            int end = closingParen(source, query.end() - 1);
            if (end < 0) break;
            String annotation = source.substring(query.start(), end + 1);
            if (NATIVE.matcher(annotation).find()) continue;
            int declEnd = source.indexOf(';', end);
            Matcher name = declEnd < 0 ? null : METHOD_NAME.matcher(source.substring(end + 1, declEnd));
            String method = name != null && name.find() ? name.group(1) : "a JPQL query";
            for (String field : fields) {
                if (Pattern.compile("(?<![\\w])\\w+\\." + Pattern.quote(field) + "(?![\\w])").matcher(annotation).find()) {
                    found.add(method + " queries " + entity + "." + field);
                }
            }
        }
        return found.stream().distinct().toList();
    }

    /** Index of the brace closing the one at {@code open}, skipping string and character literals and comments. */
    static int closingBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int close = s.indexOf("\"\"\"", i + 3);
                if (close < 0) return -1;
                i = close + 2;
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < s.length() && s.charAt(i) != quote) {
                    if (s.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (s.startsWith("//", i)) {
                int eol = s.indexOf('\n', i);
                if (eol < 0) return -1;
                i = eol;
            } else if (s.startsWith("/*", i)) {
                int close = s.indexOf("*/", i + 2);
                if (close < 0) return -1;
                i = close + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Index of the parenthesis closing the one at {@code open}, skipping string literals and text blocks. */
    static int closingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int close = s.indexOf("\"\"\"", i + 3);
                if (close < 0) return -1;
                i = close + 2;
            } else if (c == '"') {
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    if (s.charAt(i) == '\\') i++;
                    i++;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
