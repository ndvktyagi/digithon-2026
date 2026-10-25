package com.picopilot.implementation.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native SQL queries ({@code @Query(nativeQuery = true)}) read columns without loading the entity, so the
 * generated entity listener never sees them. The original query methods are left exactly as they are; next to
 * each one that selects an approved column this adds a twin method {@code <name>PiEncrypted} whose SELECT list
 * reads the encrypted copy: {@code CASE WHEN e.email_enc IS NOT NULL THEN e.email_enc ELSE e.email END AS email}.
 * The generated PiNativeQueryRouter calls the twin (and decrypts its rows) only while encrypted read is on;
 * otherwise the original method runs untouched. Queries it cannot rewrite safely are reported, not guessed.
 */
public final class NativeQueryEncryptor {

    public static final String UPDATED = "NATIVE_QUERY_UPDATED";
    public static final String UNSUPPORTED = "NATIVE_QUERY_UNSUPPORTED";
    /** Suffix of the generated twin of a native query method. */
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
     * Adds a {@code <name>PiEncrypted} twin after every native query method that selects approved columns of the
     * table. Existing lines are never edited; running it again on its own output adds nothing.
     *
     * @param table   approved table (as scanned; may be plural of the entity)
     * @param columns approved plain columns of that table
     */
    public static Result encrypt(String source, String table, List<String> columns) {
        MultiResult r = encrypt(source, Map.of(table, columns));
        return new Result(r.source(), r.outcomes().getOrDefault(table, Map.of()), r.changed());
    }

    /**
     * Same for several approved tables at once, so a query joining two of them gets one twin covering both.
     *
     * @param columnsByTable approved table -> its approved plain columns
     */
    public static MultiResult encrypt(String source, Map<String, List<String>> columnsByTable) {
        Map<String, Map<String, String>> outcomes = new LinkedHashMap<>();
        TreeMap<Integer, String> inserts = new TreeMap<>();
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
            if (method != null && method.endsWith(METHOD_SUFFIX)) continue; // a twin generated earlier

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
            if (rewritten == null
                || Pattern.compile("\\b" + Pattern.quote(method + METHOD_SUFFIX) + "\\s*\\(").matcher(source).find()) {
                continue; // nothing to add, or the twin exists already
            }
            int lineStart = source.lastIndexOf('\n', start.start()) + 1;
            String indent = source.substring(lineStart, start.start()).replaceAll("\\S", "");
            int from = annotationsAbove(source, lineStart);
            int nameStart = end + 1 + name.start(1);
            int nameEnd = end + 1 + name.end(1);
            String twin = indent + "/** PI Co-Pilot: " + method + " reading the encrypted columns. PiNativeQueryRouter calls it"
                + " instead of " + method + " only while encrypted read is on. */\n"
                + source.substring(from, start.start() + sqlStart) + rewritten
                + source.substring(start.start() + sqlEnd, nameStart) + method + METHOD_SUFFIX
                + source.substring(nameEnd, declEnd + 1);
            inserts.merge(declEnd + 1, "\n\n" + twin, String::concat);
        }
        StringBuilder out = new StringBuilder(source);
        for (Map.Entry<Integer, String> e : inserts.descendingMap().entrySet()) {
            out.insert(e.getKey(), e.getValue());
        }
        return new MultiResult(out.toString(), outcomes, !inserts.isEmpty());
    }

    /** Start of the annotation lines directly above {@code lineStart} (e.g. {@code @Modifying}), or lineStart. */
    private static int annotationsAbove(String source, int lineStart) {
        int from = lineStart;
        while (from > 0) {
            int prev = source.lastIndexOf('\n', from - 2) + 1;
            if (!source.substring(prev, from).trim().startsWith("@")) break;
            from = prev;
        }
        return from;
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

    /** Index of the parenthesis closing the one at {@code open}, skipping string literals and text blocks. */
    private static int closingParen(String s, int open) {
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
