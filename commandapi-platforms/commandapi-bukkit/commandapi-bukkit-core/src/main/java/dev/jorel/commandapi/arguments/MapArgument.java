package dev.jorel.commandapi.arguments;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * An argument that represents a key-value pair.
 *
 * @param <K> The type of keys this map will contain
 * @param <V> The type of values this map will contain
 * @apiNote Returns a {@link LinkedHashMap} object
 * @since 9.0.0
 */
@SuppressWarnings("rawtypes")
public class MapArgument<K, V> extends Argument<LinkedHashMap> implements GreedyArgument {

	private final String delimiter;
	private final String separator;
	private final StringParser<K> keyMapper;
	private final StringParser<V> valueMapper;

	private final ResultList keyList;
	private final ResultList valueList;
	private final boolean allowValueDuplicates;
	private final boolean keyListEmpty;
	private final boolean valueListEmpty;

	/**
	 * Constructs a {@link MapArgument}
	 *
	 * @param nodeName  the name to assign to this argument node
	 * @param delimiter This is used to separate key-value pairs
	 */
	MapArgument(String nodeName, String delimiter, String separator, StringParser<K> keyMapper, StringParser<V> valueMapper, List<String> keyList, List<String> valueList, boolean allowValueDuplicates) {
		super(nodeName, StringArgumentType.greedyString());

		this.delimiter = delimiter;
		this.separator = separator;
		this.keyMapper = keyMapper;
		this.valueMapper = valueMapper;

		this.keyList = ResultList.formatResults(keyList, delimiter);
		this.valueList = ResultList.formatResults(valueList, separator);
		this.allowValueDuplicates = allowValueDuplicates;

		this.keyListEmpty = keyList == null;
		this.valueListEmpty = valueList == null;

		applySuggestions();
	}

	private void applySuggestions() {
		super.replaceSuggestions((info, builder) -> {
			StringReader reader = new StringReader(info.currentArg());

			// Read through the keys and values
			Set<String> givenKeys = new HashSet<>();
			Set<String> givenValues = new HashSet<>();
			List<String> keyList = new ArrayList<>(this.keyList.results);
			List<String> valueList = new ArrayList<>(this.valueList.results);

			boolean isKey = true;
			while (true) {
				if (reader.getRemainingLength() == 0)
					return startSuggestions(builder, isKey ? keyList : valueList, isKey);

				boolean isQuoted = reader.peek() == '"';
				String result;
				try {
					result = isQuoted ? readQuoted(reader, isKey) : readUnquoted(reader, isKey);
				} catch (CommandSyntaxException ignored) {
					// Exception is thrown when the key/value never terminates
					//  That means this key/value ends the argument, so we should do the suggestions now
					builder = builder.createOffset(builder.getStart() + reader.getCursor() - (isQuoted ? 1 : 0));
					String ending = readEscapedUntilEnd(reader);
					if (!(isKey ? keyListEmpty : valueListEmpty))
						return doResultSuggestions(ending, builder, isKey ? keyList : valueList, isKey, isQuoted);
					return doEmptySuggestions(ending, builder, isKey, isQuoted);
				}

				if (!(isKey ? keyListEmpty : valueListEmpty)) {
					// Enforce the lists if they are not empty
					List<String> relaventList = isKey ? keyList : valueList;

					if (!relaventList.contains(result)) throw invalidResult(result, reader, isKey, isQuoted);

					if (isKey || !allowValueDuplicates) relaventList.remove(result);
				} else if(isKey || !allowValueDuplicates) {
					// If no lists given, we still may enforce duplicates
					if(!(isKey ? givenKeys : givenValues).add(result)) throw invalidResult(result, reader, isKey, isQuoted);
				}

				// Make sure result is valid according to the parsers
				try {
					if (isKey) keyMapper.parse(result);
					else valueMapper.parse(result);
				} catch (Exception e) {
					throw handleParserException(e, result, reader, isKey, isQuoted);
				}

				// Handle separator
				String relevantSeparator = isKey ? delimiter : separator;
				if (!reader.canRead(relevantSeparator.length())) {
					// Argument ends at a separator
					//  If the separator is being typed correctly, suggest they keep going
					//  If the separator is being typed incorrectly, this suggests overriding with the correct separator
					builder = builder.createOffset(builder.getStart() + reader.getCursor());
					builder.suggest(relevantSeparator);
					return builder.buildFuture();
				} else {
					// Argument seems to keep going, validate separator
					int start = reader.getCursor();
					reader.setCursor(start + relevantSeparator.length());
					String typedSeparator = reader.getString().substring(start, reader.getCursor());
					if (!relevantSeparator.equals(typedSeparator)) {
						reader.setCursor(start); // Set cursor back to start to underline bad typed separator
						throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(reader, separatorRequiredMessage(isKey));
					}
					// All good, keep going
				}

				// Move to next key/value
				isKey = !isKey;
			}
		});
	}

