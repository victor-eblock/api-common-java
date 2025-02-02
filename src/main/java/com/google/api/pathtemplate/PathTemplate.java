/*
 * Copyright 2016, Google Inc.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *     * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *     * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.google.api.pathtemplate;

import com.google.auto.value.AutoValue;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import jakarta.annotation.Nullable;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a path template.
 *
 * <p>
 * Templates use the syntax of the API platform; see the protobuf of HttpRule for details. A
 * template consists of a sequence of literals, wildcards, and variable bindings, where each binding
 * can have a sub-path. A string representation can be parsed into an instance of
 * {@link PathTemplate}, which can then be used to perform matching and instantiation.
 *
 * <p>
 * Matching and instantiation deals with unescaping and escaping using URL encoding rules. For
 * example, if a template variable for a single segment is instantiated with a string like
 * {@code "a/b"}, the slash will be escaped to {@code "%2f"}. (Note that slash will not be escaped
 * for a multiple-segment variable, but other characters will). The literals in the template itself
 * are <em>not</em> escaped automatically, and must be already URL encoded.
 *
 * <p>
 * Here is an example for a template using simple variables:
 *
 * <pre>{@code
 *   PathTemplate template = PathTemplate.create("v1/shelves/{shelf}/books/{book}");
 *   assert template.matches("v2/shelves") == false;
 *   Map&lt;String, String&gt; values = template.match("v1/shelves/s1/books/b1");
 *   Map&lt;String, String&gt; expectedValues = new HashMap&lt;&gt;();
 *   expectedValues.put("shelf", "s1");
 *   expectedValues.put("book", "b1");
 *   assert values.equals(expectedValues);
 *   assert template.instantiate(values).equals("v1/shelves/s1/books/b1");
 * }</pre>
 *
 * Templates can use variables which match sub-paths. Example:
 *
 * <pre>{@code
 *   PathTemplate template = PathTemplate.create("v1/{name=shelves/*&#47;books/*}"};
 *   assert template.match("v1/shelves/books/b1") == null;
 *   Map&lt;String, String&gt; expectedValues = new HashMap&lt;&gt;();
 *   expectedValues.put("name", "shelves/s1/books/b1");
 *   assert template.match("v1/shelves/s1/books/b1").equals(expectedValues);
 * }</pre>
 *
 * Path templates can also be used with only wildcards. Each wildcard is associated with an implicit
 * variable {@code $n}, where n is the zero-based position of the wildcard. Example:
 *
 * <pre>{@code
 *   PathTemplate template = PathTemplate.create("shelves/*&#47;books/*"};
 *   assert template.match("shelves/books/b1") == null;
 *   Map&lt;String, String&gt; values = template.match("v1/shelves/s1/books/b1");
 *   Map&lt;String, String&gt; expectedValues = new HashMap&lt;&gt;();
 *   expectedValues.put("$0", s1");
 *   expectedValues.put("$1", "b1");
 *   assert values.equals(expectedValues);
 * }</pre>
 *
 * Paths input to matching can use URL relative syntax to indicate a host name by prefixing the host
 * name, as in {@code //somewhere.io/some/path}. The host name is matched into the special variable
 * {@link #HOSTNAME_VAR}. Patterns are agnostic about host names, and the same pattern can be used
 * for URL relative syntax and simple path syntax:
 *
 * <pre>{@code
 *   PathTemplate template = PathTemplate.create("shelves/*"};
 *   Map&lt;String, String&gt; expectedValues = new HashMap&lt;&gt;();
 *   expectedValues.put(PathTemplate.HOSTNAME_VAR, "//somewhere.io");
 *   expectedValues.put("$0", s1");
 *   assert template.match("//somewhere.io/shelves/s1").equals(expectedValues);
 *   expectedValues.clear();
 *   expectedValues.put("$0", s1");
 *   assert template.match("shelves/s1").equals(expectedValues);
 * }</pre>
 *
 * For the representation of a <em>resource name</em> see {@link TemplatedResourceName}, which is
 * based on path templates.
 */
public class PathTemplate {

  /**
   * A constant identifying the special variable used for endpoint bindings in the result of
   * {@link #matchFromFullName(String)}. It may also contain protocol string, if its provided in the
   * input.
   */
  public static final String HOSTNAME_VAR = "$hostname";

  // A regexp to match a custom verb at the end of a path.
  private static final Pattern CUSTOM_VERB_PATTERN = Pattern.compile(":([^/*}{=]+)$");

  // A regex to match a hostname with or without protocol.
  private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^(\\w+:)?//");

  // A splitter on slash.
  private static final Splitter SLASH_SPLITTER = Splitter.on('/').trimResults();

  // A regex to match the valid complex resource ID delimiters.
  private static final Pattern COMPLEX_DELIMITER_PATTERN = Pattern.compile("[_\\-\\.~]");

  // A regex to match multiple complex resource ID delimiters.
  private static final Pattern MULTIPLE_COMPLEX_DELIMITER_PATTERN =
      Pattern.compile("\\}[_\\-\\.~]{2,}\\{");

