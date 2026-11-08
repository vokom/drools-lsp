/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.drools.lsp.parser;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.TerminalNodeImpl;
import org.drools.drl.parser.antlr4.DRL10Lexer;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.drools.drl.parser.antlr4.DRL10ParserHelper;
import org.drools.drl.parser.antlr4.JavaLexer;
import org.drools.drl.parser.antlr4.JavaParser;
import org.drools.drl.parser.antlr4.ParserHelper;
import org.kie.internal.builder.conf.LanguageLevelOption;

/**
 * Editor parser compatibility for published Drools 10.2.0.
 *
 * <p>Its DRL expression grammar imports Java's lambda rule, whose body then
 * resolves to the older DRL {@code expression} rule rather than Java's. Valid
 * Java lambdas in functions can consequently fail parsing, or make an earlier
 * annotated rule swallow the function without reporting an error.
 *
 * <p>Only function bodies containing lambdas, with both a valid DRL signature
 * and a fully valid Java block, are treated as opaque blocks by DRL lookahead.
 * The original tokens, channels, positions and text remain available, including
 * to formatter content/comment guards. Body terminals are restored to the tree;
 * the formatter already emits function bodies verbatim. Invalid bodies take the
 * normal DRL error-recovery path. No generated parser classes are replaced.
 */
public final class DrlParserFactory {

    private DrlParserFactory() {
    }

    public static DRL10Parser createDrlParser(String text) {
        FunctionTokenStream tokens = new FunctionTokenStream(new DRL10Lexer(CharStreams.fromString(text)));
        DRL10Parser parser = new DRL10Parser(tokens);
        parser.setHelper(new ParserHelper(tokens, LanguageLevelOption.DRL10));
        parser.addParseListener(new ParseTreeListener() {
            @Override public void visitTerminal(TerminalNode node) { }
            @Override public void visitErrorNode(ErrorNode node) { }
            @Override public void enterEveryRule(ParserRuleContext ctx) { }
            @Override public void exitEveryRule(ParserRuleContext ctx) {
                if (ctx instanceof DRL10Parser.DrlBlockContext && tokens.isOpaqueBlock(ctx)) {
                    // Keep the braces the parser read, restoring the source between them.
                    var close = ctx.children.remove(ctx.children.size() - 1);
                    for (int i = ctx.start.getTokenIndex() + 1; i < ctx.stop.getTokenIndex(); i++) {
                        Token token = tokens.get(i);
                        if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                            ctx.addChild(new TerminalNodeImpl(token));
                        }
                    }
                    ctx.addChild((TerminalNode) close);
                }
            }
        });
        return parser;
    }

    private record Body(int open, int close) {
        boolean contains(int index) {
            return index > open && index < close;
        }
    }

    private static final class FunctionTokenStream extends CommonTokenStream {
        private final List<Body> bodies = new ArrayList<>();

        FunctionTokenStream(DRL10Lexer lexer) {
            super(lexer);
        }

        @Override
        protected void setup() {
            // Stay lazy until the caller has attached lexer error listeners.
            while (fetch(1024) == 1024) {
                // Fill without calling fill(), which would re-enter setup().
            }
            findLambdaFunctions();
            super.setup();
        }

        private void findLambdaFunctions() {
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).getType() != DRL10Lexer.DRL_FUNCTION) {
                    continue;
                }
                int open = i + 1;
                while (open < tokens.size() && tokens.get(open).getType() != DRL10Lexer.LBRACE
                        && tokens.get(open).getType() != Token.EOF) {
                    open++;
                }
                if (open >= tokens.size() || tokens.get(open).getType() == Token.EOF) {
                    continue;
                }
                int depth = 1;
                int close = open;
                boolean lambda = false;
                while (++close < tokens.size() && depth > 0) {
                    int type = tokens.get(close).getType();
                    if (type == DRL10Lexer.LBRACE) depth++;
                    if (type == DRL10Lexer.RBRACE) depth--;
                    if (type == DRL10Lexer.ARROW) lambda = true;
                    if (depth == 0) break;
                }
                if (depth == 0 && lambda && validSignature(slice(i, open) + "}")
                        && validJavaBlock(slice(open, close))) {
                    bodies.add(new Body(open, close));
                    i = close;
                }
            }
        }

        private String slice(int start, int stop) {
            return tokenSource.getInputStream().getText(Interval.of(
                    tokens.get(start).getStartIndex(), tokens.get(stop).getStopIndex()));
        }

        private boolean hiddenByCompatibility(int index) {
            return bodies.stream().anyMatch(body -> body.contains(index));
        }

        boolean isOpaqueBlock(ParserRuleContext ctx) {
            return bodies.contains(new Body(ctx.start.getTokenIndex(), ctx.stop.getTokenIndex()));
        }

        @Override
        protected int nextTokenOnChannel(int i, int channel) {
            int next = super.nextTokenOnChannel(i, channel);
            while (hiddenByCompatibility(next)) next = super.nextTokenOnChannel(next + 1, channel);
            return next;
        }

        @Override
        protected int previousTokenOnChannel(int i, int channel) {
            int previous = super.previousTokenOnChannel(i, channel);
            while (hiddenByCompatibility(previous)) previous = super.previousTokenOnChannel(previous - 1, channel);
            return previous;
        }
    }

    private static boolean validSignature(String signature) {
        DRL10Parser parser = DRL10ParserHelper.createDrlParser(signature);
        Errors errors = new Errors();
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        var unit = parser.compilationUnit();
        return errors.count == 0 && parser.getCurrentToken().getType() == Token.EOF
                && unit.drlStatementdef().size() == 1 && unit.drlStatementdef(0).functiondef() != null;
    }

    private static boolean validJavaBlock(String text) {
        Errors errors = new Errors();
        JavaLexer lexer = new JavaLexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(errors);
        JavaParser parser = new JavaParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(errors);
        parser.block();
        return errors.count == 0 && parser.getCurrentToken().getType() == Token.EOF;
    }

    private static final class Errors extends BaseErrorListener {
        int count;

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int column, String message, RecognitionException e) {
            count++;
        }
    }
}