	private CompletableFuture<Suggestions> startSuggestions(SuggestionsBuilder builder, List<String> unusedResults, boolean isKey) {
		// Nothing written yet, give the preferred suggestions
		builder = builder.createOffset(builder.getStart() + builder.getRemaining().length());
		ResultList relevantList = isKey ? keyList : valueList;

		for (String result : unusedResults) {
			// We either prefer quoted or unquoted, so this should only suggest 1 per result
			String unquotedSuggestion = relevantList.preferredUnquoted.get(result);
			if(unquotedSuggestion != null) builder.suggest(unquotedSuggestion);

			String quotedSuggestion = relevantList.preferredQuoted.get(result);
			if(quotedSuggestion != null) builder.suggest('"' + quotedSuggestion + '"');
		}
		return builder.buildFuture();
	}

	private CompletableFuture<Suggestions> doResultSuggestions(String ending, SuggestionsBuilder builder, List<String> unusedResults, boolean isKey, boolean isQuoted) {
		String quotedInsert = isQuoted ? "\"" : "";
		String relevantSeparator = isKey ? delimiter : separator;

		ResultList relevantList = isKey ? keyList : valueList;
		Map<String, String> suggestionsMap = isQuoted ? relevantList.quoted : relevantList.unquoted;

		// Suggest key/value if they fit
		for (String result : unusedResults) {
			// If result starts with ending, and they are the same length, they must be equal
			boolean sameLength = result.length() == ending.length();
			if (result.startsWith(ending)) {
				// Started typing one of the results
				//  If they are equal, the key/value is complete, so we should also add the separator
				builder.suggest(quotedInsert + suggestionsMap.get(result) + quotedInsert + (sameLength ? relevantSeparator : ""));
			}
			if (!sameLength && ending.startsWith(result)) {
				// Typed a value result, then attempted to start the separator
				//  Always suggest the separator following because it is necessary
				builder.suggest(quotedInsert + suggestionsMap.get(result) + quotedInsert + relevantSeparator);
			}
		}

		return builder.buildFuture();
	}

	private CompletableFuture<Suggestions> doEmptySuggestions(String ending, SuggestionsBuilder builder, boolean isKey, boolean isQuoted) {
		// If the result isn't from a set list, always suggest completing it with the terminator at the end
		builder = builder.createOffset(builder.getStart());
		String quotedInsert = (isQuoted ? "\"" : "");
		builder.suggest(quotedInsert + ending + quotedInsert + (isKey ? delimiter : separator));
		return builder.buildFuture();
	}

	@Override
	public Class<LinkedHashMap> getPrimitiveType() {
		return LinkedHashMap.class;
	}

	@Override
	public CommandAPIArgumentType getArgumentType() {
		return CommandAPIArgumentType.MAP;
	}