  // A regex to match a missing complex resource ID delimiter.
  private static final Pattern MISSING_COMPLEX_DELIMITER_PATTERN = Pattern.compile("\\}\\{");

  // A regex to match invalid complex resource ID delimiters.
  private static final Pattern INVALID_COMPLEX_DELIMITER_PATTERN =
      Pattern.compile("\\}[^_\\-\\.~]\\{");

  // A regex to match a closing segment (end brace) followed by one complex resource ID delimiter.
  private static final Pattern END_SEGMENT_COMPLEX_DELIMITER_PATTERN =
      Pattern.compile("\\}[_\\-\\.~]{1}");

  // Helper Types
  // ============

  /** Specifies a path segment kind. */
  enum SegmentKind {
    /** A literal path segment. */
    LITERAL,

    /** A custom verb. Can only appear at the end of path. */
    CUSTOM_VERB,

    /** A simple wildcard ('*'). */
    WILDCARD,

    /** A path wildcard ('**'). */
    PATH_WILDCARD,

    /** A field binding start. */
    BINDING,

    /** A field binding end. */
    END_BINDING,
  }

  /** Specifies a path segment. */
  @AutoValue
  abstract static class Segment {

    /** A constant for the WILDCARD segment. */
    private static final Segment WILDCARD = create(SegmentKind.WILDCARD, "*");

    /** A constant for the PATH_WILDCARD segment. */
    private static final Segment PATH_WILDCARD = create(SegmentKind.PATH_WILDCARD, "**");

    /** A constant for the END_BINDING segment. */
    private static final Segment END_BINDING = create(SegmentKind.END_BINDING, "");

    /** Creates a segment of given kind and value. */
    private static Segment create(SegmentKind kind, String value) {
      return new AutoValue_PathTemplate_Segment(kind, value, "");
    }

    /** Creates a segment of given kind, value, and complex separator. */
    private static Segment create(SegmentKind kind, String value, String complexSeparator) {
      return new AutoValue_PathTemplate_Segment(kind, value, complexSeparator);
    }

    private static Segment wildcardCreate(String complexSeparator) {
      return new AutoValue_PathTemplate_Segment(
          SegmentKind.WILDCARD,
          "*",
          !complexSeparator.isEmpty() && COMPLEX_DELIMITER_PATTERN.matcher(complexSeparator).find()
              ? complexSeparator
              : "");
    }

    /** The path segment kind. */
    abstract SegmentKind kind();

    /**
     * The value for the segment. For literals, custom verbs, and wildcards, this reflects the value
     * as it appears in the template. For bindings, this represents the variable of the binding.
     */
    abstract String value();

    abstract String complexSeparator();

    /** Returns true of this segment is one of the wildcards, */
    boolean isAnyWildcard() {
      return kind() == SegmentKind.WILDCARD || kind() == SegmentKind.PATH_WILDCARD;
    }

    String separator() {
      switch (kind()) {
        case CUSTOM_VERB:
          return ":";
        case END_BINDING:
          return "";
        default:
          return "/";
      }
    }
  }

  /**
   * Creates a path template from a string. The string must satisfy the syntax of path templates of
   * the API platform; see HttpRule's proto source.
   *
   * @throws ValidationException if there are errors while parsing the template.
   */
  public static PathTemplate create(String template) {
    return create(template, true);
  }

  /**
   * Creates a path template from a string. The string must satisfy the syntax of path templates of
   * the API platform; see HttpRule's proto source. Url encoding of template variables is disabled.
   *
   * @throws ValidationException if there are errors while parsing the template.
   */
  public static PathTemplate createWithoutUrlEncoding(String template) {
    return create(template, false);
  }

  private static PathTemplate create(String template, boolean urlEncoding) {
    return new PathTemplate(parseTemplate(template), urlEncoding);
  }

  // Instance State and Methods
  // ==========================

  // List of segments of this template.
  private final ImmutableList<Segment> segments;

  // Map from variable names to bindings in the template.
  private final ImmutableMap<String, Segment> bindings;

  // Control use of URL encoding
  private final boolean urlEncoding;

  private PathTemplate(Iterable<Segment> segments, boolean urlEncoding) {
    this.segments = ImmutableList.copyOf(segments);
    if (this.segments.isEmpty()) {
      throw new ValidationException("template cannot be empty.");
    }
    Map<String, Segment> bindings = Maps.newLinkedHashMap();
    for (Segment seg : this.segments) {
      if (seg.kind() == SegmentKind.BINDING) {
        if (bindings.containsKey(seg.value())) {
          throw new ValidationException("Duplicate binding '%s'", seg.value());
        }
        bindings.put(seg.value(), seg);
      }
    }
    this.bindings = ImmutableMap.copyOf(bindings);
    this.urlEncoding = urlEncoding;
  }

  /** Returns the set of variable names used in the template. */
  public Set<String> vars() {
    return bindings.keySet();
  }

  /**
   * Returns a template for the parent of this template.
   *
   * @throws ValidationException if the template has no parent.
   */
  public PathTemplate parentTemplate() {
    int i = segments.size();
    Segment seg = segments.get(--i);
    if (seg.kind() == SegmentKind.END_BINDING) {
      while (i > 0 && segments.get(--i).kind() != SegmentKind.BINDING) {}
    }
    if (i == 0) {
      throw new ValidationException("template does not have a parent");
    }
    return new PathTemplate(segments.subList(0, i), urlEncoding);
  }

