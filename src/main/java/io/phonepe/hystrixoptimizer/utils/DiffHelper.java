package io.phonepe.hystrixoptimizer.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Singleton
public class DiffHelper<T> {

    private ObjectMapper mapper;

    public DiffHelper(final ObjectMapper objectMapper) {
        this.mapper = objectMapper;
    }

    public String getObjectDiff(T baseObject, T currentObject) {
        try {
            final List<String> diffString = new ArrayList<>();

            TypeReference<HashMap<String, Object>> type = new TypeReference<HashMap<String, Object>>() {
            };
            Map<String, Object> leftMap = mapper.readValue(mapper.writeValueAsString(baseObject), type);
            Map<String, Object> rightMap = mapper.readValue(mapper.writeValueAsString(currentObject), type);
            Map<String, Object> leftFlatMap = flatten(leftMap);
            Map<String, Object> rightFlatMap = flatten(rightMap);

            MapDifference<String, Object> difference = Maps.difference(leftFlatMap, rightFlatMap);

            //diffString.add("\n\nEntries only on the left\n--------------------------\n");
            difference.entriesOnlyOnLeft()
                    .forEach((key, value) -> diffString.add(key + ": " + value + '\n'));

            //diffString.add("\n\nEntries only on the right\n--------------------------\n");
            difference.entriesOnlyOnRight()
                    .forEach((key, value) -> diffString.add(key + ": " + value + '\n'));

            diffString.add("\n\nEntries differing\n--------------------------\n");
            difference.entriesDiffering()
                    .forEach((key, value) -> diffString.add(key + ": " + value + '\n'));

            return String.join("", diffString);
        } catch (Exception e) {
            log.error("Exception while calculating difference. Only logging it.");
        }

        return null;
    }

    private static Map<String, Object> flatten(Map<String, Object> map) {
        return map.entrySet().stream()
                .flatMap(DiffHelper::flatten)
                .collect(LinkedHashMap::new, (m, e) -> m.put("/" + e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    private static Stream<Map.Entry<String, Object>> flatten(Map.Entry<String, Object> entry) {

        if (entry == null) {
            return Stream.empty();
        }

        if (entry.getValue() instanceof Map<?, ?>) {
            return ((Map<?, ?>) entry.getValue()).entrySet().stream()
                    .flatMap(e -> flatten(new AbstractMap.SimpleEntry<>(entry.getKey() + "/" + e.getKey(), e.getValue())));
        }

        if (entry.getValue() instanceof List<?>) {
            List<?> list = (List<?>) entry.getValue();
            return IntStream.range(0, list.size())
                    .mapToObj(i -> new AbstractMap.SimpleEntry<String, Object>(entry.getKey() + "/" + i, list.get(i)))
                    .flatMap(DiffHelper::flatten);
        }

        return Stream.of(entry);
    }
}