	@Override
	public <Source> LinkedHashMap<K, V> parseArgument(CommandContext<Source> cmdCtx, String key, CommandArguments previousArgs) throws CommandSyntaxException {
		StringReader reader = new StringReader(cmdCtx.getArgument(key, String.class));
		LinkedHashMap<K, V> results = new LinkedHashMap<>();
		K builtKey = null;

		// Read through the keys and values
		Set<String> givenKeys = new HashSet<>();
		Set<String> givenValues = new HashSet<>();
		List<String> keyList = new ArrayList<>(this.keyList.results);
		List<String> valueList = new ArrayList<>(this.valueList.results);

		boolean isKey = true;
		while (true) {
			if (reader.getRemainingLength() == 0) {
				// Nothing left to read
				if (isKey)
					// If looking for another key-value pair, we're done!
					return results;

				// If looking for a value, that is missing
				throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(reader, "Expected a value after the key");
			}

			boolean isQuoted = reader.peek() == '"';
			String result;
			try {
				result = isQuoted ? readQuoted(reader, isKey) : readUnquoted(reader, isKey);
			} catch (CommandSyntaxException e) {
				// Usually, this error is correct
				//  However, if this is an unquoted value (not quoted and not key),
				//  it does make sense for the argument to end without the separator
				if (!isQuoted && !isKey) {
					result = readEscapedUntilEnd(reader);
				} else {
					throw e;
				}
			}

			if (!(isKey ? keyListEmpty : valueListEmpty)) {
				// Enforce the lists if they are not empty
				List<String> relaventList = isKey ? keyList : valueList;

				if (!relaventList.contains(result)) throw invalidResult(result, reader, isKey, isQuoted);

				if (isKey || !allowValueDuplicates) relaventList.remove(result);
			} else if(isKey || !allowValueDuplicates) {
				// If no lists given, we still may enforce duplicates
				if(!(isKey ? givenKeys : givenValues).add(result)) throw invalidResult(result, reader, isKey, isQuoted);
			}

			// Make sure result is valid according to the parsers
			try {
				if (isKey) {
					builtKey = keyMapper.parse(result);
				} else {
					V value = valueMapper.parse(result);
					results.put(builtKey, value);
				}
			} catch (Exception e) {
				throw handleParserException(e, result, reader, isKey, isQuoted);
			}

			// Handle separator
			String relevantSeparator = isKey ? delimiter : separator;
			if (!reader.canRead(relevantSeparator.length())) {
				// Argument ends at a separator
				if (!reader.canRead()) {
					// There is no trailing data
					if (!isKey)
						// If we just read a value, we're all done!
						return results;
					else
						// Otherwise, we ended on a key with no value
						throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(reader, separatorRequiredMessage(true));
				}

				// There is trailing data
				throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(reader, separatorRequiredMessage(isKey));
			} else {
				// Argument seems to keep going, validate separator
				int start = reader.getCursor();
				reader.setCursor(start + relevantSeparator.length());
				String typedSeparator = reader.getString().substring(start, reader.getCursor());
				if (!relevantSeparator.equals(typedSeparator)) {
					reader.setCursor(start); // Set cursor back to start to underline bad typed separator
					throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(reader, separatorRequiredMessage(isKey));
				}
				// All good, keep going
			}

			// Move to next key/value
			isKey = !isKey;
		}
	}

	private String readQuoted(StringReader reader, boolean isKey) throws CommandSyntaxException {
		// This method should only be called after a " was found
		//  If this method is called in any other circumstance, that's a problem with the code
		if (reader.read() != '"')
			throw new IllegalStateException("readQuoted was called, but the reader did not start with '\"'");

		String result = readUntil(reader, "\"", "A quoted " + (isKey ? "key" : "value") + " must end with a quotation mark");
		reader.skip(); // We know this terminated with " - skip that so caller can start reading separator
		return result;
	}

	private String readUnquoted(StringReader reader, boolean isKey) throws CommandSyntaxException {
		return readUntil(reader, isKey ? delimiter : separator, separatorRequiredMessage(isKey));
	}