  /**
   * Returns a template where all variable bindings have been replaced by wildcards, but which is
   * equivalent regards matching to this one.
   */
  public PathTemplate withoutVars() {
    StringBuilder result = new StringBuilder();
    ListIterator<Segment> iterator = segments.listIterator();
    boolean start = true;
    while (iterator.hasNext()) {
      Segment seg = iterator.next();
      switch (seg.kind()) {
        case BINDING:
        case END_BINDING:
          break;
        default:
          if (!start) {
            result.append(seg.separator());
          } else {
            start = false;
          }
          result.append(seg.value());
      }
    }
    return create(result.toString(), urlEncoding);
  }

  /**
   * Returns a path template for the sub-path of the given variable. Example:
   *
   * <pre>{@code
   *   PathTemplate template = PathTemplate.create("v1/{name=shelves/*&#47;books/*}");
   *   assert template.subTemplate("name").toString().equals("shelves/*&#47;books/*");
   * }</pre>
   *
   * The returned template will never have named variables, but only wildcards, which are dealt with
   * in matching and instantiation using '$n'-variables. See the documentation of
   * {@link #match(String)} and {@link #instantiate(Map)}, respectively.
   *
   * <p>
   * For a variable which has no sub-path, this returns a path template with a single wildcard
   * ('*').
   *
   * @throws ValidationException if the variable does not exist in the template.
   */
  public PathTemplate subTemplate(String varName) {
    List<Segment> sub = Lists.newArrayList();
    boolean inBinding = false;
    for (Segment seg : segments) {
      if (seg.kind() == SegmentKind.BINDING && seg.value().equals(varName)) {
        inBinding = true;
      } else if (inBinding) {
        if (seg.kind() == SegmentKind.END_BINDING) {
          return PathTemplate.create(toSyntax(sub, true), urlEncoding);
        } else {
          sub.add(seg);
        }
      }
    }
    throw new ValidationException(
        String.format("Variable '%s' is undefined in template '%s'", varName, this.toRawString()));
  }

  /** Returns true of this template ends with a literal. */
  public boolean endsWithLiteral() {
    return segments.get(segments.size() - 1).kind() == SegmentKind.LITERAL;
  }

  /** Returns true of this template ends with a custom verb. */
  public boolean endsWithCustomVerb() {
    return segments.get(segments.size() - 1).kind() == SegmentKind.CUSTOM_VERB;
  }

  /**
   * Creates a resource name from this template and a path.
   *
   * @throws ValidationException if the path does not match the template.
   */
  public TemplatedResourceName parse(String path) {
    return TemplatedResourceName.create(this, path);
  }

  /**
   * Returns the name of a singleton variable used by this template. If the template does not
   * contain a single variable, returns null.
   */
  @Nullable
  public String singleVar() {
    if (bindings.size() == 1) {
      return bindings.entrySet().iterator().next().getKey();
    }
    return null;
  }

  // Template Matching
  // =================

  /**
   * Throws a ValidationException if the template doesn't match the path. The exceptionMessagePrefix
   * parameter will be prepended to the ValidationException message.
   */
  public void validate(String path, String exceptionMessagePrefix) {
    if (!matches(path)) {
      throw new ValidationException(
          String.format(
              "%s: Parameter \"%s\" must be in the form \"%s\"",
              exceptionMessagePrefix,
              path,
              this.toString()));
    }
  }

  /**
   * Matches the path, returning a map from variable names to matched values. All matched values
   * will be properly unescaped using URL encoding rules. If the path does not match the template,
   * throws a ValidationException. The exceptionMessagePrefix parameter will be prepended to the
   * ValidationException message.
   *
   * <p>
   * If the path starts with '//', the first segment will be interpreted as a host name and stored
   * in the variable {@link #HOSTNAME_VAR}.
   *
   * <p>
   * See the {@link PathTemplate} class documentation for examples.
   *
   * <p>
   * For free wildcards in the template, the matching process creates variables named '$n', where
   * 'n' is the wildcard's position in the template (starting at n=0). For example:
   *
   * <pre>{@code
   *   PathTemplate template = PathTemplate.create("shelves/*&#47;books/*");
   *   Map&lt;String, String&gt; expectedValues = new HashMap&lt;&gt;();
   *   expectedValues.put("$0", "s1");
   *   expectedValues.put("$1", "b1");
   *   assert template.validatedMatch("shelves/s1/books/b2", "User exception string")
   *              .equals(expectedValues);
   *   expectedValues.clear();
   *   expectedValues.put(HOSTNAME_VAR, "//somewhere.io");
   *   expectedValues.put("$0", "s1");
   *   expectedValues.put("$1", "b1");
   *   assert template.validatedMatch("//somewhere.io/shelves/s1/books/b2", "User exception string")
   *              .equals(expectedValues);
   * }</pre>
   *
   * All matched values will be properly unescaped using URL encoding rules (so long as URL encoding
   * has not been disabled by the {@link #createWithoutUrlEncoding} method).
   */
  public Map<String, String> validatedMatch(String path, String exceptionMessagePrefix) {
    Map<String, String> matchMap = match(path);
    if (matchMap == null) {
      throw new ValidationException(
          String.format(
              "%s: Parameter \"%s\" must be in the form \"%s\"",
              exceptionMessagePrefix,
              path,
              this.toString()));
    }
    return matchMap;
  }

