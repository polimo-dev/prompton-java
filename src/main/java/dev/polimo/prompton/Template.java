package dev.polimo.prompton;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The prompt template engine: the Liquid subset PromptOn allows, and nothing else.
 *
 * <p>Tags: {@code for} (with {@code else}, {@code break}, {@code continue} and {@code forloop.*}),
 * {@code if}/{@code elsif}/{@code else}, {@code unless}, {@code assign}. Filters: {@code size},
 * {@code join}, {@code default}. Every other construct — {@code include}, {@code capture},
 * {@code case}, {@code raw}, {@code comment}, {@code cycle}, {@code tablerow}, {@code liquid} — is
 * a parse error, and whitespace control ({@code &#123;%-}, {@code -%&#125;}) is rejected by
 * {@link #lint}. Values are substituted raw: there is no HTML escaping.
 *
 * <p>A variable is <em>missing</em> when its key is absent from the variables map. A key present
 * with a {@code null} value is not missing — it renders as the empty string and the {@code default}
 * filter replaces it. Missing variables are an error at output positions, in a {@code for}
 * enumerable, in an {@code unless} condition and as an {@code assign} source; a branch that does
 * not execute is never checked, and neither are variables that appear only inside an {@code if}
 * condition.
 *
 * <p>{@link Engine#RAW} returns the source verbatim without parsing it. It exists for prompts whose
 * text genuinely contains <code>&#123;&#123;</code> or <code>&#123;%</code>.
 */
public final class Template {

    /** Which engine a prompt version was committed with. */
    public enum Engine {
        /** The Liquid subset above. */
        LIQUID("liquid"),
        /** No parsing at all: the source is the output. */
        RAW("raw");

        private final String wireName;

        Engine(String wireName) {
            this.wireName = wireName;
        }

        /** The value stored in the use-case document. */
        public String wireName() {
            return wireName;
        }

        /** Parses a use-case document value; anything but {@code raw} is {@link #LIQUID}. */
        public static Engine from(String value) {
            return value != null && value.trim().equalsIgnoreCase("raw") ? RAW : LIQUID;
        }
    }

    /** One reason {@link #lint} rejected a template. */
    public record LintIssue(Kind kind, String value) {

        /** What sort of problem this is. */
        public enum Kind {
            /** A whitespace-control marker, which PromptOn forbids. */
            WHITESPACE_CONTROL("whitespace_control"),
            /** A tag outside the allowed set. */
            DISALLOWED_TAG("disallowed_tag"),
            /** A filter outside the allowed set. */
            DISALLOWED_FILTER("disallowed_filter"),
            /** The template does not parse. */
            PARSE("parse");

            private final String wireName;

            Kind(String wireName) {
                this.wireName = wireName;
            }

            /** The name the conformance suite uses. */
            public String wireName() {
                return wireName;
            }
        }
    }

    /** The tag names the subset allows, including the block terminators. */
    public static final Set<String> ALLOWED_TAGS = Set.of(
            "for", "endfor", "if", "endif", "elsif", "else", "unless", "endunless", "assign",
            "break", "continue");

    /** The filter names the subset allows. */
    public static final Set<String> ALLOWED_FILTERS = Set.of("size", "join", "default");

    private static final String FORLOOP = "forloop";

    private Template() {}

    // ---------------------------------------------------------------------
    // package implementation API

    /** Renders {@code source} with the Liquid engine. */
    static String render(String source, Map<String, Object> variables) {
        return render(source, variables, Engine.LIQUID);
    }

    /** Renders {@code source}; {@link Engine#RAW} returns it verbatim. */
    static String render(String source, Map<String, Object> variables, Engine engine) {
        if (source == null) {
            return "";
        }
        if (engine == Engine.RAW) {
            return source;
        }
        List<Node> nodes = parse(source);
        Scope scope = new Scope(variables);
        StringBuilder out = new StringBuilder();
        renderNodes(nodes, scope, out);
        return out.toString();
    }

    /** Renders the {@code content} of every message, keeping the other fields as they are. */
    static List<Message> renderMessages(
            List<Message> messages, Map<String, Object> variables, Engine engine) {
        List<Message> rendered = new ArrayList<>(messages.size());
        for (Message message : messages) {
            rendered.add(message.withContent(render(message.content(), variables, engine)));
        }
        return rendered;
    }

    /**
     * Checks a template against the allowed subset. An empty list means it is fine.
     *
     * <p>The server runs the same check when a prompt version is committed, so a template that
     * fails lint can never reach a use-case document.
     */
    public static List<LintIssue> lint(String source) {
        List<LintIssue> issues = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Token> tokens;
        try {
            tokens = tokenize(source);
        } catch (TemplateException e) {
            issues.add(new LintIssue(LintIssue.Kind.PARSE, e.getMessage()));
            return issues;
        }

        for (Token token : tokens) {
            if (token.type == TokenType.TEXT) {
                continue;
            }
            boolean tag = token.type == TokenType.TAG;
            if (token.leftTrim && seen.add(tag ? "{%-" : "{{-")) {
                issues.add(new LintIssue(LintIssue.Kind.WHITESPACE_CONTROL, tag ? "{%-" : "{{-"));
            }
            if (token.rightTrim && seen.add(tag ? "-%}" : "-}}")) {
                issues.add(new LintIssue(LintIssue.Kind.WHITESPACE_CONTROL, tag ? "-%}" : "-}}"));
            }
        }

        List<LintIssue> tagIssues = new ArrayList<>();
        Set<String> badTags = new LinkedHashSet<>();
        for (Token token : tokens) {
            if (token.type == TokenType.TAG && !ALLOWED_TAGS.contains(token.tagName)
                    && badTags.add(token.tagName)) {
                tagIssues.add(new LintIssue(LintIssue.Kind.DISALLOWED_TAG, token.tagName));
            }
        }
        if (!tagIssues.isEmpty()) {
            issues.addAll(tagIssues);
            return issues;
        }

        List<Node> nodes;
        try {
            nodes = new Parser(tokens).parseTemplate();
        } catch (TemplateException e) {
            issues.add(new LintIssue(LintIssue.Kind.PARSE, e.getMessage()));
            return issues;
        }

        Set<String> badFilters = new LinkedHashSet<>();
        collectFilters(nodes, badFilters);
        for (String filter : badFilters) {
            if (!ALLOWED_FILTERS.contains(filter)) {
                issues.add(new LintIssue(LintIssue.Kind.DISALLOWED_FILTER, filter));
            }
        }
        return issues;
    }

    /**
     * The top-level input variables a template reads, sorted and deduplicated. Loop variables,
     * {@code assign} targets and {@code forloop} are excluded.
     */
    public static List<String> variables(String source) {
        Set<String> referenced = new LinkedHashSet<>();
        Set<String> bound = new LinkedHashSet<>();
        try {
            List<Node> nodes = new Parser(tokenize(source)).parseTemplate();
            collectVariables(nodes, referenced, bound);
        } catch (TemplateException e) {
            scrapeVariables(source, referenced, bound);
        }
        Set<String> out = new TreeSet<>(referenced);
        out.removeAll(bound);
        out.remove(FORLOOP);
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------------
    // tokenizer

    private enum TokenType { TEXT, OUTPUT, TAG }

    private static final class Token {
        final TokenType type;
        String text;
        final String tagName;
        final String body;
        final boolean leftTrim;
        final boolean rightTrim;

        Token(TokenType type, String text, String tagName, String body, boolean left, boolean right) {
            this.type = type;
            this.text = text;
            this.tagName = tagName;
            this.body = body;
            this.leftTrim = left;
            this.rightTrim = right;
        }
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int length = source.length();
        StringBuilder text = new StringBuilder();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '{' && i + 1 < length && (source.charAt(i + 1) == '{' || source.charAt(i + 1) == '%')) {
                boolean output = source.charAt(i + 1) == '{';
                String close = output ? "}}" : "%}";
                int bodyStart = i + 2;
                boolean leftTrim = bodyStart < length && source.charAt(bodyStart) == '-';
                if (leftTrim) {
                    bodyStart++;
                }
                int end = source.indexOf(close, bodyStart);
                if (end < 0) {
                    // An unterminated block is literal text, which is how Liquid treats it.
                    text.append(source, i, length);
                    i = length;
                    break;
                }
                boolean rightTrim = end > bodyStart && source.charAt(end - 1) == '-';
                String body = source.substring(bodyStart, rightTrim ? end - 1 : end).trim();
                tokens.add(new Token(TokenType.TEXT, text.toString(), null, null, false, false));
                text.setLength(0);
                if (output) {
                    tokens.add(new Token(TokenType.OUTPUT, null, null, body, leftTrim, rightTrim));
                } else {
                    int space = 0;
                    while (space < body.length() && !Character.isWhitespace(body.charAt(space))) {
                        space++;
                    }
                    String name = body.substring(0, space);
                    String rest = body.substring(space).trim();
                    tokens.add(new Token(TokenType.TAG, null, name, rest, leftTrim, rightTrim));
                }
                i = end + 2;
            } else {
                text.append(c);
                i++;
            }
        }
        tokens.add(new Token(TokenType.TEXT, text.toString(), null, null, false, false));
        applyWhitespaceControl(tokens);
        return tokens;
    }

    private static void applyWhitespaceControl(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.type == TokenType.TEXT) {
                continue;
            }
            if (token.leftTrim && i > 0 && tokens.get(i - 1).type == TokenType.TEXT) {
                Token before = tokens.get(i - 1);
                before.text = stripTrailing(before.text);
            }
            if (token.rightTrim && i + 1 < tokens.size()
                    && tokens.get(i + 1).type == TokenType.TEXT) {
                Token after = tokens.get(i + 1);
                after.text = stripLeading(after.text);
            }
        }
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }

    private static String stripLeading(String s) {
        int start = 0;
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) {
            start++;
        }
        return s.substring(start);
    }

    // ---------------------------------------------------------------------
    // AST

    private interface Node {}

    private record TextNode(String text) implements Node {}

    private record OutputNode(ValueExpr expr) implements Node {}

    private record AssignNode(String target, ValueExpr expr) implements Node {}

    private record BreakNode() implements Node {}

    private record ContinueNode() implements Node {}

    private record ForNode(String variable, ValueExpr enumerable, List<Node> body, List<Node> orElse)
            implements Node {}

    private record Branch(Condition condition, List<Node> body) {}

    private record IfNode(List<Branch> branches, List<Node> orElse) implements Node {}

    private record UnlessNode(Condition condition, List<Node> body, List<Node> orElse)
            implements Node {}

    /** A value: a literal or a variable path, with a chain of filters applied. */
    private record ValueExpr(Object literal, Path path, List<FilterCall> filters) {}

    private record FilterCall(String name, List<ValueExpr> args) {}

    private record Path(String source, List<Object> segments) {}

    private record Comparison(ValueExpr left, String op, ValueExpr right) {}

    private record Condition(List<Comparison> terms, List<String> operators) {}

    // ---------------------------------------------------------------------
    // parser

    private static List<Node> parse(String source) {
        return new Parser(tokenize(source)).parseTemplate();
    }

    private static final class Parser {
        private final List<Token> tokens;
        private int index;

        Parser(List<Token> tokens) {
            this.tokens = tokens;
        }

        List<Node> parseTemplate() {
            List<Node> nodes = parseBlock(Set.of(), false);
            if (index < tokens.size()) {
                Token token = tokens.get(index);
                throw TemplateException.parseError("Unexpected tag '" + token.tagName + "'");
            }
            return nodes;
        }

        /**
         * Parses until one of {@code terminators} (which is left unconsumed).
         *
         * <p>{@code blockBody} reproduces a quirk of the reference implementation that the
         * conformance suite pins down: a block body made of nothing but one whitespace-only text
         * run renders as empty, while the same whitespace at the top level, or next to anything
         * else, is kept.
         */
        private List<Node> parseBlock(Set<String> terminators, boolean blockBody) {
            List<Node> nodes = collectBlock(terminators);
            if (blockBody && nodes.size() == 1 && nodes.get(0) instanceof TextNode text
                    && text.text().isBlank()) {
                return List.of();
            }
            return nodes;
        }

        private List<Node> collectBlock(Set<String> terminators) {
            List<Node> nodes = new ArrayList<>();
            while (index < tokens.size()) {
                Token token = tokens.get(index);
                if (token.type == TokenType.TEXT) {
                    index++;
                    if (!token.text.isEmpty()) {
                        nodes.add(new TextNode(token.text));
                    }
                    continue;
                }
                if (token.type == TokenType.OUTPUT) {
                    index++;
                    nodes.add(new OutputNode(new ExprParser(token.body).parseValue()));
                    continue;
                }
                if (terminators.contains(token.tagName)) {
                    return nodes;
                }
                index++;
                nodes.add(parseTag(token));
            }
            return nodes;
        }

        private Node parseTag(Token token) {
            return switch (token.tagName) {
                case "assign" -> parseAssign(token.body);
                case "break" -> new BreakNode();
                case "continue" -> new ContinueNode();
                case "if" -> parseIf(token.body);
                case "unless" -> parseUnless(token.body);
                case "for" -> parseFor(token.body);
                default -> throw TemplateException.parseError("Unexpected tag '" + token.tagName + "'");
            };
        }

        private Node parseAssign(String body) {
            int eq = body.indexOf('=');
            if (eq < 0) {
                throw TemplateException.parseError("Expected '=' in assign");
            }
            String target = body.substring(0, eq).trim();
            if (target.isEmpty()) {
                throw TemplateException.parseError("Expected a name in assign");
            }
            return new AssignNode(target, new ExprParser(body.substring(eq + 1)).parseValue());
        }

        private Node parseIf(String body) {
            List<Branch> branches = new ArrayList<>();
            branches.add(new Branch(
                    new ExprParser(body).parseCondition(),
                    parseBlock(Set.of("elsif", "else", "endif"), true)));
            List<Node> orElse = List.of();
            while (index < tokens.size()) {
                Token token = tokens.get(index);
                index++;
                switch (token.tagName) {
                    case "elsif" -> branches.add(new Branch(
                            new ExprParser(token.body).parseCondition(),
                            parseBlock(Set.of("elsif", "else", "endif"), true)));
                    case "else" -> orElse = parseBlock(Set.of("endif"), true);
                    case "endif" -> {
                        return new IfNode(branches, orElse);
                    }
                    default -> throw TemplateException.parseError("Expected 'endif'");
                }
            }
            throw TemplateException.parseError("Expected 'endif'");
        }

        private Node parseUnless(String body) {
            Condition condition = new ExprParser(body).parseCondition();
            List<Node> nodes = parseBlock(Set.of("else", "endunless"), true);
            List<Node> orElse = List.of();
            while (index < tokens.size()) {
                Token token = tokens.get(index);
                index++;
                switch (token.tagName) {
                    case "else" -> orElse = parseBlock(Set.of("endunless"), true);
                    case "endunless" -> {
                        return new UnlessNode(condition, nodes, orElse);
                    }
                    default -> throw TemplateException.parseError("Expected 'endunless'");
                }
            }
            throw TemplateException.parseError("Expected 'endunless'");
        }

        private Node parseFor(String body) {
            int in = indexOfWord(body, "in");
            if (in < 0) {
                throw TemplateException.parseError("Expected 'in' in for");
            }
            String variable = body.substring(0, in).trim();
            ValueExpr enumerable = new ExprParser(body.substring(in + 2)).parseValue();
            List<Node> nodes = parseBlock(Set.of("else", "endfor"), true);
            List<Node> orElse = List.of();
            while (index < tokens.size()) {
                Token token = tokens.get(index);
                index++;
                switch (token.tagName) {
                    case "else" -> orElse = parseBlock(Set.of("endfor"), true);
                    case "endfor" -> {
                        return new ForNode(variable, enumerable, nodes, orElse);
                    }
                    default -> throw TemplateException.parseError("Expected 'endfor'");
                }
            }
            throw TemplateException.parseError("Expected 'endfor'");
        }

        private static int indexOfWord(String body, String word) {
            int from = 0;
            while (true) {
                int at = body.indexOf(word, from);
                if (at < 0) {
                    return -1;
                }
                boolean leftOk = at > 0 && Character.isWhitespace(body.charAt(at - 1));
                int after = at + word.length();
                boolean rightOk = after < body.length() && Character.isWhitespace(body.charAt(after));
                if (leftOk && rightOk) {
                    return at;
                }
                from = at + 1;
            }
        }
    }

    // ---------------------------------------------------------------------
    // expression parser

    private static final Object NIL = new Object();

    private static final class ExprParser {
        private final String src;
        private int pos;

        ExprParser(String src) {
            this.src = src == null ? "" : src;
        }

        ValueExpr parseValue() {
            ValueExpr expr = parsePrimaryWithFilters();
            skipSpace();
            if (pos < src.length()) {
                throw TemplateException.parseError("Unexpected '" + src.substring(pos).trim() + "'");
            }
            return expr;
        }

        Condition parseCondition() {
            List<Comparison> terms = new ArrayList<>();
            List<String> operators = new ArrayList<>();
            terms.add(parseComparison());
            while (true) {
                skipSpace();
                String word = peekWord();
                if (word.equals("and") || word.equals("or")) {
                    pos += word.length();
                    operators.add(word);
                    terms.add(parseComparison());
                } else {
                    break;
                }
            }
            skipSpace();
            if (pos < src.length()) {
                throw TemplateException.parseError("Unexpected '" + src.substring(pos).trim() + "'");
            }
            return new Condition(terms, operators);
        }

        private Comparison parseComparison() {
            ValueExpr left = parsePrimaryWithFilters();
            skipSpace();
            String op = peekOperator();
            if (op == null) {
                return new Comparison(left, null, null);
            }
            pos += op.length();
            ValueExpr right = parsePrimaryWithFilters();
            return new Comparison(left, op, right);
        }

        private String peekOperator() {
            skipSpace();
            for (String op : new String[] {"==", "!=", ">=", "<=", "<>", ">", "<"}) {
                if (src.startsWith(op, pos)) {
                    return op.equals("<>") ? "!=" : op;
                }
            }
            String word = peekWord();
            return word.equals("contains") ? "contains" : null;
        }

        private ValueExpr parsePrimaryWithFilters() {
            skipSpace();
            Object literal = null;
            Path path = null;
            if (pos >= src.length()) {
                throw TemplateException.parseError("Expected an expression");
            }
            char c = src.charAt(pos);
            if (c == '"' || c == '\'') {
                literal = readString();
            } else if (Character.isDigit(c) || (c == '-' && pos + 1 < src.length()
                    && Character.isDigit(src.charAt(pos + 1)))) {
                literal = readNumber();
            } else {
                String word = peekWord();
                if (word.equals("true")) {
                    pos += 4;
                    literal = Boolean.TRUE;
                } else if (word.equals("false")) {
                    pos += 5;
                    literal = Boolean.FALSE;
                } else if (word.equals("nil") || word.equals("null")) {
                    pos += word.length();
                    literal = NIL;
                } else if (word.equals("empty") || word.equals("blank")) {
                    pos += word.length();
                    literal = "";
                } else {
                    path = readPath();
                }
            }
            List<FilterCall> filters = readFilters();
            return new ValueExpr(literal, path, filters);
        }

        private List<FilterCall> readFilters() {
            List<FilterCall> filters = new ArrayList<>();
            while (true) {
                skipSpace();
                if (pos >= src.length() || src.charAt(pos) != '|') {
                    return filters;
                }
                pos++;
                skipSpace();
                String name = readIdentifier();
                if (name.isEmpty()) {
                    throw TemplateException.parseError("Expected a filter name");
                }
                List<ValueExpr> args = new ArrayList<>();
                skipSpace();
                if (pos < src.length() && src.charAt(pos) == ':') {
                    pos++;
                    while (true) {
                        args.add(parseArgument());
                        skipSpace();
                        if (pos < src.length() && src.charAt(pos) == ',') {
                            pos++;
                        } else {
                            break;
                        }
                    }
                }
                filters.add(new FilterCall(name, args));
            }
        }

        private ValueExpr parseArgument() {
            skipSpace();
            if (pos >= src.length()) {
                throw TemplateException.parseError("Expected a filter argument");
            }
            char c = src.charAt(pos);
            if (c == '"' || c == '\'') {
                return new ValueExpr(readString(), null, List.of());
            }
            if (Character.isDigit(c) || (c == '-' && pos + 1 < src.length()
                    && Character.isDigit(src.charAt(pos + 1)))) {
                return new ValueExpr(readNumber(), null, List.of());
            }
            String word = peekWord();
            if (word.equals("true")) {
                pos += 4;
                return new ValueExpr(Boolean.TRUE, null, List.of());
            }
            if (word.equals("false")) {
                pos += 5;
                return new ValueExpr(Boolean.FALSE, null, List.of());
            }
            if (word.equals("nil") || word.equals("null")) {
                pos += word.length();
                return new ValueExpr(NIL, null, List.of());
            }
            return new ValueExpr(null, readPath(), List.of());
        }

        private Path readPath() {
            int start = pos;
            List<Object> segments = new ArrayList<>();
            String first = readIdentifier();
            if (first.isEmpty()) {
                throw TemplateException.parseError(
                        "Expected a variable at '" + src.substring(pos).trim() + "'");
            }
            segments.add(first);
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == '.') {
                    pos++;
                    String name = readIdentifier();
                    if (name.isEmpty()) {
                        throw TemplateException.parseError("Expected a name after '.'");
                    }
                    segments.add(name);
                } else if (c == '[') {
                    pos++;
                    skipSpace();
                    Object key;
                    char open = pos < src.length() ? src.charAt(pos) : ' ';
                    if (open == '"' || open == '\'') {
                        key = readString();
                    } else {
                        key = readNumber();
                    }
                    skipSpace();
                    if (pos >= src.length() || src.charAt(pos) != ']') {
                        throw TemplateException.parseError("Expected ']'");
                    }
                    pos++;
                    segments.add(key);
                } else {
                    break;
                }
            }
            return new Path(src.substring(start, pos).trim(), segments);
        }

        private String readIdentifier() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            return src.substring(start, pos);
        }

        private String peekWord() {
            int save = pos;
            String word = readIdentifier();
            pos = save;
            return word;
        }

        private String readString() {
            char quote = src.charAt(pos);
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < src.length() && src.charAt(pos) != quote) {
                char c = src.charAt(pos);
                if (c == '\\' && pos + 1 < src.length()) {
                    pos++;
                    out.append(src.charAt(pos));
                } else {
                    out.append(c);
                }
                pos++;
            }
            if (pos >= src.length()) {
                throw TemplateException.parseError("Unterminated string literal");
            }
            pos++;
            return out.toString();
        }

        private Object readNumber() {
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') {
                pos++;
            }
            boolean decimal = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !decimal && pos + 1 < src.length()
                        && Character.isDigit(src.charAt(pos + 1))) {
                    decimal = true;
                    pos++;
                } else {
                    break;
                }
            }
            String text = src.substring(start, pos);
            if (text.isEmpty() || text.equals("-")) {
                throw TemplateException.parseError("Expected a number");
            }
            return decimal ? (Object) Double.valueOf(text) : (Object) Long.valueOf(text);
        }

        private void skipSpace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }

    // ---------------------------------------------------------------------
    // rendering

    private static final class Scope {
        private final Map<String, Object> values = new LinkedHashMap<>();

        Scope(Map<String, Object> variables) {
            if (variables != null) {
                values.putAll(variables);
            }
        }

        boolean defined(String name) {
            return values.containsKey(name);
        }

        Object get(String name) {
            return values.get(name);
        }

        void put(String name, Object value) {
            values.put(name, value);
        }

        Object remove(String name) {
            return values.remove(name);
        }

        boolean has(String name) {
            return values.containsKey(name);
        }
    }

    private enum Flow { NORMAL, BREAK, CONTINUE }

    private static Flow renderNodes(List<Node> nodes, Scope scope, StringBuilder out) {
        for (Node node : nodes) {
            Flow flow = renderNode(node, scope, out);
            if (flow != Flow.NORMAL) {
                return flow;
            }
        }
        return Flow.NORMAL;
    }

    private static Flow renderNode(Node node, Scope scope, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(text.text());
            return Flow.NORMAL;
        }
        if (node instanceof OutputNode output) {
            out.append(toDisplayString(evaluate(output.expr(), scope, true)));
            return Flow.NORMAL;
        }
        if (node instanceof AssignNode assign) {
            scope.put(assign.target(), evaluate(assign.expr(), scope, true));
            return Flow.NORMAL;
        }
        if (node instanceof BreakNode) {
            return Flow.BREAK;
        }
        if (node instanceof ContinueNode) {
            return Flow.CONTINUE;
        }
        if (node instanceof IfNode ifNode) {
            for (Branch branch : ifNode.branches()) {
                if (evaluateCondition(branch.condition(), scope, false)) {
                    return renderNodes(branch.body(), scope, out);
                }
            }
            return renderNodes(ifNode.orElse(), scope, out);
        }
        if (node instanceof UnlessNode unless) {
            if (evaluateCondition(unless.condition(), scope, true)) {
                return renderNodes(unless.orElse(), scope, out);
            }
            return renderNodes(unless.body(), scope, out);
        }
        if (node instanceof ForNode forNode) {
            return renderFor(forNode, scope, out);
        }
        throw TemplateException.renderError("unsupported node " + node);
    }

    private static Flow renderFor(ForNode node, Scope scope, StringBuilder out) {
        Object enumerable = evaluate(node.enumerable(), scope, true);
        List<Object> items = toIterable(enumerable);
        if (items.isEmpty()) {
            return renderNodes(node.orElse(), scope, out);
        }
        boolean hadVariable = scope.has(node.variable());
        Object previous = scope.get(node.variable());
        boolean hadForloop = scope.has(FORLOOP);
        Object previousForloop = scope.get(FORLOOP);
        try {
            int size = items.size();
            for (int i = 0; i < size; i++) {
                scope.put(node.variable(), items.get(i));
                scope.put(FORLOOP, forloop(i, size));
                Flow flow = renderNodes(node.body(), scope, out);
                if (flow == Flow.BREAK) {
                    break;
                }
            }
        } finally {
            restore(scope, node.variable(), hadVariable, previous);
            restore(scope, FORLOOP, hadForloop, previousForloop);
        }
        return Flow.NORMAL;
    }

    private static void restore(Scope scope, String name, boolean had, Object previous) {
        if (had) {
            scope.put(name, previous);
        } else {
            scope.remove(name);
        }
    }

    private static Map<String, Object> forloop(int index, int size) {
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("index", (long) index + 1);
        loop.put("index0", (long) index);
        loop.put("rindex", (long) size - index);
        loop.put("rindex0", (long) size - index - 1);
        loop.put("first", index == 0);
        loop.put("last", index == size - 1);
        loop.put("length", (long) size);
        return loop;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> toIterable(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        if (value instanceof Map<?, ?> map) {
            List<Object> pairs = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                pairs.add(java.util.Arrays.asList(
                        String.valueOf(entry.getKey()), entry.getValue()));
            }
            return pairs;
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        return List.of(value);
    }

    private static boolean evaluateCondition(Condition condition, Scope scope, boolean strict) {
        return evaluateCondition(condition, scope, strict, 0);
    }

    // Liquid has no operator precedence: `a and b or c` binds right to left.
    private static boolean evaluateCondition(Condition condition, Scope scope, boolean strict, int i) {
        boolean value = evaluateComparison(condition.terms().get(i), scope, strict);
        if (i >= condition.operators().size()) {
            return value;
        }
        String op = condition.operators().get(i);
        if (op.equals("and")) {
            return value && evaluateCondition(condition, scope, strict, i + 1);
        }
        return value || evaluateCondition(condition, scope, strict, i + 1);
    }

    private static boolean evaluateComparison(Comparison term, Scope scope, boolean strict) {
        Object left = evaluate(term.left(), scope, strict);
        if (term.op() == null) {
            return truthy(left);
        }
        Object right = evaluate(term.right(), scope, strict);
        return switch (term.op()) {
            case "==" -> equalValues(left, right);
            case "!=" -> !equalValues(left, right);
            case "contains" -> contains(left, right);
            default -> compareOrdered(left, right, term.op());
        };
    }

    private static boolean compareOrdered(Object left, Object right, String op) {
        Integer cmp = compare(left, right);
        if (cmp == null) {
            return false;
        }
        return switch (op) {
            case ">" -> cmp > 0;
            case "<" -> cmp < 0;
            case ">=" -> cmp >= 0;
            case "<=" -> cmp <= 0;
            default -> false;
        };
    }

    private static Integer compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        if (left instanceof String a && right instanceof String b) {
            return a.compareTo(b);
        }
        return null;
    }

    private static boolean equalValues(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return a.doubleValue() == b.doubleValue();
        }
        return left.equals(right);
    }

    private static boolean contains(Object left, Object right) {
        if (left instanceof String a) {
            return a.contains(toDisplayString(right));
        }
        if (left instanceof List<?> list) {
            for (Object item : list) {
                if (equalValues(item, right)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return true;
    }

    private static Object evaluate(ValueExpr expr, Scope scope, boolean strict) {
        Object value;
        if (expr.path() != null) {
            value = lookup(expr.path(), scope, strict);
        } else {
            value = expr.literal() == NIL ? null : expr.literal();
        }
        for (FilterCall filter : expr.filters()) {
            List<Object> args = new ArrayList<>(filter.args().size());
            for (ValueExpr arg : filter.args()) {
                args.add(evaluate(arg, scope, false));
            }
            value = applyFilter(filter.name(), value, args);
        }
        return value;
    }

    private static Object lookup(Path path, Scope scope, boolean strict) {
        String root = String.valueOf(path.segments().get(0));
        if (!scope.defined(root)) {
            if (strict) {
                throw TemplateException.missingVariable(path.source());
            }
            return null;
        }
        Object current = scope.get(root);
        for (int i = 1; i < path.segments().size(); i++) {
            Object segment = path.segments().get(i);
            current = step(current, segment, path, strict);
            if (current == null && i < path.segments().size() - 1) {
                if (strict) {
                    throw TemplateException.missingVariable(path.source());
                }
                return null;
            }
        }
        return current;
    }

    private static Object step(Object current, Object segment, Path path, boolean strict) {
        if (current instanceof Map<?, ?> map) {
            String key = String.valueOf(segment);
            if (!map.containsKey(key)) {
                if (strict) {
                    throw TemplateException.missingVariable(path.source());
                }
                return null;
            }
            return map.get(key);
        }
        if (current instanceof List<?> list) {
            if (segment instanceof Number n) {
                int index = n.intValue();
                if (index < 0) {
                    index += list.size();
                }
                return index >= 0 && index < list.size() ? list.get(index) : null;
            }
            return switch (String.valueOf(segment)) {
                case "size" -> (long) list.size();
                case "first" -> list.isEmpty() ? null : list.get(0);
                case "last" -> list.isEmpty() ? null : list.get(list.size() - 1);
                default -> null;
            };
        }
        if (current instanceof String s && "size".equals(String.valueOf(segment))) {
            return (long) s.codePointCount(0, s.length());
        }
        if (current == null) {
            if (strict) {
                throw TemplateException.missingVariable(path.source());
            }
            return null;
        }
        return null;
    }

    private static Object applyFilter(String name, Object value, List<Object> args) {
        return switch (name) {
            case "size" -> sizeOf(value);
            case "join" -> joinOf(value, args.isEmpty() ? " " : toDisplayString(args.get(0)));
            case "default" -> isBlank(value) ? (args.isEmpty() ? null : args.get(0)) : value;
            default -> throw TemplateException.renderError(
                    "filter '" + name + "' is not part of the PromptOn template subset");
        };
    }

    private static boolean isBlank(Object value) {
        return value == null || Boolean.FALSE.equals(value) || "".equals(value)
                || (value instanceof List<?> list && list.isEmpty());
    }

    private static Object sizeOf(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof String s) {
            return (long) s.codePointCount(0, s.length());
        }
        if (value instanceof List<?> list) {
            return (long) list.size();
        }
        if (value instanceof Map<?, ?> map) {
            return (long) map.size();
        }
        return 0L;
    }

    private static String joinOf(Object value, String separator) {
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(separator);
                }
                out.append(toDisplayString(list.get(i)));
            }
            return out.toString();
        }
        return toDisplayString(value);
    }

    /** How a value appears when substituted into a template. */
    static String toDisplayString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        if (value instanceof Double || value instanceof Float || value instanceof BigDecimal) {
            BigDecimal decimal = value instanceof BigDecimal b
                    ? b
                    : BigDecimal.valueOf(((Number) value).doubleValue());
            decimal = decimal.stripTrailingZeros();
            if (decimal.scale() <= 0) {
                decimal = decimal.setScale(1);
            }
            return decimal.toPlainString();
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder();
            for (Object item : list) {
                out.append(toDisplayString(item));
            }
            return out.toString();
        }
        return String.valueOf(value);
    }

    // ---------------------------------------------------------------------
    // AST walking for lint and variables

    private static void collectFilters(List<Node> nodes, Set<String> out) {
        for (Node node : nodes) {
            if (node instanceof OutputNode output) {
                collectFilters(output.expr(), out);
            } else if (node instanceof AssignNode assign) {
                collectFilters(assign.expr(), out);
            } else if (node instanceof IfNode ifNode) {
                for (Branch branch : ifNode.branches()) {
                    collectFilters(branch.condition(), out);
                    collectFilters(branch.body(), out);
                }
                collectFilters(ifNode.orElse(), out);
            } else if (node instanceof UnlessNode unless) {
                collectFilters(unless.condition(), out);
                collectFilters(unless.body(), out);
                collectFilters(unless.orElse(), out);
            } else if (node instanceof ForNode forNode) {
                collectFilters(forNode.enumerable(), out);
                collectFilters(forNode.body(), out);
                collectFilters(forNode.orElse(), out);
            }
        }
    }

    private static void collectFilters(Condition condition, Set<String> out) {
        for (Comparison term : condition.terms()) {
            collectFilters(term.left(), out);
            if (term.right() != null) {
                collectFilters(term.right(), out);
            }
        }
    }

    private static void collectFilters(ValueExpr expr, Set<String> out) {
        for (FilterCall filter : expr.filters()) {
            out.add(filter.name());
            for (ValueExpr arg : filter.args()) {
                collectFilters(arg, out);
            }
        }
    }

    private static void collectVariables(List<Node> nodes, Set<String> refs, Set<String> bound) {
        for (Node node : nodes) {
            if (node instanceof OutputNode output) {
                collectVariables(output.expr(), refs);
            } else if (node instanceof AssignNode assign) {
                bound.add(assign.target());
                collectVariables(assign.expr(), refs);
            } else if (node instanceof IfNode ifNode) {
                for (Branch branch : ifNode.branches()) {
                    collectVariables(branch.condition(), refs);
                    collectVariables(branch.body(), refs, bound);
                }
                collectVariables(ifNode.orElse(), refs, bound);
            } else if (node instanceof UnlessNode unless) {
                collectVariables(unless.condition(), refs);
                collectVariables(unless.body(), refs, bound);
                collectVariables(unless.orElse(), refs, bound);
            } else if (node instanceof ForNode forNode) {
                bound.add(forNode.variable());
                collectVariables(forNode.enumerable(), refs);
                collectVariables(forNode.body(), refs, bound);
                collectVariables(forNode.orElse(), refs, bound);
            }
        }
    }

    private static void collectVariables(Condition condition, Set<String> refs) {
        for (Comparison term : condition.terms()) {
            collectVariables(term.left(), refs);
            if (term.right() != null) {
                collectVariables(term.right(), refs);
            }
        }
    }

    private static void collectVariables(ValueExpr expr, Set<String> refs) {
        if (expr.path() != null) {
            refs.add(String.valueOf(expr.path().segments().get(0)));
        }
        for (FilterCall filter : expr.filters()) {
            for (ValueExpr arg : filter.args()) {
                collectVariables(arg, refs);
            }
        }
    }

    private static final Set<String> KEYWORDS =
            Set.of("true", "false", "nil", "null", "empty", "blank", "and", "or", "contains", "in");

    /** Best-effort variable scrape for a template that does not parse. */
    private static void scrapeVariables(String source, Set<String> refs, Set<String> bound) {
        java.util.regex.Matcher output = java.util.regex.Pattern
                .compile("\\{\\{-?\\s*([A-Za-z_][A-Za-z0-9_]*)")
                .matcher(source);
        while (output.find()) {
            refs.add(output.group(1));
        }
        java.util.regex.Matcher tags = java.util.regex.Pattern
                .compile("\\{%-?\\s*(?:if|unless|elsif)\\s+([A-Za-z_][A-Za-z0-9_]*)"
                        + "|\\{%-?\\s*for\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+in\\s+([A-Za-z_][A-Za-z0-9_]*)"
                        + "|\\{%-?\\s*assign\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)?")
                .matcher(source);
        while (tags.find()) {
            if (tags.group(1) != null) {
                refs.add(tags.group(1));
            }
            if (tags.group(2) != null) {
                bound.add(tags.group(2));
                refs.add(tags.group(3));
            }
            if (tags.group(4) != null) {
                bound.add(tags.group(4));
                if (tags.group(5) != null) {
                    refs.add(tags.group(5));
                }
            }
        }
        refs.removeAll(KEYWORDS);
        refs.removeIf(name -> name.toLowerCase(Locale.ROOT).equals(FORLOOP));
    }
}
