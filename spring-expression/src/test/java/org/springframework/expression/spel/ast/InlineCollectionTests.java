/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.expression.spel.ast;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Tests for {@link InlineList} and {@link InlineMap}.
 *
 * @author Sam Brannen
 * @since 5.3.39
 */
class InlineCollectionTests {

	private static final SpelExpressionParser parser = new SpelExpressionParser();


	@Nested
	class InlineListTests {

		@Test
		@SuppressWarnings({ "rawtypes", "unchecked" })
		void getConstantValue() {
			InlineList inlineList = parseList("{1, 2, 3, 4}");
			assertThat(inlineList.isConstant()).isTrue();
			List list1 = inlineList.getConstantValue();
			List list2 = (List) inlineList.getValue(expressionState());
			assertThat(list1).containsExactly(1, 2, 3, 4).isSameAs(list2);
		}

		@Test
		@SuppressWarnings({ "rawtypes", "unchecked" })
		void constantListIsCached() {
			ExpressionState expressionState = expressionState();

			InlineList inlineList = parseList("{1, 2, 3, 4}");
			assertThat(inlineList.isConstant()).isTrue();
			List list1 = (List) inlineList.getValue(expressionState);
			List list2 = (List) inlineList.getValue(expressionState);
			assertThat(list1).containsExactly(1, 2, 3, 4).isSameAs(list2);
			// Constant inline lists are immutable.
			assertThatExceptionOfType(UnsupportedOperationException.class)
					.isThrownBy(() -> list1.add(999));
		}

		@Test
		void dynamicListIsNotCached() {
			InlineList list = parseList("{1, (5 - 3), 3, 4}");
			assertThat(list.isConstant()).isFalse();
			assertThat(list.getValue(expressionState())).isEqualTo(Arrays.asList(1, 2, 3, 4));
		}

		@Test
		void nestedInlineListIsConstant() {
			InlineList list = parseList("{{1, 2}, {3, 4}}");
			assertThat(list.isConstant()).isTrue();
		}

		@Test
		void nestedDynamicInlineListIsNotConstant() {
			InlineList list = parseList("{{1, 2}, {(5 - 2), 4}}");
			assertThat(list.isConstant()).isFalse();
		}

		private InlineList parseList(String s) {
			SpelExpression expression = parseExpression(s);
			return (InlineList) expression.getAST();
		}
	}


	@Nested
	class InlineMapTests {

		@Test
		@SuppressWarnings({ "rawtypes", "unchecked" })
		void getConstantValue() {
			InlineMap inlineMap = parseMap("{1 : 2, 3 : 4}");
			assertThat(inlineMap.isConstant()).isTrue();
			Map map1 = inlineMap.getConstantValue();
			Map map2 = (Map) inlineMap.getValue(expressionState());
			Map<Integer, Integer> expected = new HashMap<>();
			expected.put(1, 2);
			expected.put(3, 4);
			assertThat(map1).isEqualTo(expected).isSameAs(map2);
		}

		@Test
		@SuppressWarnings({ "rawtypes", "unchecked" })
		void constantMapIsCached() {
			ExpressionState expressionState = expressionState();

			InlineMap inlineMap = parseMap("{1 : 2, 3 : 4}");
			assertThat(inlineMap.isConstant()).isTrue();
			Map map1 = (Map) inlineMap.getValue(expressionState);
			Map map2 = (Map) inlineMap.getValue(expressionState);
			Map<Integer, Integer> expected = new HashMap<>();
			expected.put(1, 2);
			expected.put(3, 4);
			assertThat(map1).isEqualTo(expected).isSameAs(map2);
			// Constant inline maps are immutable.
			assertThatExceptionOfType(UnsupportedOperationException.class)
					.isThrownBy(() -> map1.put(99, "X"));
		}

		@Test
		void dynamicMapIsNotCached() {
			InlineMap map = parseMap("{-1 : 2, (-2 - 1) : -4}");
			assertThat(map.isConstant()).isFalse();
			Map<Integer, Integer> expected = new HashMap<>();
			expected.put(-1, 2);
			expected.put(-3, -4);
			assertThat(map.getValue(expressionState())).isEqualTo(expected);
		}

		@Test
		void mapWithPositiveKeysAndValuesIsCached() {
			InlineMap map = parseMap("{1 : 2, 3 : 4}");
			assertThat(map.isConstant()).isTrue();
			Map<Integer, Integer> expected = new HashMap<>();
			expected.put(1, 2);
			expected.put(3, 4);
			assertThat(map.getValue(expressionState())).isEqualTo(expected);
		}

		@Test
		void mapWithStringKeysIsCached() {
			InlineMap map = parseMap("{name : 'foo', age : 12}");
			assertThat(map.isConstant()).isTrue();
			Map<String, Object> expected = new HashMap<>();
			expected.put("name", "foo");
			expected.put("age", 12);
			assertThat(map.getValue(expressionState())).isEqualTo(expected);
		}

		private InlineMap parseMap(String s) {
			SpelExpression expression = parseExpression(s);
			return (InlineMap) expression.getAST();
		}
	}

	private static SpelExpression parseExpression(String s) {
		return (SpelExpression) parser.parseExpression(s);
	}

	private static ExpressionState expressionState() {
		return new ExpressionState(new StandardEvaluationContext());
	}

}