  /** Returns true if the template matches the path. */
  public boolean matches(String path) {
    return match(path) != null;
  }

  /**
   * Matches the path, returning a map from variable names to matched values. All matched values
   * will be properly unescaped using URL encoding rules. If the path does not match the template,
   * null is returned.
   *
   * <p>
   * If the path starts with '//', the first segment will be interpreted as a host name and stored
   * in the variable {@link #HOSTNAME_VAR}.
   *
   * <p>
   * See the {@link PathTemplate} class documentation for examples.
   *
   * <p>
   * For free wildcards in the template, the matching process creates variables named '$n', where
   * 'n' is the wildcard's position in the template (starting at n=0). For example:
   *
   * <pre>{@code
   *   PathTemplate template = PathTemplate.create("shelves/*&#47;books/*");
   *   Map&lt;String, String&gt; expectedValues = new HashMap&lt;&gt;();
   *   expectedValues.put("$0", "s1");
   *   expectedValues.put("$1", "b1");
   *   assert template.match("shelves/s1/books/b2").equals(expectedValues);
   *   expectedValues.clear();
   *   expectedValues.put(HOSTNAME_VAR, "//somewhere.io");
   *   expectedValues.put("$0", "s1");
   *   expectedValues.put("$1", "b1");
   *   assert template.match("//somewhere.io/shelves/s1/books/b2").equals(expectedValues);
   * }</pre>
   *
   * All matched values will be properly unescaped using URL encoding rules (so long as URL encoding
   * has not been disabled by the {@link #createWithoutUrlEncoding} method).
   */
  @Nullable
  public Map<String, String> match(String path) {
    return match(path, false);
  }

  /**
   * Matches the path, where the first segment is interpreted as the host name regardless of whether
   * it starts with '//' or not. Example:
   *
   * <pre>{@code
   *   Map&lt;String, String&gt; expectedValues = new HashMap&lt;&gt;();
   *   expectedValues.put(HOSTNAME_VAR, "//somewhere.io");
   *   expectedValues.put("name", "shelves/s1");
   *   assert template("{name=shelves/*}").matchFromFullName("somewhere.io/shelves/s1")
   *            .equals(expectedValues);
   * }</pre>
   */
  @Nullable
  public Map<String, String> matchFromFullName(String path) {
    return match(path, true);
  }

  // Matches a path.
  private Map<String, String> match(String path, boolean forceHostName) {
    // Quick check for trailing custom verb.
    Segment last = segments.get(segments.size() - 1);
    if (last.kind() == SegmentKind.CUSTOM_VERB) {
      Matcher matcher = CUSTOM_VERB_PATTERN.matcher(path);
      if (!matcher.find() || !decodeUrl(matcher.group(1)).equals(last.value())) {
        return null;
      }
      path = path.substring(0, matcher.start(0));
    }

    Matcher matcher = HOSTNAME_PATTERN.matcher(path);
    boolean withHostName = matcher.find();
    if (withHostName) {
      path = matcher.replaceFirst("");
    }
    List<String> input = SLASH_SPLITTER.splitToList(path);
    int inPos = 0;
    Map<String, String> values = Maps.newLinkedHashMap();
    if (withHostName || forceHostName) {
      if (input.isEmpty()) {
        return null;
      }
      String hostName = input.get(inPos++);
      if (withHostName) {
        // Put the // back, so we can distinguish this case from forceHostName.
        hostName = matcher.group(0) + hostName;
      }
      values.put(HOSTNAME_VAR, hostName);
    }
    if (withHostName) {
      inPos = alignInputToAlignableSegment(input, inPos, segments.get(0));
    }
    if (!match(input, inPos, segments, 0, values)) {
      return null;
    }
    return ImmutableMap.copyOf(values);
  }

  // Aligns input to start of literal value of literal or binding segment if input contains
  // hostname.
  private int alignInputToAlignableSegment(List<String> input, int inPos, Segment segment) {
    switch (segment.kind()) {
      case BINDING:
        inPos = alignInputPositionToLiteral(input, inPos, segment.value() + "s");
        return inPos + 1;
      case LITERAL:
        return alignInputPositionToLiteral(input, inPos, segment.value());
    }
    return inPos;
  }

  // Aligns input to start of literal value if input contains hostname.
  private int alignInputPositionToLiteral(
      List<String> input, int inPos, String literalSegmentValue) {
    for (; inPos < input.size(); inPos++) {
      if (literalSegmentValue.equals(input.get(inPos))) {
        return inPos;
      }
    }
    return inPos;
  }

