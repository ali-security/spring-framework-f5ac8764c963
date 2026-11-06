/*
 * Copyright 2002-2023 the original author or authors.
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.standard.SpelExpression;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Unit tests for SpEL's {@code matches} operator, with an emphasis on the
 * bounded pattern cache and on the maximum regular expression length.
 *
 * @see OperatorMatches
 */
class OperatorMatchesTests {

	private static final int MAX_REGEX_LENGTH = 1000;

	private final SpelExpressionParser parser = new SpelExpressionParser();


	@Test
	void patternCacheSizeIsBounded() {
		assertThat(OperatorMatches.MAX_PATTERN_CACHE_SIZE).isEqualTo(256);
	}

	@Test
	void patternCacheDoesNotGrowBeyondTheMaximumCacheSize() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		// Feed the operator far more distinct regular expressions than the cache
		// is allowed to hold: prior to the fix, every one of them was retained in
		// an unbounded ConcurrentHashMap.
		int distinctRegexCount = OperatorMatches.MAX_PATTERN_CACHE_SIZE * 4;
		for (int i = 0; i < distinctRegexCount; i++) {
			context.setVariable("regex", distinctRegex(i));
			assertThat(expression.getValue(context, Boolean.class)).isTrue();
		}

		PatternCacheAccessor patternCache = patternCache(expression);
		assertThat(patternCache.size()).isLessThanOrEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
		assertThat(patternCache.sizeLimit()).isEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);

		// Evicted patterns are transparently recompiled, so results stay correct.
		context.setVariable("regex", distinctRegex(0));
		assertThat(expression.getValue(context, Boolean.class)).isTrue();
		context.setVariable("regex", "does-not-match");
		assertThat(expression.getValue(context, Boolean.class)).isFalse();
		assertThat(patternCache.size()).isLessThanOrEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}

	@Test
	void regexExceedingMaxLengthIsRejectedBeforeTheCacheIsConsulted() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		String tooLongRegex = "abc|" + repeat("x", MAX_REGEX_LENGTH);
		assertThat(tooLongRegex.length()).isGreaterThan(MAX_REGEX_LENGTH);
		context.setVariable("regex", tooLongRegex);

		// A regex that has never been seen before must be rejected...
		assertMaxRegexLengthExceeded(expression, context);
		// ...and it must still be rejected when evaluated again, which proves the
		// length check runs before the cache lookup and that the oversized regex
		// was never admitted to the cache.
		assertMaxRegexLengthExceeded(expression, context);
		assertThat(patternCache(expression).size()).isZero();

		// The same holds once the cache has already been populated with valid patterns.
		context.setVariable("regex", "abc");
		assertThat(expression.getValue(context, Boolean.class)).isTrue();
		assertThat(patternCache(expression).size()).isEqualTo(1);

		context.setVariable("regex", tooLongRegex + "y");
		assertMaxRegexLengthExceeded(expression, context);
		assertMaxRegexLengthExceeded(expression, context);
		assertThat(patternCache(expression).size()).isEqualTo(1);
	}

	@Test
	void regexOfMaxLengthIsStillAccepted() {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");

		String regex = "abc|" + repeat("x", MAX_REGEX_LENGTH - 4);
		assertThat(regex).hasSize(MAX_REGEX_LENGTH);
		context.setVariable("regex", regex);

		assertThat(expression.getValue(context, Boolean.class)).isTrue();
	}

	@Test
	void invalidRegexIsRejectedAndNeverCached() throws Exception {
		SpelExpression expression = parseMatchesExpression();
		StandardEvaluationContext context = new StandardEvaluationContext();
		context.setVariable("input", "abc");
		context.setVariable("regex", "abc(");

		for (int i = 0; i < 2; i++) {
			assertThatExceptionOfType(SpelEvaluationException.class)
					.isThrownBy(() -> expression.getValue(context, Boolean.class))
					.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.INVALID_PATTERN));
		}
		assertThat(patternCache(expression).size()).isZero();
	}

	@Test
	void matchesEvaluatesPositivelyAndNegatively() {
		StandardEvaluationContext context = new StandardEvaluationContext();
		assertThat(parser.parseExpression("'5.00' matches '^-?\\d+(\\.\\d{2})?$'")
				.getValue(context, Boolean.class)).isTrue();
		assertThat(parser.parseExpression("'5.0067' matches '^-?\\d+(\\.\\d{2})?$'")
				.getValue(context, Boolean.class)).isFalse();
	}

	@Test
	void concurrentMapConstructorIgnoresSuppliedMapButStillEvaluates() throws Exception {
		ConcurrentMap<String, Pattern> suppliedCache = new ConcurrentHashMap<>();
		OperatorMatches operator = new OperatorMatches(suppliedCache, 0, 26,
				stringLiteral("'5.00'"), stringLiteral("'^-?\\d+(\\.\\d{2})?$'"));

		ExpressionState state = new ExpressionState(new StandardEvaluationContext());
		assertThat(operator.getValueInternal(state).getValue()).isEqualTo(Boolean.TRUE);

		// The supplied unbounded map is no longer used at all.
		assertThat(suppliedCache).isEmpty();
		assertThat(patternCache(operator).sizeLimit()).isEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
		assertThat(patternCache(operator).size()).isEqualTo(1);
	}

	@Test
	@SuppressWarnings("deprecation")
	void deprecatedNoCacheConstructorStillEvaluates() throws Exception {
		OperatorMatches operator = new OperatorMatches(0, 28,
				stringLiteral("'5.0067'"), stringLiteral("'^-?\\d+(\\.\\d{2})?$'"));

		ExpressionState state = new ExpressionState(new StandardEvaluationContext());
		assertThat(operator.getValueInternal(state).getValue()).isEqualTo(Boolean.FALSE);
		assertThat(patternCache(operator).sizeLimit()).isEqualTo(OperatorMatches.MAX_PATTERN_CACHE_SIZE);
	}


	private SpelExpression parseMatchesExpression() {
		return (SpelExpression) this.parser.parseExpression("#input matches #regex");
	}

	private static void assertMaxRegexLengthExceeded(SpelExpression expression, StandardEvaluationContext context) {
		assertThatExceptionOfType(SpelEvaluationException.class)
				.isThrownBy(() -> expression.getValue(context, Boolean.class))
				.satisfies(ex -> assertThat(ex.getMessageCode()).isEqualTo(SpelMessage.MAX_REGEX_LENGTH_EXCEEDED));
	}

	private static String distinctRegex(int index) {
		// Always matches "abc", but the regex text itself is unique per index.
		return "abc|no-match-" + index;
	}

	private static StringLiteral stringLiteral(String quotedValue) {
		return new StringLiteral(quotedValue, 0, quotedValue.length(), quotedValue);
	}

	private static PatternCacheAccessor patternCache(SpelExpression expression) throws Exception {
		return patternCache((OperatorMatches) expression.getAST());
	}

	private static PatternCacheAccessor patternCache(OperatorMatches operator) throws Exception {
		Field field = OperatorMatches.class.getDeclaredField("patternCache");
		field.setAccessible(true);
		return new PatternCacheAccessor(field.get(operator));
	}

	private static String repeat(String str, int count) {
		StringBuilder sb = new StringBuilder(str.length() * count);
		for (int i = 0; i < count; i++) {
			sb.append(str);
		}
		return sb.toString();
	}


	/**
	 * Reflective view onto the private pattern cache held by an {@link OperatorMatches}.
	 */
	private static class PatternCacheAccessor {

		private final Object patternCache;

		PatternCacheAccessor(Object patternCache) {
			assertThat(patternCache).isNotNull();
			this.patternCache = patternCache;
		}

		int size() throws Exception {
			return invoke("size");
		}

		int sizeLimit() throws Exception {
			return invoke("sizeLimit");
		}

		private int invoke(String methodName) throws Exception {
			Method method = this.patternCache.getClass().getDeclaredMethod(methodName);
			method.setAccessible(true);
			return (Integer) method.invoke(this.patternCache);
		}
	}

}
