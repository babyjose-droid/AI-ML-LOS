package com.rhythm.los.docgen;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The loan document template language. Templates are HTML with:
 * <ul>
 *   <li>{@code {{applicant.name}}} a value, HTML-escaped; filters after a bar: {@code {{loan.amount | rupees}}}, {@code | words}
 *       (amount in words, Indian numbering), {@code | number}, {@code | date}, {@code | upper}, {@code | lower}, {@code | percent}</li>
 *   <li>{@code {{#each coApplicants}} … {{name}} … {{@number}} … {{/each}}} a repeated section ({@code {{else}}} when empty)</li>
 *   <li>{@code {{#if guarantors}} … {{else}} … {{/if}}}, {@code {{#if loan.secured == true}}}, {@code {{#unless …}}}: comparisons ==, !=, &gt;, &lt;, &gt;=, &lt;=</li>
 *   <li>{@code {{> CL_INTEREST}}} a clause from the clause library or a built-in block (KFS_STANDARD, SCHEDULE_TABLE, …)</li>
 * </ul>
 * Pure: no database, so it is unit tested and gives the editor exact error messages.
 */
public final class DocTemplateEngine {
    private DocTemplateEngine() {}

    static final Pattern TAG = Pattern.compile("\\{\\{\\s*(.*?)\\s*}}", Pattern.DOTALL);
    public static final Set<String> FILTERS = Set.of("rupees", "words", "number", "date", "upper", "lower", "percent");
    static final int MAX_DEPTH = 6;

    public record Result(String html, Set<String> missing) {}

    sealed interface Node permits Text, Value, Block, Partial {}
    record Text(String text) implements Node {}
    record Value(String expr) implements Node {}
    record Block(String kind, String expr, List<Node> body, List<Node> orElse) implements Node {}
    record Partial(String name) implements Node {}

    // ---------------------------------------------------------------- parsing

    public static class TemplateError extends RuntimeException {
        public TemplateError(String m) { super(m); }
    }

    static List<Node> parse(String tpl) {
        Deque<Object[]> stack = new ArrayDeque<>();   // [kind, expr, body, orElse, inElse]
        List<Node> root = new ArrayList<>();
        List<Node> cur = root;
        Matcher m = TAG.matcher(tpl == null ? "" : tpl);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) cur.add(new Text(tpl.substring(last, m.start())));
            last = m.end();
            String t = m.group(1).replace(' ', ' ').trim();
            if (t.startsWith("#")) {
                String[] p = t.substring(1).trim().split("\\s+", 2);
                String kind = p[0];
                if (!Set.of("each", "if", "unless").contains(kind)) throw new TemplateError("Unknown block {{#" + kind + "}}: use #each, #if or #unless");
                if (p.length < 2 || p[1].isBlank()) throw new TemplateError("{{#" + kind + "}} needs a field, e.g. {{#" + kind + " guarantors}}");
                List<Node> body = new ArrayList<>(), orElse = new ArrayList<>();
                stack.push(new Object[]{kind, p[1].trim(), body, orElse, false, cur});
                cur = body;
            } else if (t.equals("else")) {
                Object[] top = stack.peek();
                if (top == null) throw new TemplateError("{{else}} outside a block");
                if ((boolean) top[4]) throw new TemplateError("Two {{else}} in one block");
                top[4] = true;
                @SuppressWarnings("unchecked") List<Node> e = (List<Node>) top[3];
                cur = e;
            } else if (t.startsWith("/")) {
                String kind = t.substring(1).trim();
                Object[] top = stack.poll();
                if (top == null) throw new TemplateError("{{/" + kind + "}} has no matching {{#" + kind + "}}");
                if (!top[0].equals(kind)) throw new TemplateError("{{#" + top[0] + " " + top[1] + "}} is closed by {{/" + kind + "}}");
                @SuppressWarnings("unchecked") List<Node> body = (List<Node>) top[2];
                @SuppressWarnings("unchecked") List<Node> orElse = (List<Node>) top[3];
                @SuppressWarnings("unchecked") List<Node> parent = (List<Node>) top[5];
                parent.add(new Block(kind, (String) top[1], body, orElse));
                cur = parent;
            } else if (t.startsWith(">")) {
                String name = t.substring(1).trim();
                if (!name.matches("[A-Z][A-Z0-9_]{1,39}")) throw new TemplateError("Clause names are capitals, digits and _: {{> " + name + "}}");
                cur.add(new Partial(name));
            } else if (t.startsWith("!")) {
                // comment
            } else {
                if (t.isEmpty()) throw new TemplateError("Empty {{ }}");
                for (String f : filters(t)) if (!FILTERS.contains(f)) throw new TemplateError("Unknown format \"" + f + "\" in {{" + t + "}}: use " + new TreeSet<>(FILTERS));
                cur.add(new Value(t));
            }
        }
        if (last < (tpl == null ? 0 : tpl.length())) cur.add(new Text(tpl.substring(last)));
        if (!stack.isEmpty()) throw new TemplateError("{{#" + stack.peek()[0] + " " + stack.peek()[1] + "}} is not closed");
        return root;
    }

    static String path(String expr) { return expr.split("\\|")[0].trim(); }

    static List<String> filters(String expr) {
        String[] p = expr.split("\\|");
        List<String> out = new ArrayList<>();
        for (int i = 1; i < p.length; i++) out.add(p[i].trim());
        return out;
    }

    // ---------------------------------------------------------------- checking

    /**
     * Problems an editor should fix before saving: syntax, fields not in the catalog (inside #each, names of the list's
     * items are allowed), and unknown clauses.
     */
    public static List<String> check(String tpl, Map<String, Set<String>> fields, Set<String> partials) {
        List<String> e = new ArrayList<>();
        List<Node> nodes;
        try { nodes = parse(tpl); } catch (TemplateError x) { return List.of(x.getMessage()); }
        walkCheck(nodes, fields, partials, new ArrayDeque<>(), e);
        return e.stream().distinct().toList();
    }

    private static void walkCheck(List<Node> nodes, Map<String, Set<String>> fields, Set<String> partials, Deque<String> lists, List<String> e) {
        for (Node n : nodes) {
            if (n instanceof Value v) checkPath(path(v.expr()), fields, lists, e);
            else if (n instanceof Partial p) { if (!partials.contains(p.name())) e.add("No clause or block called " + p.name()); }
            else if (n instanceof Block b) {
                for (String side : condPaths(b.expr())) checkPath(side, fields, lists, e);
                if ("each".equals(b.kind())) {
                    String list = path(b.expr());
                    if (!fields.containsKey(list) || fields.get(list).isEmpty()) {
                        if (!isKnown(list, fields, lists)) e.add("{{#each " + list + "}}: unknown list");
                        else e.add("{{#each " + list + "}}: " + list + " is not a list");
                    }
                    lists.push(list);
                    walkCheck(b.body(), fields, partials, lists, e);
                    lists.pop();
                } else walkCheck(b.body(), fields, partials, lists, e);
                walkCheck(b.orElse(), fields, partials, lists, e);
            }
        }
    }

    static List<String> condPaths(String expr) {
        String[] c = splitCondition(expr);
        List<String> out = new ArrayList<>();
        out.add(c[0]);
        if (c[2] != null && !isLiteral(c[2])) out.add(c[2]);
        return out;
    }

    private static boolean isKnown(String p, Map<String, Set<String>> fields, Deque<String> lists) {
        if (p.equals("this") || p.startsWith("@")) return !lists.isEmpty();
        if (fields.containsKey(p)) return true;
        for (String l : lists) {
            Set<String> item = fields.get(l);
            if (item != null && (item.contains(p) || item.contains(p.split("\\.")[0]))) return true;
        }
        return false;
    }

    private static void checkPath(String p, Map<String, Set<String>> fields, Deque<String> lists, List<String> e) {
        if (!isKnown(p, fields, lists)) e.add("Unknown field {{" + p + "}}" + (lists.isEmpty() ? "" : " (inside {{#each " + lists.peek() + "}})"));
    }

    // ---------------------------------------------------------------- what a document depends on

    /**
     * The data paths a template (with its clauses) reads: plain values by full path, repeated sections by the list's
     * path. A document is out of date when any of these values changes, and only then.
     */
    public static Set<String> paths(String tpl, Function<String, String> partials) {
        Set<String> out = new TreeSet<>();
        collect(parse(tpl), partials, out, 0, 0);
        out.removeIf(p -> p.equals("this") || p.startsWith("@") || p.equals("today"));
        return out;
    }

    private static void collect(List<Node> nodes, Function<String, String> partials, Set<String> out, int inEach, int depth) {
        for (Node n : nodes) {
            if (n instanceof Value v) out.add(path(v.expr()));
            else if (n instanceof Partial p) {
                if (depth >= MAX_DEPTH) continue;
                String body = partials.apply(p.name());
                if (body != null) collect(parse(body), partials, out, inEach, depth + 1);
            } else if (n instanceof Block b) {
                for (String c : condPaths(b.expr())) out.add(c);
                boolean each = "each".equals(b.kind());
                if (each) out.add(path(b.expr()));
                collect(b.body(), partials, out, inEach + (each ? 1 : 0), depth);
                collect(b.orElse(), partials, out, inEach, depth);
            }
        }
    }

    /** The values at those paths (missing ones included as null), for fingerprinting. */
    public static Map<String, Object> pick(Map<String, Object> data, Set<String> paths) {
        Map<String, Object> m = new TreeMap<>();
        for (String p : paths) m.put(p, get(data, p));
        return m;
    }

    // ---------------------------------------------------------------- rendering

    public static Result render(String tpl, Map<String, Object> data, Function<String, String> partials) {
        Set<String> missing = new TreeSet<>();
        StringBuilder sb = new StringBuilder();
        Deque<Object> ctx = new ArrayDeque<>();
        ctx.push(data);
        write(parse(tpl), ctx, new ArrayDeque<>(), partials, sb, missing, 0);
        return new Result(sb.toString(), missing);
    }

    private static void write(List<Node> nodes, Deque<Object> ctx, Deque<Integer> index, Function<String, String> partials, StringBuilder sb, Set<String> missing, int depth) {
        for (Node n : nodes) {
            if (n instanceof Text t) sb.append(t.text());
            else if (n instanceof Value v) {
                Object x = lookup(path(v.expr()), ctx, index);
                if (x == null) { missing.add(path(v.expr())); continue; }
                sb.append(escape(format(x, filters(v.expr()))));
            } else if (n instanceof Partial p) {
                if (depth >= MAX_DEPTH) throw new TemplateError("Clauses include each other too deeply at " + p.name());
                String body = partials.apply(p.name());
                if (body == null) { missing.add("> " + p.name()); continue; }
                write(parse(body), ctx, index, partials, sb, missing, depth + 1);
            } else if (n instanceof Block b) {
                if ("each".equals(b.kind())) {
                    Object x = lookup(path(b.expr()), ctx, index);
                    List<?> items = x instanceof List<?> l ? l : x == null ? List.of() : List.of(x);
                    if (items.isEmpty()) { write(b.orElse(), ctx, index, partials, sb, missing, depth); continue; }
                    for (int i = 0; i < items.size(); i++) {
                        ctx.push(items.get(i));
                        index.push(i);
                        write(b.body(), ctx, index, partials, sb, missing, depth);
                        index.pop();
                        ctx.pop();
                    }
                } else {
                    boolean c = condition(b.expr(), ctx, index);
                    if ("unless".equals(b.kind())) c = !c;
                    write(c ? b.body() : b.orElse(), ctx, index, partials, sb, missing, depth);
                }
            }
        }
    }

    static String[] splitCondition(String expr) {
        Matcher m = Pattern.compile("^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matcher(expr.trim());
        if (m.matches()) return new String[]{m.group(1).trim(), m.group(2), m.group(3).trim()};
        return new String[]{expr.trim(), null, null};
    }

    static boolean isLiteral(String s) {
        return s.startsWith("\"") || s.startsWith("'") || s.matches("-?[0-9.,]+") || s.equals("true") || s.equals("false") || s.equals("null");
    }

    static boolean condition(String expr, Deque<Object> ctx, Deque<Integer> index) {
        String[] c = splitCondition(expr);
        Object left = lookup(c[0], ctx, index);
        if (c[1] == null) return truthy(left);
        Object right = isLiteral(c[2]) ? literal(c[2]) : lookup(c[2], ctx, index);
        Double l = num(left), r = num(right);
        return switch (c[1]) {
            case "==" -> l != null && r != null ? l.equals(r) : Objects.equals(str(left), str(right));
            case "!=" -> l != null && r != null ? !l.equals(r) : !Objects.equals(str(left), str(right));
            case ">" -> l != null && r != null && l > r;
            case "<" -> l != null && r != null && l < r;
            case ">=" -> l != null && r != null && l >= r;
            case "<=" -> l != null && r != null && l <= r;
            default -> false;
        };
    }

    static Object literal(String s) {
        if (s.startsWith("\"") || s.startsWith("'")) return s.substring(1, Math.max(1, s.length() - 1));
        if (s.equals("true") || s.equals("false")) return Boolean.valueOf(s);
        if (s.equals("null")) return null;
        return Double.valueOf(s.replace(",", ""));
    }

    static String str(Object o) { return o == null ? null : String.valueOf(o); }

    static Double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && s.matches("-?[0-9]+(\\.[0-9]+)?")) return Double.valueOf(s);
        return null;
    }

    static boolean truthy(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        if (o instanceof Number n) return n.doubleValue() != 0;
        if (o instanceof Collection<?> c) return !c.isEmpty();
        if (o instanceof Map<?, ?> m) return !m.isEmpty();
        return !String.valueOf(o).isBlank();
    }

    static Object lookup(String path, Deque<Object> ctx, Deque<Integer> index) {
        if (path.equals("@number")) return index.isEmpty() ? null : index.peek() + 1;
        if (path.equals("@index")) return index.peek();
        if (path.equals("this")) return ctx.peek();
        for (Object scope : ctx) {           // innermost first
            Object v = get(scope, path);
            if (v != null) return v;
        }
        return null;
    }

    @SuppressWarnings("rawtypes")
    static Object get(Object scope, String path) {
        Object cur = scope;
        for (String seg : path.split("\\.")) {
            if (cur instanceof Map m) cur = m.get(seg);
            else if (cur instanceof List l && seg.matches("[0-9]+")) { int i = Integer.parseInt(seg); cur = i < l.size() ? l.get(i) : null; }
            else return null;
            if (cur == null) return null;
        }
        return cur;
    }

    // ---------------------------------------------------------------- formats

    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    static String format(Object x, List<String> filters) {
        Object v = x;
        for (String f : filters) {
            v = switch (f) {
                case "rupees" -> num(v) == null ? v : rupees(num(v));
                case "number" -> num(v) == null ? v : indian(Math.round(num(v)));
                case "words" -> num(v) == null ? v : words(Math.round(num(v)));
                case "percent" -> num(v) == null ? v : stripZeros(num(v)) + "%";
                case "date" -> date(v);
                case "upper" -> String.valueOf(v).toUpperCase(Locale.ROOT);
                case "lower" -> String.valueOf(v).toLowerCase(Locale.ROOT);
                default -> v;
            };
        }
        if (v instanceof Double d) return stripZeros(d);
        if (v instanceof BigDecimal b) return b.stripTrailingZeros().toPlainString();
        if (v instanceof Boolean b) return b ? "Yes" : "No";
        if (v instanceof Collection<?> c) return String.join(", ", c.stream().map(String::valueOf).toList());
        return String.valueOf(v);
    }

    static String stripZeros(double d) { return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString(); }

    static String date(Object v) {
        try {
            if (v instanceof TemporalAccessor t) return DATE.format(t instanceof LocalDate ? t : LocalDate.from(t));
            String s = String.valueOf(v);
            return DATE.format(LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s));
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    public static String rupees(double v) {
        long paise = Math.round(v * 100);
        String r = "₹" + indian(paise / 100);
        long p = Math.abs(paise % 100);
        return p == 0 ? r : r + "." + (p < 10 ? "0" : "") + p;
    }

    /** 1234567 -> 12,34,567 */
    public static String indian(long n) {
        String s = String.valueOf(Math.abs(n));
        if (s.length() > 3) {
            String last = s.substring(s.length() - 3), rest = s.substring(0, s.length() - 3);
            StringBuilder b = new StringBuilder();
            while (rest.length() > 2) { b.insert(0, "," + rest.substring(rest.length() - 2)); rest = rest.substring(0, rest.length() - 2); }
            s = rest + b + "," + last;
        }
        return (n < 0 ? "-" : "") + s;
    }

    static final String[] ONES = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve", "Thirteen",
            "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    static final String[] TENS = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    /** Rupees in words, Indian numbering: 250000 -> Rupees Two Lakh Fifty Thousand Only */
    public static String words(long n) {
        if (n == 0) return "Rupees Zero Only";
        StringBuilder b = new StringBuilder();
        long crore = n / 10_000_000, lakh = (n / 100_000) % 100, thousand = (n / 1000) % 100, hundred = (n / 100) % 10, rest = n % 100;
        if (crore > 0) b.append(crore >= 100 ? words(crore).replace("Rupees ", "").replace(" Only", "") : two((int) crore)).append(" Crore ");
        if (lakh > 0) b.append(two((int) lakh)).append(" Lakh ");
        if (thousand > 0) b.append(two((int) thousand)).append(" Thousand ");
        if (hundred > 0) b.append(ONES[(int) hundred]).append(" Hundred ");
        if (rest > 0) b.append(b.length() > 0 ? "and " : "").append(two((int) rest)).append(' ');
        return "Rupees " + b.toString().trim() + " Only";
    }

    static String two(int n) { return n < 20 ? ONES[n] : TENS[n / 10] + (n % 10 > 0 ? " " + ONES[n % 10] : ""); }

    public static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
