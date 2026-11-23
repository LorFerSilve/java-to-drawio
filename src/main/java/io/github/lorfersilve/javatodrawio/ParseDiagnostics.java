package io.github.lorfersilve.javatodrawio;

import com.github.javaparser.GeneratedJavaParserConstants;
import com.github.javaparser.ParseException;
import com.github.javaparser.Position;
import com.github.javaparser.Problem;
import com.github.javaparser.Token;
import com.github.javaparser.TokenRange;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class ParseDiagnostics {
    private static final int CONTEXT_WIDTH = 120;
    private static final Pattern LEXICAL_POSITION = Pattern.compile("^Lexical error at line (\\d+), column (\\d+)\\.");

    static String format(Path file, List<String> lines, Problem problem) {
        if (problem.getCause().orElse(null) instanceof ParseException error && error.currentToken != null
                && error.currentToken.next != null) {
            Token unexpected = error.currentToken.next;
            Token packageName = unterminatedPackageName(unexpected);
            if (packageName != null) {
                return at(file, lines, packageName.endLine, packageName.endColumn + 1,
                        "Parse error: add ';' after the package name.");
            }
            String token = unexpected.kind == GeneratedJavaParserConstants.EOF ? "end of file"
                    : "\"" + displayToken(unexpected.image) + "\"";
            return at(file, lines, unexpected.beginLine, unexpected.beginColumn,
                    "Parse error: unexpected " + token + "." + expectedTokens(error));
        }

        String message = problem.getMessage();
        var lexicalPosition = LEXICAL_POSITION.matcher(message);
        if (lexicalPosition.lookingAt()) {
            return at(file, lines, Integer.parseInt(lexicalPosition.group(1)), Integer.parseInt(lexicalPosition.group(2)),
                    "Lexical error." + message.substring(lexicalPosition.end()));
        }
        return problem.getLocation().flatMap(TokenRange::toRange).map(range -> range.begin)
                .map(position -> at(file, lines, position.line, position.column, message))
                .orElse(file + ": " + message);
    }

    private static Token unterminatedPackageName(Token unexpected) {
        if (unexpected.kind != GeneratedJavaParserConstants.PACKAGE) {
            return null;
        }
        Token name = unexpected.next;
        while (name != null && name.kind == GeneratedJavaParserConstants.IDENTIFIER && name.next != null) {
            switch (name.next.kind) {
                case GeneratedJavaParserConstants.DOT -> name = name.next.next;
                case GeneratedJavaParserConstants.IMPORT, GeneratedJavaParserConstants.CLASS,
                     GeneratedJavaParserConstants.INTERFACE, GeneratedJavaParserConstants.ENUM,
                     GeneratedJavaParserConstants.RECORD, GeneratedJavaParserConstants.PUBLIC,
                     GeneratedJavaParserConstants.ABSTRACT, GeneratedJavaParserConstants.FINAL,
                     GeneratedJavaParserConstants.STRICTFP, GeneratedJavaParserConstants.SEALED,
                     GeneratedJavaParserConstants.NON_SEALED, GeneratedJavaParserConstants.AT,
                     GeneratedJavaParserConstants.EOF -> {
                    return name;
                }
                default -> {
                    return null;
                }
            }
        }
        return null;
    }

    private static String expectedTokens(ParseException error) {
        if (error.expectedTokenSequences == null || error.tokenImage == null) {
            return "";
        }
        Set<String> firstTokens = new TreeSet<>();
        for (int[] sequence : error.expectedTokenSequences) {
            if (sequence.length != 0) {
                firstTokens.add(error.tokenImage[sequence[0]]);
            }
            if (firstTokens.size() > 4) {
                return "";
            }
        }
        return firstTokens.isEmpty() ? "" : " Expected " + String.join(" or ", firstTokens) + ".";
    }

    private static String displayToken(String value) {
        String text = value.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        return text.length() <= 48 ? text : text.substring(0, 45) + "...";
    }

    private static String at(Path file, List<String> lines, int line, int column, String message) {
        if (line < Position.FIRST_LINE || column < Position.FIRST_COLUMN) {
            return file + ": " + message;
        }
        String location = file + ":" + line + ":" + column + ": " + message;
        if (line > lines.size()) {
            return location;
        }
        String source = lines.get(line - 1).replace('\t', ' ');
        int caret = Math.min(column - 1, source.length());
        int start = Math.max(0, Math.min(caret - CONTEXT_WIDTH / 2, source.length() - CONTEXT_WIDTH));
        int end = Math.min(source.length(), start + CONTEXT_WIDTH);
        String prefix = start == 0 ? "" : "...";
        String suffix = end == source.length() ? "" : "...";
        return location + System.lineSeparator() + "  " + prefix + source.substring(start, end) + suffix
                + System.lineSeparator() + "  " + " ".repeat(prefix.length() + caret - start) + "^";
    }

    private ParseDiagnostics() {
    }
}