  // Tries to match the input based on the segments at given positions. Returns a boolean
  // indicating whether the match was successful.
  private boolean match(
      List<String> input,
      int inPos,
      List<Segment> segments,
      int segPos,
      Map<String, String> values) {
    String currentVar = null;
    List<String> modifiableInput = new ArrayList<>(input);
    while (segPos < segments.size()) {
      Segment seg = segments.get(segPos++);
      switch (seg.kind()) {
        case END_BINDING:
          // End current variable binding scope.
          currentVar = null;
          continue;
        case BINDING:
          // Start variable binding scope.
          currentVar = seg.value();
          continue;
        case CUSTOM_VERB:
          // This is the final segment, and this check should have already been performed by the
          // caller. The matching value is no longer present in the input.
          break;
        case LITERAL:
        case WILDCARD:
          if (inPos >= modifiableInput.size()) {
            // End of input
            return false;
          }
          // Check literal match.
          String next = decodeUrl(modifiableInput.get(inPos++));
          if (seg.kind() == SegmentKind.LITERAL) {
            if (!seg.value().equals(next)) {
              // Literal does not match.
              return false;
            }
          }
          if (seg.kind() == SegmentKind.WILDCARD && !seg.complexSeparator().isEmpty()) {
            // Parse the complex resource separators one by one.
            int complexSeparatorIndex = next.indexOf(seg.complexSeparator());
            if (complexSeparatorIndex >= 0) {
              modifiableInput.add(inPos, next.substring(complexSeparatorIndex + 1));
              next = next.substring(0, complexSeparatorIndex);
              modifiableInput.set(inPos - 1, next);
            } else {
              // No complex resource ID separator found in the literal when we expected one.
              return false;
            }
          }
          if (currentVar != null) {
            // Create or extend current match
            values.put(currentVar, concatCaptures(values.get(currentVar), next));
          }
          break;
        case PATH_WILDCARD:
          // Compute the number of additional input the ** can consume. This
          // is possible because we restrict patterns to have only one **.
          int segsToMatch = 0;
          for (int i = segPos; i < segments.size(); i++) {
            switch (segments.get(i).kind()) {
              case BINDING:
              case END_BINDING:
              case CUSTOM_VERB:
                // These segments do not actually consume any input.
                continue;
              default:
                segsToMatch++;
            }
          }
          int available = (modifiableInput.size() - inPos) - segsToMatch;
          // If this segment is empty, make sure it is still captured.
          if (available == 0 && !values.containsKey(currentVar)) {
            values.put(currentVar, "");
          }
          while (available-- > 0) {
            values.put(
                currentVar,
                concatCaptures(values.get(currentVar), decodeUrl(modifiableInput.get(inPos++))));
          }
      }
    }
    return inPos == modifiableInput.size();
  }

  private static String concatCaptures(@Nullable String cur, String next) {
    return cur == null ? next : cur + "/" + next;
  }

  // Template Instantiation
  // ======================

  /**
   * Instantiate the template based on the given variable assignment. Performs proper URL escaping
   * of variable assignments.
   *
   * <p>
   * Note that free wildcards in the template must have bindings of '$n' variables, where 'n' is the
   * position of the wildcard (starting at 0). See the documentation of {@link #match(String)} for
   * details.
   *
   * @throws ValidationException if a variable occurs in the template without a binding.
   */
  public String instantiate(Map<String, String> values) {
    return instantiate(values, false);
  }

