package com.materialize.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Parses configuration properties to extract topic configurations.
 * Expected format:
 * - schema.topic.<input-topic>=<schema-json>
 * - output.topic.<input-topic>=<output-topic>
 */
public class ConfigParser {
    private static final Logger logger = LoggerFactory.getLogger(ConfigParser.class);
    private static final String SCHEMA_TOPIC_PREFIX = "schema.topic.";
    private static final String OUTPUT_TOPIC_PREFIX = "output.topic.";

    private final ObjectMapper mapper;

    public ConfigParser() {
        this.mapper = new ObjectMapper();
    }

    public ConfigParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parse all schema.topic.* and corresponding output.topic.* configurations.
     *
     * @param props the properties containing topic configurations
     * @return list of parsed topic configurations
     * @throws IOException if schema JSON parsing fails
     * @throws IllegalArgumentException if configuration is invalid
     */
    public List<TopicConfig> parseTopicConfigs(Properties props) throws IOException {
        Map<String, String> propMap = props.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> e.getValue().toString()
                ));

        List<TopicConfig> result = new ArrayList<>();

        for (String key : propMap.keySet()) {
            if (!key.startsWith(SCHEMA_TOPIC_PREFIX)) {
                continue;
            }

            String inputTopic = key.substring(SCHEMA_TOPIC_PREFIX.length());
            String schemaJson = propMap.get(key);
            String outputTopicKey = OUTPUT_TOPIC_PREFIX + inputTopic;
            String outputTopic = propMap.get(outputTopicKey);

            if (outputTopic == null) {
                throw new IllegalArgumentException(
                        "Missing config '" + outputTopicKey + "' for input topic '" + inputTopic + "'");
            }

            JsonNode schemaNode = mapper.readTree(schemaJson);
            TopicConfig config = new TopicConfig(inputTopic, outputTopic, schemaNode);
            result.add(config);
            logger.info("Loaded configuration: {} -> {}", inputTopic, outputTopic);
        }

        if (result.isEmpty()) {
            throw new IllegalArgumentException("No schema.topic.* configuration entries found");
        }

        return result;
    }
}
