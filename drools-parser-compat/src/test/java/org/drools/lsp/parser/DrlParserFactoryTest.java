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

import java.util.concurrent.atomic.AtomicInteger;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DrlParserFactoryTest {

    static final String FUNCTION = """
            function List collect(List xs, List out) {
              xs.forEach(e -> out.add(e));
              xs.forEach((Object e) -> { out.add(e); });
              xs.forEach(e -> xs.forEach(f -> out.add(f)));
              return out;
            }
            """;

    @Test
    void lambdasDoNotSwallowEarlierRulesOrFollowingDeclarations() {
        String text = """
                // supplementary character before the function: \uD801\uDC00
                package p;
                rule R1
                  @tag("before")
                  when Foo() then end
                """ + FUNCTION + """
                declare Foo
                  value : String
                end
                rule R2 when Foo() then end
                """;
        DRL10Parser parser = DrlParserFactory.createDrlParser(text);
        var unit = parser.compilationUnit();
        assertThat(parser.getNumberOfSyntaxErrors()).isZero();
        assertThat(unit.drlStatementdef()).hasSize(4);
        assertThat(unit.drlStatementdef(0).ruledef().lhs()).isNotNull();
        assertThat(unit.drlStatementdef(1).functiondef().drlBlock().getText())
                .contains("e->out.add(e)", "f->out.add(f)");
        assertThat(unit.drlStatementdef(2).declaredef().typeDeclaration()).isNotNull();
        assertThat(unit.drlStatementdef(3).ruledef().lhs()).isNotNull();
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();
        assertThat(tokens.getText()).isEqualTo(text);
        assertThat(tokens.getTokens().stream().filter(t -> "->".equals(t.getText())))
                .allSatisfy(t -> assertThat(t.getChannel()).isZero()).hasSize(4);
    }

    @Test
    void agendaGroupMayBeFollowedByOtherAttributes() {
        for (String attrs : new String[] {
                "agenda-group \"a\"\n  salience 10",
                "dialect \"java\"\n  agenda-group \"a\"\n  no-loop true",
                "agenda-group \"a\", salience 10",
                "salience 5\n  agenda-group \"a\"" }) {
            DRL10Parser parser = DrlParserFactory.createDrlParser(
                    "package p\nrule \"r\"\n  " + attrs + "\n  when\n  then\nend\n");
            parser.removeErrorListeners();
            parser.compilationUnit();
            assertThat(parser.getNumberOfSyntaxErrors()).as(attrs).isZero();
        }
    }

    @Test
    void spacedAgendaMinusGroupIsNotMerged() {
        DRL10Parser parser = DrlParserFactory.createDrlParser("agenda - group");
        CommonTokenStream tokens = (CommonTokenStream) parser.getTokenStream();
        tokens.fill();
        assertThat(tokens.getTokens()).extracting(t -> t.getText()).contains("agenda", "-", "group");
    }

    @Test
    void malformedLambdaStillReportsAnError() {
        DRL10Parser parser = DrlParserFactory.createDrlParser(
                "function void bad(List xs) { xs.forEach(e -> out.add( )); return +; }");
        parser.removeErrorListeners();
        parser.compilationUnit();
        assertThat(parser.getNumberOfSyntaxErrors()).isPositive();
    }

    @Test
    void tokenizationStaysLazyUntilErrorListenersAreAttached() {
        DRL10Parser parser = DrlParserFactory.createDrlParser(FUNCTION + "global String \"unterminated");
        assertThat(parser.getTokenStream().size()).isZero();
        AtomicInteger errors = new AtomicInteger();
        Lexer lexer = (Lexer) parser.getTokenStream().getTokenSource();
        lexer.removeErrorListeners();
        var listener = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object symbol, int line,
                                    int column, String message, RecognitionException e) {
                errors.incrementAndGet();
            }
        };
        lexer.addErrorListener(listener);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        parser.compilationUnit();
        assertThat(errors.get()).isPositive();
    }
}