  /** Shortcut for {@link #instantiate(Map)} with a vararg parameter for keys and values. */
  public String instantiate(String... keysAndValues) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      builder.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return instantiate(builder.build());
  }

  /**
   * Same like {@link #instantiate(Map)} but allows for unbound variables, which are substituted
   * using their original syntax. Example:
   *
   * <pre>{@code
   *   PathTemplate template = PathTemplate.create("v1/shelves/{shelf}/books/{book}");
   *   Map&lt;String, String&gt; partialMap = new HashMap&lt;&gt;();
   *   partialMap.put("shelf", "s1");
   *   assert template.instantiatePartial(partialMap).equals("v1/shelves/s1/books/{book}");
   * }</pre>
   *
   * The result of this call can be used to create a new template.
   */
  public String instantiatePartial(Map<String, String> values) {
    return instantiate(values, true);
  }

  private String instantiate(Map<String, String> values, boolean allowPartial) {
    StringBuilder result = new StringBuilder();
    if (values.containsKey(HOSTNAME_VAR)) {
      result.append(values.get(HOSTNAME_VAR));
      result.append('/');
    }
    boolean continueLast = true; // Whether to not append separator
    boolean skip = false; // Whether we are substituting a binding and segments shall be skipped.
    ListIterator<Segment> iterator = segments.listIterator();
    String prevSeparator = "";
    while (iterator.hasNext()) {
      Segment seg = iterator.next();
      if (!skip && !continueLast) {
        String separator =
            prevSeparator.isEmpty() || !iterator.hasNext() ? seg.separator() : prevSeparator;
        result.append(separator);
        prevSeparator = seg.complexSeparator().isEmpty() ? seg.separator() : seg.complexSeparator();
      }
      continueLast = false;
      switch (seg.kind()) {
        case BINDING:
          String var = seg.value();
          String value = values.get(seg.value());
          if (value == null) {
            if (!allowPartial) {
              throw new ValidationException(
                  String.format("Unbound variable '%s'. Bindings: %s", var, values));
            }
            // Append pattern to output
            if (var.startsWith("$")) {
              // Eliminate positional variable.
              result.append(iterator.next().value());
              iterator.next();
              continue;
            }
            result.append('{');
            result.append(seg.value());
            result.append('=');
            continueLast = true;
            continue;
          }
          Segment next = iterator.next();
          Segment nextNext = iterator.next();
          boolean pathEscape =
              next.kind() == SegmentKind.PATH_WILDCARD
                  || nextNext.kind() != SegmentKind.END_BINDING;
          restore(iterator, iterator.nextIndex() - 2);
          if (!pathEscape) {
            result.append(encodeUrl(value));
          } else {
            // For a path wildcard or path of length greater 1, split the value and escape
            // every sub-segment.
            boolean first = true;
            for (String subSeg : SLASH_SPLITTER.split(value)) {
              if (!first) {
                result.append('/');
              }
              first = false;
              result.append(encodeUrl(subSeg));
            }
          }
          skip = true;
          continue;
        case END_BINDING:
          if (!skip) {
            result.append('}');
          }
          skip = false;
          continue;
        default:
          if (!skip) {
            result.append(seg.value());
          }
      }
    }
    return result.toString();
  }

  // Positional Matching and Instantiation
  // =====================================

  /**
   * Instantiates the template from the given positional parameters. The template must not be build
   * from named bindings, but only contain wildcards. Each parameter position corresponds to a
   * wildcard of the according position in the template.
   */
  public String encode(String... values) {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
    int i = 0;
    for (String value : values) {
      builder.put("$" + i++, value);
    }
    // We will get an error if there are named bindings which are not reached by values.
    return instantiate(builder.build());
  }

  /**
   * Matches the template into a list of positional values. The template must not be build from
   * named bindings, but only contain wildcards. For each wildcard in the template, a value is
   * returned at corresponding position in the list.
   */
  public List<String> decode(String path) {
    Map<String, String> match = match(path);
    if (match == null) {
      throw new IllegalArgumentException(
          String.format("template '%s' does not match '%s'", this, path));
    }
    List<String> result = Lists.newArrayList();
    for (Map.Entry<String, String> entry : match.entrySet()) {
      String key = entry.getKey();
      if (!key.startsWith("$")) {
        throw new IllegalArgumentException("template must not contain named bindings");
      }
      int i = Integer.parseInt(key.substring(1));
      while (result.size() <= i) {
        result.add("");
      }
      result.set(i, entry.getValue());
    }
    return ImmutableList.copyOf(result);
  }

  // Template Parsing
  // ================

  private static ImmutableList<Segment> parseTemplate(String template) {
    // Skip useless leading slash.
    if (template.startsWith("/")) {
      template = template.substring(1);
    }

    // Extract trailing custom verb.
    Matcher matcher = CUSTOM_VERB_PATTERN.matcher(template);
    String customVerb = null;
    if (matcher.find()) {
      customVerb = matcher.group(1);
      template = template.substring(0, matcher.start(0));
    }

    ImmutableList.Builder<Segment> builder = ImmutableList.builder();
    String varName = null;
    int freeWildcardCounter = 0;
    int pathWildCardBound = 0;

    for (String seg : Splitter.on('/').trimResults().split(template)) {
      // Handle _deleted-topic_ for PubSub.
      if (seg.equals("_deleted-topic_")) {
        builder.add(Segment.create(SegmentKind.LITERAL, seg));
        continue;
      }

      boolean isLastSegment = (template.indexOf(seg) + seg.length()) == template.length();
      boolean isCollectionWildcard = !isLastSegment && (seg.equals("-") || seg.equals("-}"));
      if (!isCollectionWildcard && isSegmentBeginOrEndInvalid(seg)) {
        throw new ValidationException("parse error: invalid begin or end character in '%s'", seg);
      }
      // Disallow zero or multiple delimiters between variable names.
      if (MULTIPLE_COMPLEX_DELIMITER_PATTERN.matcher(seg).find()
          || MISSING_COMPLEX_DELIMITER_PATTERN.matcher(seg).find()) {
        throw new ValidationException(
            "parse error: missing or 2+ consecutive delimiter characters in '%s'", seg);
      }
      // If segment starts with '{', a binding group starts.
      boolean bindingStarts = seg.startsWith("{");
      boolean implicitWildcard = false;
      boolean complexDelimiterFound = false;
      if (bindingStarts) {
        if (varName != null) {
          throw new ValidationException("parse error: nested binding in '%s'", template);
        }
        seg = seg.substring(1);

        // Check for invalid complex resource ID delimiters.
        if (INVALID_COMPLEX_DELIMITER_PATTERN.matcher(seg).find()) {
          throw new ValidationException(
              "parse error: invalid complex resource ID delimiter character in '%s'", seg);
        }

        Matcher complexPatternDelimiterMatcher = END_SEGMENT_COMPLEX_DELIMITER_PATTERN.matcher(seg);
        complexDelimiterFound = !isCollectionWildcard && complexPatternDelimiterMatcher.find();

        // Look for complex resource names.
        // Need to handle something like "{user_a}~{user_b}".
        if (complexDelimiterFound) {
          builder.addAll(parseComplexResourceId(seg));
        } else {
          int i = seg.indexOf('=');
          if (i <= 0) {
            // Possibly looking at something like "{name}" with implicit wildcard.
            if (seg.endsWith("}")) {
              // Remember to add an implicit wildcard later.
              implicitWildcard = true;
              varName = seg.substring(0, seg.length() - 1).trim();
              seg = seg.substring(seg.length() - 1).trim();
            } else {
              throw new ValidationException(
                  "parse error: invalid binding syntax in '%s'", template);
            }
          } else if (seg.indexOf('-') <= 0 && isCollectionWildcard) {
            implicitWildcard = true;
          } else {
            // Looking at something like "{name=wildcard}".
            varName = seg.substring(0, i).trim();
            seg = seg.substring(i + 1).trim();
          }
          builder.add(Segment.create(SegmentKind.BINDING, varName));
        }
      }

      if (!complexDelimiterFound) {
        // If segment ends with '}', a binding group ends. Remove the brace and remember.
        boolean bindingEnds = seg.endsWith("}");
        if (bindingEnds) {
          seg = seg.substring(0, seg.length() - 1).trim();
        }

        // Process the segment, after stripping off "{name=.." and "..}".
        switch (seg) {
          case "**":
          case "*":
            if ("**".equals(seg)) {
              pathWildCardBound++;
            }
            Segment wildcard = seg.length() == 2 ? Segment.PATH_WILDCARD : Segment.WILDCARD;
            if (varName == null) {
              // Not in a binding, turn wildcard into implicit binding.
              // "*" => "{$n=*}"
              builder.add(Segment.create(SegmentKind.BINDING, "$" + freeWildcardCounter));
              freeWildcardCounter++;
              builder.add(wildcard);
              builder.add(Segment.END_BINDING);
            } else {
              builder.add(wildcard);
            }
            break;
          case "":
            if (!bindingEnds) {
              throw new ValidationException(
                  "parse error: empty segment not allowed in '%s'", template);
            }
            // If the wildcard is implicit, seg will be empty. Just continue.
            break;
          case "-":
            builder.add(Segment.WILDCARD);
            implicitWildcard = false;
            break;
          default:
            builder.add(Segment.create(SegmentKind.LITERAL, seg));
        }

        // End a binding.
        if (bindingEnds && !complexDelimiterFound) {
          // Reset varName to null for next binding.
          varName = null;

          if (implicitWildcard) {
            // Looking at something like "{var}". Insert an implicit wildcard, as it is the same
            // as "{var=*}".
            builder.add(Segment.WILDCARD);
          }
          builder.add(Segment.END_BINDING);
        }

        if (pathWildCardBound > 1) {
          // Report restriction on number of '**' in the pattern. There can be only one, which
          // enables non-backtracking based matching.
          throw new ValidationException(
              "parse error: pattern must not contain more than one path wildcard ('**') in '%s'",
              template);
        }
      }
    }

    if (customVerb != null) {
      builder.add(Segment.create(SegmentKind.CUSTOM_VERB, customVerb));
    }
    return builder.build();
  }

  private static boolean isSegmentBeginOrEndInvalid(String seg) {
    // A segment is invalid if it contains only one character and the character is a delimiter
    if (seg.length() == 1 && COMPLEX_DELIMITER_PATTERN.matcher(seg).find()) {
      return true;
    }
    // A segment can start with a delimiter, as long as there is no { right after it.
    // A segment can end with a delimiter, as long as there is no } right before it.
    // e.g. Invalid: .{well}-{known}, {well}-{known}-
    // Valid: .well-known, .well-{known}, .-~{well-known}, these segments are all considered as literals for matching
    return (COMPLEX_DELIMITER_PATTERN.matcher(seg.substring(0, 1)).find() && seg.charAt(1) == '{')
        || (COMPLEX_DELIMITER_PATTERN.matcher(seg.substring(seg.length() - 1)).find()
            && seg.charAt(seg.length() - 2) == '}');
  }

  private static List<Segment> parseComplexResourceId(String seg) {
    List<Segment> segments = new ArrayList<>();
    List<String> separatorIndices = new ArrayList<>();

    Matcher complexPatternDelimiterMatcher = END_SEGMENT_COMPLEX_DELIMITER_PATTERN.matcher(seg);
    boolean delimiterFound = complexPatternDelimiterMatcher.find();

    while (delimiterFound) {
      int delimiterIndex = complexPatternDelimiterMatcher.start();
      if (seg.substring(delimiterIndex).startsWith("}")) {
        delimiterIndex += 1;
      }
      String currDelimiter = seg.substring(delimiterIndex, delimiterIndex + 1);
      if (!COMPLEX_DELIMITER_PATTERN.matcher(currDelimiter).find()) {
        throw new ValidationException(
            "parse error: invalid complex ID delimiter '%s' in '%s'", currDelimiter, seg);
      }
      separatorIndices.add(currDelimiter);
      delimiterFound = complexPatternDelimiterMatcher.find(delimiterIndex + 1);
    }
    // The last entry does not have a delimiter.
    separatorIndices.add("");

    String subVarName = null;
    Iterable<String> complexSubsegments =
        Splitter.onPattern("\\}[_\\-\\.~]").trimResults().split(seg);
    boolean complexSegImplicitWildcard = false;
    int currIteratorIndex = 0;
    for (String complexSeg : complexSubsegments) {
      boolean subsegmentBindingStarts = complexSeg.startsWith("{");
      if (subsegmentBindingStarts) {
        if (subVarName != null) {
          throw new ValidationException("parse error: nested binding in '%s'", complexSeg);
        }
        complexSeg = complexSeg.substring(1);
      }
      subVarName = complexSeg.trim();

      boolean subBindingEnds = complexSeg.endsWith("}");
      int i = complexSeg.indexOf('=');
      if (i <= 0) {
        // Possibly looking at something like "{name}" with implicit wildcard.
        if (subBindingEnds) {
          // Remember to add an implicit wildcard later.
          complexSegImplicitWildcard = true;
          subVarName = complexSeg.substring(0, complexSeg.length() - 1).trim();
          complexSeg = complexSeg.substring(complexSeg.length() - 1).trim();
        }
      } else {
        // Looking at something like "{name=wildcard}".
        subVarName = complexSeg.substring(0, i).trim();
        complexSeg = complexSeg.substring(i + 1).trim();
        if (complexSeg.equals("**")) {
          throw new ValidationException(
              "parse error: wildcard path not allowed in complex ID resource '%s'", subVarName);
        }
      }
      String complexDelimiter =
          currIteratorIndex < separatorIndices.size()
              ? separatorIndices.get(currIteratorIndex)
              : "";
      segments.add(Segment.create(SegmentKind.BINDING, subVarName, complexDelimiter));
      segments.add(Segment.wildcardCreate(complexDelimiter));
      segments.add(Segment.END_BINDING);
      subVarName = null;

      currIteratorIndex++;
    }
    return segments;
  }

  // Helpers
  // =======

  private String encodeUrl(String text) {
    if (urlEncoding) {
      try {
        return URLEncoder.encode(text, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new ValidationException("UTF-8 encoding is not supported on this platform");
      }
    } else {
      // When encoding is disabled, we accept any character except '/'
      final String INVALID_CHAR = "/";
      if (text.contains(INVALID_CHAR)) {
        throw new ValidationException(
            "Invalid character \"" + INVALID_CHAR + "\" in path section \"" + text + "\".");
      }
      return text;
    }
  }

  private String decodeUrl(String url) {
    if (urlEncoding) {
      try {
        return URLDecoder.decode(url, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new ValidationException("UTF-8 encoding is not supported on this platform");
      }
    } else {
      return url;
    }
  }

  // Checks for the given segments kind. On success, consumes them. Otherwise leaves
  // the list iterator in its state.
  private static boolean peek(ListIterator<Segment> segments, SegmentKind... kinds) {
    int start = segments.nextIndex();
    boolean success = false;
    for (SegmentKind kind : kinds) {
      if (!segments.hasNext() || segments.next().kind() != kind) {
        success = false;
        break;
      }
    }
    if (success) {
      return true;
    }
    restore(segments, start);
    return false;
  }

  // Restores a list iterator back to a given index.
  private static void restore(ListIterator<?> segments, int index) {
    while (segments.nextIndex() > index) {
      segments.previous();
    }
  }

  // Equality and String Conversion
  // ==============================

  /** Returns a pretty version of the template as a string. */
  @Override
  public String toString() {
    return toSyntax(segments, true);
  }

  /**
   * Returns a raw version of the template as a string. This renders the template in its internal,
   * normalized form.
   */
  public String toRawString() {
    return toSyntax(segments, false);
  }

  private static String toSyntax(List<Segment> segments, boolean pretty) {
    StringBuilder result = new StringBuilder();
    boolean continueLast = true; // if true, no slash is appended.
    ListIterator<Segment> iterator = segments.listIterator();
    while (iterator.hasNext()) {
      Segment seg = iterator.next();
      if (!continueLast) {
        result.append(seg.separator());
      }
      continueLast = false;
      switch (seg.kind()) {
        case BINDING:
          if (pretty && seg.value().startsWith("$")) {
            // Remove the internal binding.
            seg = iterator.next(); // Consume wildcard
            result.append(seg.value());
            iterator.next(); // Consume END_BINDING
            continue;
          }
          result.append('{');
          result.append(seg.value());
          if (pretty && peek(iterator, SegmentKind.WILDCARD, SegmentKind.END_BINDING)) {
            // Reduce {name=*} to {name}.
            result.append('}');
            continue;
          }
          result.append('=');
          continueLast = true;
          continue;
        case END_BINDING:
          result.append('}');
          continue;
        default:
          result.append(seg.value());
          continue;
      }
    }
    return result.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof PathTemplate)) {
      return false;
    }
    PathTemplate other = (PathTemplate) obj;
    return Objects.equals(segments, other.segments);
  }

  @Override
  public int hashCode() {
    return segments.hashCode();
  }
}