	private String readUntil(StringReader reader, String terminator, String reachedEndErrorMessage) throws CommandSyntaxException {
		// Inspired by StringReader#readUntil, but what I wish it would actually do
		int start = reader.getCursor();
		char firstTerminatorChar = terminator.charAt(0);

		StringBuilder result = new StringBuilder();
		boolean escaped = false;
		readLoop:
		while (reader.canRead()) {
			char c = reader.read();
			if (escaped) {
				result.append(c);
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else if (c == firstTerminatorChar) {
				// Check if this is really the start of the terminator
				if (!reader.canRead(terminator.length() - 1)) continue; // Not long enough to fit terminator

				int i = 1;
				while (i < terminator.length()) {
					if (reader.peek(i - 1) != terminator.charAt(i)) {
						// wasn't actually the terminator, continue searching
						result.append(c);
						continue readLoop;
					}
					i++;
				}
				// Move reader back 1 char so that caller method can start reading the terminator itself
				reader.setCursor(reader.getCursor() - 1);
				return result.toString();
			} else {
				result.append(c);
			}
		}

		// Reset the cursor, so it underlines the entire invalid key/value
		reader.setCursor(start);
		throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(reader, reachedEndErrorMessage);
	}

	private String readEscapedUntilEnd(StringReader reader) {
		StringBuilder result = new StringBuilder();
		boolean escaped = false;
		while (reader.canRead()) {
			char c = reader.read();
			if (escaped) {
				result.append(c);
				escaped = false;
			} else if (c == '\\') {
				escaped = true;
			} else {
				result.append(c);
			}
		}
		return result.toString();
	}

	private CommandSyntaxException invalidResult(String result, StringReader context, boolean isKey, boolean isQuoted) {
		List<String> relaventList = (isKey ? keyList : valueList).results;

		String message;
		if ((isKey? keyListEmpty : valueListEmpty) || relaventList.contains(result)) {
			// Either:
			//  The lists are empty, so this method call came because the given sets found a duplicate
			//  Or it used to be in the list and was removed from the local copy
			// Therefore, the result was a duplicate when duplicates were not allowed
			message = "Duplicate " + (isKey ? "keys" : "values") + " are not allowed!";
		} else {
			// Result was invalid because it was never part of the allowed set in the first place
			message = "Invalid " + (isKey ? "key" : "value") + ": " + result;
		}

		// Reset cursor to underline entire invalid result, adjusting for quotes
		context.setCursor(context.getCursor() - result.length() - (isQuoted ? 2 : 0));
		return CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(context, message);
	}

	private String separatorRequiredMessage(boolean isKey) {
		return (isKey ? "Delimiter \"" + delimiter : "Separator \"" + separator) +
			"\" required after writing a " +
			(isKey ? "key" : "value");
	}

	private CommandSyntaxException handleParserException(Exception e, String result, StringReader context, boolean isKey, boolean isQuoted) {
		// Reset cursor to underline entire invalid result, adjusting for quotes
		context.setCursor(context.getCursor() - result.length() - (isQuoted ? 2 : 0));

		Message message;
		if (e instanceof WrapperCommandSyntaxException wCSE) message = wCSE.getRawMessage();
		else message = new LiteralMessage(
			"Invalid " + (isKey ? "key" : "value") + " (" + result + "): cannot be converted to a " + (isKey ? "key" : "value")
		);

		return CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException().createWithContext(context, message);
	}

	private record ResultList(List<String> results, Map<String, String> unquoted, Map<String, String> quoted,
							  Map<String, String> preferredUnquoted, Map<String, String> preferredQuoted) {
		public static ResultList EMPTY = new ResultList(List.of(), Map.of(), Map.of(), Map.of(), Map.of());

		private static ResultList formatResults(List<String> results, String terminator) {
			// Format results and sort for suggestions
			if (results == null) return EMPTY;

			char firstTerminatorChar = terminator.charAt(0);

			Map<String, String> unquoted = new HashMap<>();
			Map<String, String> quoted = new HashMap<>();
			Map<String, String> preferredUnquoted = new HashMap<>();
			Map<String, String> preferredQuoted = new HashMap<>();

			for (String result : results) {
				// Figure out escape sequences that would produce result if quoted or unquoted
				StringBuilder unquotedResult = new StringBuilder();
				StringBuilder quotedResult = new StringBuilder();
				boolean preferUnquoted = true; // Prefer the simplest by default, which is unquoted

				StringReader reader = new StringReader(result);
				while (reader.canRead()) {
					char c = reader.read();
					boolean escapeUnquoted = false;
					boolean escapeQuoted = false;

					// Determine where escape is needed
					if (c == '\\') {
						// \ is always escaped
						escapeUnquoted = true;
						escapeQuoted = true;
					} else if (c == '"') {
						// " is only escaped when in a quote
						escapeQuoted = true;
					} else checkTerminator:if (c == firstTerminatorChar) {
						// Check if this is really the start of the terminator
						if (!reader.canRead(terminator.length() - 1))
							break checkTerminator; // Not long enough to fit terminator

						int i = 1;
						while (i < terminator.length()) {
							if (reader.peek(i - 1) != terminator.charAt(i)) {
								// wasn't actually the terminator, no escape necessary
								break checkTerminator;
							}
							i++;
						}

						// Yes, this was the terminator. We need to escape it when unquoted
						escapeUnquoted = true;
						// If the result dose contain the separator, we would prefer it be quoted
						preferUnquoted = false;
					}

					// Add the character, escaping if deemed necessary
					if (escapeUnquoted) unquotedResult.append('\\');
					unquotedResult.append(c);

					if (escapeQuoted) quotedResult.append('\\');
					quotedResult.append(c);
				}

				// Update lists
				unquoted.put(result, unquotedResult.toString());
				quoted.put(result, quotedResult.toString());

				if (preferUnquoted)
					preferredUnquoted.put(result, unquotedResult.toString());
				else
					preferredQuoted.put(result, quotedResult.toString());
			}

			return new ResultList(results, unquoted, quoted, preferredUnquoted, preferredQuoted);
		}
	}
}