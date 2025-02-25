package com.cedarsoftware.io.factory;

import java.util.HashMap;
import java.util.Map;

import com.cedarsoftware.io.JsonObject;
import com.cedarsoftware.io.JsonReader;
import com.cedarsoftware.io.Resolver;
import com.cedarsoftware.util.CompactMap;
import com.cedarsoftware.util.convert.Converter;

/**
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
public class CompactMapFactory implements JsonReader.ClassFactory {

    public CompactMapFactory() {
    }

    public Map newInstance(Class<?> c, JsonObject jObj, Resolver resolver) {
        Map<String, Object> map = (Map) jObj;
        Converter converter = resolver.getConverter();

        // Create Map
        boolean caseSensitive = converter.convert(map.get(JsonObject.FIELD_PREFIX + "caseSensitive" + JsonObject.FIELD_SUFFIX), boolean.class);
        int compactSize = converter.convert(map.get(JsonObject.FIELD_PREFIX + "compactSize" + JsonObject.FIELD_SUFFIX), int.class);
        String order = converter.convert(map.get(JsonObject.FIELD_PREFIX + "order" + JsonObject.FIELD_SUFFIX), String.class);
        String singleKey = converter.convert(map.get(JsonObject.FIELD_PREFIX + "singleKey" + JsonObject.FIELD_SUFFIX), String.class);

        Map<String, Object> options = new HashMap<>();
        options.put(CompactMap.CASE_SENSITIVE, caseSensitive);
        options.put(CompactMap.COMPACT_SIZE, compactSize);
        options.put(CompactMap.ORDERING, order);
        options.put(CompactMap.SINGLE_KEY, singleKey);

        CompactMap.Builder<Object, Object> builder = CompactMap.builder().caseSensitive(caseSensitive).compactSize(compactSize).singleValueKey(singleKey);
        if (order.equals(CompactMap.SORTED)) {
            builder.sortedOrder();
        } else if (order.equals(CompactMap.REVERSE)) {
            builder.reverseOrder();
        } else if (order.equals(CompactMap.INSERTION)) {
            builder.insertionOrder();
        } else if (order.equals(CompactMap.UNORDERED)) {
            builder.noOrder();
        }
        CompactMap<Object, Object> cmap = builder.build();
        JsonReader reader = new JsonReader(resolver);

        // Fill Map
        Object[] entries = (Object[])map.get(JsonObject.FIELD_PREFIX + "entries" + JsonObject.FIELD_SUFFIX);

        if (entries != null) {
            for (int i = 0; i < entries.length; i++) {
                Map<Object, Object> pair = (Map<Object, Object>) entries[i];
                Object key = pair.get("key");
                Object value = pair.get("value");
                if (key instanceof JsonObject) {
                    key = reader.toJava(null, key);
                }
                if (value instanceof JsonObject) {
                    value = reader.toJava(null, value);
                }
                cmap.put(key, value);
            }
        }
        return cmap;
    }
}