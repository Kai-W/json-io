package com.cedarsoftware.io;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cedarsoftware.io.reflect.Injector;
import com.cedarsoftware.util.ClassUtilities;
import com.cedarsoftware.util.FastReader;

import static com.cedarsoftware.io.JsonObject.ENUM;
import static com.cedarsoftware.io.JsonObject.ID;
import static com.cedarsoftware.io.JsonObject.ITEMS;
import static com.cedarsoftware.io.JsonObject.KEYS;
import static com.cedarsoftware.io.JsonObject.REF;
import static com.cedarsoftware.io.JsonObject.SHORT_ID;
import static com.cedarsoftware.io.JsonObject.SHORT_ITEMS;
import static com.cedarsoftware.io.JsonObject.SHORT_KEYS;
import static com.cedarsoftware.io.JsonObject.SHORT_REF;
import static com.cedarsoftware.io.JsonObject.SHORT_TYPE;
import static com.cedarsoftware.io.JsonObject.TYPE;
import static com.cedarsoftware.util.MathUtilities.parseToMinimalNumericType;

/**
 * Parse the JSON input stream supplied by the FastPushbackReader to the constructor.
 * The entire JSON input stream will be read until it is emptied: an EOF (-1) is read.
 * <p>
 * While reading the content, Java Maps (JsonObjects) are used to hold the contents of
 * JSON objects { }.  Lists are used to hold the contents of JSON arrays.  Each object
 * that has an @id field will be copied into the supplied 'objectsMap' constructor
 * argument.  This allows the user of this class to locate any referenced object
 * directly.
 * <p>
 * When this parser completes, the @ref (references to objects identified with @id)
 * are stored as a JsonObject with a @ref as the key and the ID value of the object.
 * No substitution has yet occurred (substituting the @ref pointers with a Java
 * reference to the actual Map (Map containing the @id)).
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License");
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         <a href="http://www.apache.org/licenses/LICENSE-2.0">License</a>
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
class JsonParser {
    private static final JsonObject EMPTY_ARRAY = new JsonObject();  // compared with ==
    private final Map<String, String> stringCache = new LinkedHashMap<String, String>() {
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > 2500;
        }
    };
    private final Map<Number, Number> numberCache = new LinkedHashMap<Number, Number>() {
        protected boolean removeEldestEntry(Map.Entry eldest) {
            return size() > 2500;
        }
    };
    private final Map<String, String> substitutes = new LinkedHashMap<>();
    private final FastReader input;
    private final StringBuilder strBuf = new StringBuilder(256);
    private final StringBuilder hexBuf = new StringBuilder();
    private final StringBuilder numBuf = new StringBuilder();
    private int curParseDepth = 0;
    private final boolean allowNanAndInfinity;
    private final int maxParseDepth;
    private final Resolver resolver;
    private final ReadOptions readOptions;
    private final ReferenceTracker references;

     {
        // substitutes
        substitutes.put(SHORT_ID, ID);
        substitutes.put(SHORT_REF, REF);
        substitutes.put(SHORT_ITEMS, ITEMS);
        substitutes.put(SHORT_TYPE, TYPE);
        substitutes.put(SHORT_KEYS, KEYS);

        // Save heap memory by re-using common strings (String's immutable)
        stringCache.put("", "");
        stringCache.put("true", "true");
        stringCache.put("True", "True");
        stringCache.put("TRUE", "TRUE");
        stringCache.put("false", "false");
        stringCache.put("False", "False");
        stringCache.put("FALSE", "FALSE");
        stringCache.put("null", "null");
        stringCache.put("yes", "yes");
        stringCache.put("Yes", "Yes");
        stringCache.put("YES", "YES");
        stringCache.put("no", "no");
        stringCache.put("No", "No");
        stringCache.put("NO", "NO");
        stringCache.put("on", "on");
        stringCache.put("On", "On");
        stringCache.put("ON", "ON");
        stringCache.put("off", "off");
        stringCache.put("Off", "Off");
        stringCache.put("OFF", "OFF");
        stringCache.put(ID, ID);
        stringCache.put(REF, REF);
        stringCache.put(ITEMS, ITEMS);
        stringCache.put(TYPE, TYPE);
        stringCache.put(KEYS, KEYS);
        stringCache.put("0", "0");
        stringCache.put("1", "1");
        stringCache.put("2", "2");
        stringCache.put("3", "3");
        stringCache.put("4", "4");
        stringCache.put("5", "5");
        stringCache.put("6", "6");
        stringCache.put("7", "7");
        stringCache.put("8", "8");
        stringCache.put("9", "9");

        numberCache.put(-1L, -1L);
        numberCache.put(0L, 0L);
        numberCache.put(1L, 1L);
        numberCache.put(-1.0d, -1.0d);
        numberCache.put(0.0d, 0.0d);
        numberCache.put(1.0d, 1.0d);
        numberCache.put(Double.MIN_VALUE, Double.MIN_VALUE);
        numberCache.put(Double.MAX_VALUE, Double.MAX_VALUE);
        numberCache.put(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        numberCache.put(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        numberCache.put(Double.NaN, Double.NaN);
    }

    JsonParser(FastReader reader, Resolver resolver) {
        input = reader;
        this.resolver = resolver;
        readOptions = resolver.getReadOptions();
        references = resolver.getReferences();
        maxParseDepth = readOptions.getMaxDepth();
        allowNanAndInfinity = readOptions.isAllowNanAndInfinity();
    }

    /**
     * Read a JSON value (see json.org).  A value can be a JSON object, array, string, number, ("true", "false"), or "null".
     * @param suggestedType JsonValue Owning entity.
     */
    Object readValue(Class<?> suggestedType) throws IOException {
        if (curParseDepth > maxParseDepth) {
            error("Maximum parsing depth exceeded");
        }

        int c = skipWhitespaceRead(true);
        if (c >= '0' && c <= '9' || c == '-' || c == 'N' || c == 'I') {
            return readNumber(c);
        }
        switch (c) {
            case '"':
                String str = readString();
                return str;
            case '{':
                input.pushback('{');
                JsonObject jObj = readJsonObject(suggestedType);
                return jObj;
            case '[':
                Object array = readArray(suggestedType == null ? null : suggestedType.getComponentType());
                return array;
            case ']':   // empty array
                input.pushback(']');
                return EMPTY_ARRAY;
            case 'f':
            case 'F':
                readToken("false");
                return false;
            case 'n':
            case 'N':
                readToken("null");
                return null;
            case 't':
            case 'T':
                readToken("true");
                return true;
        }

        return error("Unknown JSON value type");
    }

    /**
     * Read a JSON object { ... }
     *
     * @return JsonObject that represents the { ... } being read in.  If the JSON object type can be inferred,
     * from an @type field, containing field type, or containing array type, then the javaType will be set on the
     * JsonObject.
     */
    private JsonObject readJsonObject(Class<?> suggestedType) throws IOException {
        JsonObject jObj = new JsonObject();
        jObj.setHintType(suggestedType);
        final FastReader in = input;

        // Start reading the object, skip white space and find {
        skipWhitespaceRead(true);           // Burn '{'
        jObj.line = in.getLine();
        jObj.col = in.getCol();
        int c = skipWhitespaceRead(true);
        if (c == '}') {    // empty object
            // Using new JsonObject() below will prevent @id/@ref if more than one {} appears in the JSON.
            return new JsonObject();
        }
        in.pushback((char) c);
        ++curParseDepth;

        Map<String, Injector> injectors = readOptions.getDeepInjectorMap(suggestedType);

        while (true) {
            String field = readFieldName();
            if (substitutes.containsKey(field)) {
                field = substitutes.get(field);
            }
            Injector injector = injectors.get(field);
            Object value = readValue(injector == null ? null : injector.getType());

            // process key-value pairing
            switch (field) {
                case ENUM:  // Legacy support (@enum was used to indicate EnumSet in prior versions)
                case TYPE:
                    Class<?> type = loadType(value);
                    jObj.setJavaType(type);
                    break;

                case REF:
                    loadRef(value, jObj);
                    break;

                case ID:
                    loadId(value, jObj);
                    break;

                case ITEMS:
                    loadItems(value, jObj);
                    break;

                case KEYS:
                    loadKeys(value, jObj);
                    break;

                default:
                    jObj.put(field, value); // Load key/value pair
                    break;
            }

            c = skipWhitespaceRead(true);
            if (c == '}') {
                break;
            } else if (c != ',') {
                error("Object not ended with '}', instead found '" + (char) c + "'");
            }
        }

        --curParseDepth;
        return jObj;
    }

    /**
     * Read a JSON array
     */
    private Object readArray(Class<?> suggestedType) throws IOException {
        final List<Object> list = new ArrayList<>();
        ++curParseDepth;

        while (true) {
            Object value = readValue(suggestedType);

            if (value != EMPTY_ARRAY) {
                list.add(value);
            }

            final int c = skipWhitespaceRead(true);

            if (c == ']') {
                break;
            } else if (c != ',') {
                error("Expected ',' or ']' inside array");
            }
        }

        --curParseDepth;
        return resolver.resolveArray(suggestedType, list);
    }

    /**
     * Read the field name of a JSON object.
     *
     * @return String field name.
     */
    private String readFieldName() throws IOException {
        int c = skipWhitespaceRead(true);
        if (c != '"') {
            error("Expected quote before field name");
        }
        String field = readString();
        c = skipWhitespaceRead(true);
        if (c != ':') {
            error("Expected ':' between field and value, instead found '" + (char) c + "'");
        }
        return field;
    }

    /**
     * Return the specified token from the reader.  If it is not found,
     * throw an IOException indicating that.  Converting to c to
     * (char) c is acceptable because the 'tokens' allowed in a
     * JSON input stream (true, false, null) are all ASCII.
     */
    private void readToken(String token) throws IOException {
        final int len = token.length();

        for (int i = 1; i < len; i++) {
            int c = input.read();
            if (c == -1) {
                error("EOF reached while reading token: " + token);
            }
            c = Character.toLowerCase((char) c);
            int loTokenChar = token.charAt(i);

            if (loTokenChar != c) {
                error("Expected token: " + token);
            }
        }
    }

    /**
     * Read a JSON number.
     *
     * @param c int a character representing the first digit of the number that
     *          was already read.
     * @return a Number (a Long or a Double) depending on whether the number is
     * a decimal number or integer.  This choice allows all smaller types (Float, int, short, byte)
     * to be represented as well.
     * @throws IOException for stream errors or parsing errors.
     */
    private Number readNumber(int c) throws IOException {
        final FastReader in = input;
        boolean isFloat = false;

        if (allowNanAndInfinity && (c == '-' || c == 'N' || c == 'I')) {
            /*
             * In this branch, we must have either one of these scenarios: (a) -NaN or NaN (b) Inf or -Inf (c) -123 but
             * NOT 123 (replace 123 by any number)
             *
             * In case of (c), we do nothing and revert input and c for normal processing.
             */

            // Handle negativity.
            final boolean isNeg = (c == '-');
            if (isNeg) {
                // Advance to next character.
                c = input.read();
            }

            // Case "-Infinity", "Infinity" or "NaN".
            if (c == 'I') {
                readToken("infinity");
                // [Out of RFC 4627] accept NaN/Infinity values
                return isNeg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            } else if ('N' == c) {
                // [Out of RFC 4627] accept NaN/Infinity values
                readToken("nan");
                return Double.NaN;
            } else {
                // This is (c) case, meaning there was c = '-' at the beginning.
                // This is a number like "-2", but not "-Infinity". We let the normal code process.
                input.pushback((char) c);
                c = '-';
            }
        }

        // We are sure we have a positive or negative number, so we read char by char.
        StringBuilder number = numBuf;
        number.setLength(0);
        number.append((char) c);
        while (true) {
            c = in.read();
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                number.append((char) c);
            } else if (c == '.' || c == 'e' || c == 'E') {
                number.append((char) c);
                isFloat = true;
            } else if (c == -1) {
                break;
            } else {
                in.pushback((char) c);
                break;
            }
        }

        try {
            Number val;
            String numStr = number.toString();
            if (isFloat) {
                val = readFloatingPoint(numStr);
            } else {
                val = readInteger(numStr);
            }
            final Number cachedInstance = numberCache.get(val);
            if (cachedInstance != null) {
                return cachedInstance;
            } else {
                numberCache.put(val, val);  // caching all numbers (LRU has upper limit)
                return val;
            }
        }
        catch (Exception e) {
            return (Number) error("Invalid number: " + number, e);
        }
    }

    private Number readInteger(String numStr) {
        if (readOptions.isIntegerTypeBigInteger()) {
            return new BigInteger(numStr);
        }

        try {
            return Long.parseLong(numStr);
        } catch (Exception e) {
            BigInteger bigInt = new BigInteger(numStr);
            if (readOptions.isIntegerTypeBoth()) {
                return bigInt;
            } else {
                // Super-big integers (more than 19 digits) will "wrap around" as expected, similar to casting a long
                // to an int, where the originating long is larger than Integer.MAX_VALUE.
                return bigInt.longValue();
            }
        }
    }

    private Number readFloatingPoint(String numStr) {
        if (readOptions.isFloatingPointBigDecimal()) {
            return new BigDecimal(numStr);
        }

        Number number = parseToMinimalNumericType(numStr);
        if (readOptions.isFloatingPointBoth()) {
            return number;
        } else {
            return number.doubleValue();
        }
    }

    private static final int STRING_START = 0;
    private static final int STRING_SLASH = 1;
    private static final int HEX_DIGITS = 2;

    /**
     * Read a JSON string
     * This method assumes the initial quote has already been read.
     *
     * @return String read from JSON input stream.
     * @throws IOException for stream errors or parsing errors.
     */
    private String readString() throws IOException {
        final StringBuilder str = strBuf;
        str.setLength(0);
        final StringBuilder hex = hexBuf;
        int state = STRING_START;
        final FastReader in = input;

        while (true) {
            int c = in.read();
            if (c == -1) {
                error("EOF reached while reading JSON string");
            }

            if (state == STRING_START) {
                if (c == '"') {
                    if (curParseDepth == 0) {
                        // Enforce JSON grammar.  At root, a String must be complete, with only whitespace and then EOF after
                        c = skipWhitespaceRead(false);
                        if (c != -1) {
                            throw new JsonIoException("EOF expected, content found after \"" + str + "\" --> " + (char) c);
                        }
                    }
                    break;
                } else if (c == '\\') {
                    state = STRING_SLASH;
                } else {
                    str.append((char) c);
                }
            } else if (state == STRING_SLASH) {
                switch (c) {
                    case '\\':
                        str.append('\\');
                        break;
                    case '/':
                        str.append('/');
                        break;
                    case '"':
                        str.append('"');
                        break;
                    case '\'':
                        str.append('\'');
                        break;
                    case 'b':
                        str.append('\b');
                        break;
                    case 'f':
                        str.append('\f');
                        break;
                    case 'n':
                        str.append('\n');
                        break;
                    case 'r':
                        str.append('\r');
                        break;
                    case 't':
                        str.append('\t');
                        break;
                    case 'u':
                        hex.setLength(0);
                        state = HEX_DIGITS;
                        break;
                    default:
                        error("Invalid character escape sequence specified: " + c);
                }

                if (c != 'u') {
                    state = STRING_START;
                }
            } else {
                if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f')) {
                    hex.append((char) c);
                    if (hex.length() == 4) {
                        int value = Integer.parseInt(hex.toString(), 16);
                        str.append((char) value);
                        state = STRING_START;
                    }
                } else {
                    error("Expected hexadecimal digits");
                }
            }
        }

        final String s = str.toString();
        final String cachedInstance = stringCache.get(s);
        if (cachedInstance != null) {
            return cachedInstance;
        } else {
            if (s.length() < 33) {
                stringCache.put(s, s);  // cache small strings (LRU has upper limit)
            }
            return s;
        }
    }

    /**
     * Read until non-whitespace character and then return it.
     * This saves extra read/pushback.
     *
     * @return int representing the next non-whitespace character in the stream.
     * @throws IOException for stream errors or parsing errors.
     */
    private int skipWhitespaceRead(boolean throwOnEof) throws IOException {
        final Reader in = input;
        int c;
        do {
            c = in.read();
        } while (c == ' ' || c == '\n' || c == '\r' || c == '\t');

        if (c == -1 && throwOnEof) {
            error("EOF reached prematurely");
        }
        return c;
    }

    /**
     * Load the @id field listed in the JSON
     *
     * @param value Object should be a Long, if not exception is thrown.  It is the value associated to the @id field.
     * @param jObj  JsonObject representing the current item in the JSON being loaded.
     */
    private void loadId(Object value, JsonObject jObj) {
        if (!(value instanceof Number)) {
            error("Expected a number for " + ID + ", instead got: " + value);
        }
        Long id = ((Number) value).longValue();
        references.put(id, jObj);
        jObj.setId(id);
    }

    /**
     * Load the @ref field listed in the JSON
     *
     * @param value Object should be a Long, if not exception is thrown. It is the value associated to the @ref field.
     * @param jObj  JsonValue that will be stuffed with the reference id and marked as finished.
     */
    private void loadRef(Object value, JsonValue jObj) {
        if (!(value instanceof Number)) {
            error("Expected a number for " + REF + ", instead got: " + value);
        }
        jObj.setReferenceId(((Number) value).longValue());
    }

    /**
     * Load the @enum field listed in the JSON
     *
     * @param value Object should be a String, if not exception is thrown. It is the class of the Enum.
     */
    private void loadEnum(Object value, JsonObject jObj) {
        if (!(value instanceof String)) {
            error("Expected a String for " + ENUM + ", instead got: " + value);
        }
        Class<?> enumClass = stringToClass((String) value);
        jObj.setJavaType(enumClass);  // Also set javaType
    }

    /**
     * Load the @type field listed in the JSON
     *
     * @param value Object should be a String, if not an exception is thrown.  It is the value associated to the @type field.
     */
    private Class<?> loadType(Object value) {
        if (!(value instanceof String)) {
            error("Expected a String for " + TYPE + ", instead got: " + value);
        }
        String javaType = (String) value;
        final String substitute = readOptions.getTypeNameAlias(javaType);
        if (substitute != null) {
            javaType = substitute;
        }

        // Resolve class during parsing
        return stringToClass(javaType);
    }

    /**
     * Load the @items field listed in the JSON
     *
     * @param value Object should be an array, if not exception is thrown.  It is the value associated to the @items field.
     * @param jObj  JsonObject representing the current item in the JSON being loaded.
     */
    private void loadItems(Object value, JsonObject jObj) {
        if (value == null) {
            return;
        }
        if (!value.getClass().isArray()) {
            error("Expected @items to have an array [], but found: " + value.getClass().getName());
        }
        jObj.setItems(value);
    }

    /**
     * Load the @keys field listed in the JSON
     *
     * @param value Object should be an array, if not exception is thrown.  It is the value associated to the @keys field.
     * @param jObj  JsonObject representing the current item in the JSON being loaded.
     */
    private void loadKeys(Object value, JsonObject jObj) {
        if (value == null) {
            return;
        }
        if (!value.getClass().isArray()) {
            error("Expected @keys to have an array [], but found: " + value.getClass().getName());
        }
        jObj.setKeys(value);
    }

    private Class<?> stringToClass(String className) {
        Class<?> clazz = ClassUtilities.forName(className, readOptions.getClassLoader());
        if (clazz == null) {
            if (readOptions.isFailOnUnknownType()) {
                error("Unknown type (class) '" + className + "' not defined.");
            }
            clazz = readOptions.getUnknownTypeClass();
            if (clazz == null) {
                clazz = LinkedHashMap.class;
            }
        }
        return clazz;
    }

    private Object error(String msg) {
        throw new JsonIoException(getMessage(msg));
    }

    private Object error(String msg, Exception e) {
        throw new JsonIoException(getMessage(msg), e);
    }

    private String getMessage(String msg) {
        return msg + "\nline: " + input.getLine() + ", col: " + input.getCol() + "\n" + input.getLastSnippet();
    }
}