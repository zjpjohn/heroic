package com.spotify.heroic.shell;

import org.junit.Assert;
import org.junit.Test;

public class QuoteParserTest {
    private void test(final Case c) throws QuoteParserException {
        Assert.assertArrayEquals(c.expected, QuoteParser.parse(c.input));
    }

    @Test
    public void testBasic() throws QuoteParserException {
        test(Case.of("\"hello world\"", "hello world"));
        test(Case.of("'hello world'", "hello world"));
        test(Case.of("default  split", "default", "split"));
    }

    @Test
    public void testUnicode() throws QuoteParserException {
        test(Case.of("a \\u0020 b", "a", "\u0020", "b"));
        test(Case.of("a \\u2000 b", "a", "\u2000", "b"));
        test(Case.of("a \\u9999 b", "a", "\u9999", "b"));
    }

    private static class Case {
        private final String input;
        private final String[] expected;

        private Case(String input, String[] expected) {
            this.input = input;
            this.expected = expected;
        }

        public static Case of(String input, String... expected) {
            return new Case(input, expected);
        }
    }
}