package de.jplag.java_cpg.token;

import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_BODY_END;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_DECL_BEGIN;
import static de.jplag.java_cpg.token.CpgTokenType.METHOD_PARAM;
import static de.jplag.java_cpg.token.CpgTokenType.RECORD_DECL_END;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import de.jplag.Language;
import de.jplag.ParsingException;
import de.jplag.Token;
import de.jplag.TokenType;
import de.jplag.java_cpg.JavaCpgLanguage;
import de.jplag.java_cpg.transformation.GraphTransformation;

final class TokenTestSupport {
    private static final Path BASE_PATH = Path.of("src", "test", "resources", "java").toAbsolutePath();

    private TokenTestSupport() {
    }

    static Path resource(String fixture) {
        return BASE_PATH.resolve(fixture);
    }

    static List<Token> parse(String fixture, boolean normalize) throws ParsingException {
        return parse(new JavaCpgLanguage(), List.of(fixture), normalize);
    }

    static List<Token> parseWithTransformations(String fixture, boolean normalize, GraphTransformation... transformations) throws ParsingException {
        return parseWithTransformations(List.of(fixture), normalize, transformations);
    }

    static List<Token> parseWithTransformations(Collection<String> fixtures, boolean normalize, GraphTransformation... transformations)
            throws ParsingException {
        var language = new JavaCpgLanguage();
        language.resetTransformations();
        language.addTransformations(transformations);
        return parse(language, fixtures, normalize);
    }

    private static List<Token> parse(Language language, Collection<String> fixtures, boolean normalize) throws ParsingException {
        return language.parse(fixtures.stream().map(fixture -> resource(fixture).toFile()).collect(Collectors.toSet()), normalize);
    }

    static String sourceOf(Token token) {
        try {
            List<String> lines = Files.readAllLines(token.getFile().toPath()).subList(token.getStartLine() - 1, token.getEndLine());
            lines.set(lines.size() - 1, lines.getLast().substring(0, token.getEndColumn() - 1));
            lines.set(0, lines.getFirst().substring(token.getStartColumn() - 1));
            return String.join("\n", lines);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<Token> tokensInMethod(Collection<Token> tokens, String methodName) {
        Token declaration = uniqueToken(tokens, token -> token.getType() == METHOD_DECL_BEGIN && sourceOf(token).contains(" " + methodName + "("));
        return tokens.stream().dropWhile(token -> token != declaration).skip(1)
                .takeWhile(token -> token.getType() != METHOD_DECL_BEGIN && token.getType() != RECORD_DECL_END)
                .filter(token -> token.getType() != METHOD_PARAM && token.getType() != METHOD_BODY_BEGIN && token.getType() != METHOD_BODY_END)
                .toList();
    }

    static List<TokenType> types(Collection<? extends Token> tokens) {
        return tokens.stream().map(Token::getType).toList();
    }

    static Token uniqueToken(Collection<? extends Token> tokens, TokenType type) {
        return uniqueToken(tokens, token -> token.getType() == type);
    }

    static Token uniqueToken(Collection<? extends Token> tokens, Predicate<? super Token> predicate) {
        var matches = tokens.stream().filter(predicate).toList();
        assertEquals(1, matches.size(), () -> "Expected exactly one matching token, found " + matches);
        return matches.getFirst();
    }
}
