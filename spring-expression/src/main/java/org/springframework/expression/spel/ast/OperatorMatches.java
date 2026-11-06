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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.expression.EvaluationException;
import org.springframework.expression.spel.ExpressionState;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelMessage;
import org.springframework.expression.spel.support.BooleanTypedValue;
import org.springframework.util.Assert;

/**
 * Implements the matches operator. Matches takes two operands:
 * The first is a String and the second is a Java regex.
 * It will return {@code true} when {@link #getValue} is called
 * if the first operand matches the regex.
 *
 * @author Andy Clement
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.0
 */
public class OperatorMatches extends Operator {

	/**
	 * Maximum number of compiled regular expressions in the pattern cache: {@value}.
	 */
	public static final int MAX_PATTERN_CACHE_SIZE = 256;

	private static final int PATTERN_ACCESS_THRESHOLD = 1000000;

	/**
	 * Maximum number of characters permitted in a regular expression.
	 * @since 5.2.23
	 */
	private static final int MAX_REGEX_LENGTH = 1000;

	private final PatternCache patternCache;


	/**
	 * Create a new {@link OperatorMatches} instance.
	 * @deprecated as of Spring Framework 5.2.23 in favor of invoking
	 * {@link #OperatorMatches(ConcurrentMap, int, int, SpelNodeImpl...)}
	 * with a shared pattern cache instead
	 */
	@Deprecated
	public OperatorMatches(int startPos, int endPos, SpelNodeImpl... operands) {
		this(new ConcurrentHashMap<>(), startPos, endPos, operands);
	}

	/**
	 * Create a new {@link OperatorMatches} instance with a shared pattern cache.
	 * <p>The supplied {@code patternCacheMap} is ignored: the operator always compiles
	 * through its own pattern cache, bounded to {@value #MAX_PATTERN_CACHE_SIZE}
	 * entries, so that an unbounded shared map cannot be grown without limit.
	 * @since 5.2.23
	 */
	public OperatorMatches(ConcurrentMap<String, Pattern> patternCacheMap,
			int startPos, int endPos, SpelNodeImpl... operands) {

		super("matches", startPos, endPos, operands);
		this.patternCache = new PatternCache(MAX_PATTERN_CACHE_SIZE, Pattern::compile);
	}

	/**
	 * Check the first operand matches the regex specified as the second operand.
	 * @param state the expression state
	 * @return {@code true} if the first operand matches the regex specified as the
	 * second operand, otherwise {@code false}
	 * @throws EvaluationException if there is a problem evaluating the expression
	 * (e.g. the regex is invalid)
	 */
	@Override
	public BooleanTypedValue getValueInternal(ExpressionState state) throws EvaluationException {
		SpelNodeImpl leftOp = getLeftOperand();
		SpelNodeImpl rightOp = getRightOperand();

		String input = leftOp.getValue(state, String.class);
		if (input == null) {
			throw new SpelEvaluationException(leftOp.getStartPosition(),
					SpelMessage.INVALID_FIRST_OPERAND_FOR_MATCHES_OPERATOR, (Object) null);
		}

		Object right = rightOp.getValue(state);
		if (!(right instanceof String)) {
			throw new SpelEvaluationException(rightOp.getStartPosition(),
					SpelMessage.INVALID_SECOND_OPERAND_FOR_MATCHES_OPERATOR, right);
		}
		String regex = (String) right;
		if (regex.length() > MAX_REGEX_LENGTH) {
			throw new SpelEvaluationException(getStartPosition(),
					SpelMessage.MAX_REGEX_LENGTH_EXCEEDED, MAX_REGEX_LENGTH);
		}

		state.trackOperation();
		try {
			Pattern pattern = this.patternCache.get(regex);
			Matcher matcher = pattern.matcher(new MatcherInput(input, new AccessCount()));
			return BooleanTypedValue.forValue(matcher.matches());
		}
		catch (PatternSyntaxException ex) {
			throw new SpelEvaluationException(
					rightOp.getStartPosition(), ex, SpelMessage.INVALID_PATTERN, right);
		}
		catch (IllegalStateException ex) {
			throw new SpelEvaluationException(
					rightOp.getStartPosition(), ex, SpelMessage.FLAWED_PATTERN, right);
		}
	}


	/**
	 * Simple LRU (Least Recently Used) cache of compiled regular expressions,
	 * bounded by a maximum number of entries.
	 * <p>Patterns that fail to compile are never admitted to the cache.
	 */
	private static class PatternCache {

		private final int sizeLimit;

		private final Function<String, Pattern> generator;

		private final ConcurrentHashMap<String, Pattern> cache = new ConcurrentHashMap<>();

		private final ConcurrentLinkedDeque<String> queue = new ConcurrentLinkedDeque<>();

		private final ReadWriteLock lock = new ReentrantReadWriteLock();

		private volatile int size;

		public PatternCache(int sizeLimit, Function<String, Pattern> generator) {
			Assert.isTrue(sizeLimit > 0, "Cache size limit must be positive");
			Assert.notNull(generator, "Generator function must not be null");
			this.sizeLimit = sizeLimit;
			this.generator = generator;
		}

		public Pattern get(String key) {
			Pattern cached = this.cache.get(key);
			if (cached != null) {
				if (this.size < this.sizeLimit) {
					return cached;
				}
				this.lock.readLock().lock();
				try {
					if (this.queue.removeLastOccurrence(key)) {
						this.queue.offer(key);
					}
					return cached;
				}
				finally {
					this.lock.readLock().unlock();
				}
			}
			this.lock.writeLock().lock();
			try {
				// Retrying in case of concurrent reads on the same key
				cached = this.cache.get(key);
				if (cached != null) {
					if (this.queue.removeLastOccurrence(key)) {
						this.queue.offer(key);
					}
					return cached;
				}
				// Generate value first, to prevent size inconsistency
				// (and to keep patterns that fail to compile out of the cache)
				Pattern value = this.generator.apply(key);
				if (this.size == this.sizeLimit) {
					String leastUsed = this.queue.poll();
					if (leastUsed != null) {
						this.cache.remove(leastUsed);
					}
				}
				this.queue.offer(key);
				this.cache.put(key, value);
				this.size = this.cache.size();
				return value;
			}
			finally {
				this.lock.writeLock().unlock();
			}
		}

		public int sizeLimit() {
			return this.sizeLimit;
		}

		public int size() {
			return this.size;
		}
	}


	private static class AccessCount {

		private int count;

		public void check() throws IllegalStateException {
			if (this.count++ > PATTERN_ACCESS_THRESHOLD) {
				throw new IllegalStateException("Pattern access threshold exceeded");
			}
		}
	}


	private static class MatcherInput implements CharSequence {

		private final CharSequence value;

		private final AccessCount access;

		public MatcherInput(CharSequence value, AccessCount access) {
			this.value = value;
			this.access = access;
		}

		@Override
		public char charAt(int index) {
			this.access.check();
			return this.value.charAt(index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new MatcherInput(this.value.subSequence(start, end), this.access);
		}

		@Override
		public int length() {
			return this.value.length();
		}

		@Override
		public String toString() {
			return this.value.toString();
		}
	}

}
