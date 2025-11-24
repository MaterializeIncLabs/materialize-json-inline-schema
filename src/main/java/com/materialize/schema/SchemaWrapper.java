package com.materialize.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps JSON payloads with inline schema information.
 * Transforms: {"field": "value"}
 * Into: {"schema": {...}, "payload": {"field": "value"}}
 */
public class SchemaWrapper {
    private static final Logger logger = LoggerFactory.getLogger(SchemaWrapper.class);
    private final ObjectMapper mapper;

    public SchemaWrapper() {
        this.mapper = new ObjectMapper();
    }

    public SchemaWrapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Converts payload fields to match schema types.
     * Materialize outputs numeric values as strings in JSON to preserve precision.
     * This method converts them to numbers when the schema declares them as numeric.
     *
     * @param payloadNode the payload to convert
     * @param schemaNode the schema defining expected types
     * @return converted payload
     */
    private JsonNode convertPayloadTypes(JsonNode payloadNode, JsonNode schemaNode) {
        if (!payloadNode.isObject() || !schemaNode.has("fields")) {
            return payloadNode;
        }

        ObjectNode convertedPayload = ((ObjectNode) payloadNode).deepCopy();
        JsonNode fields = schemaNode.get("fields");

        if (fields.isArray()) {
            for (JsonNode fieldDef : fields) {
                String fieldName = fieldDef.get("field").asText();
                String fieldType = fieldDef.get("type").asText();
                String logicalType = fieldDef.has("name") ? fieldDef.get("name").asText() : null;

                if (convertedPayload.has(fieldName)) {
                    JsonNode fieldValue = convertedPayload.get(fieldName);

                    // Convert string to number if schema expects a numeric type
                    if (fieldValue.isTextual()) {
                        try {
                            if ("double".equals(fieldType) || "float".equals(fieldType)) {
                                double numValue = Double.parseDouble(fieldValue.asText());
                                convertedPayload.set(fieldName, new DoubleNode(numValue));
                            } else if ("int64".equals(fieldType) || "int32".equals(fieldType)) {
                                String textValue = fieldValue.asText();
                                long numValue;
                                if ("org.apache.kafka.connect.data.Timestamp".equals(logicalType)) {
                                    // Materialize outputs TIMESTAMP type as milliseconds with optional decimal places
                                    // e.g., "1763960467672.000" (milliseconds with fractional precision)
                                    // Remove decimal point and everything after, then parse
                                    // Kafka Connect Timestamp expects milliseconds since epoch, so no conversion needed
                                    String cleanedValue = textValue.contains(".") ?
                                        textValue.substring(0, textValue.indexOf(".")) : textValue;
                                    numValue = Long.parseLong(cleanedValue);
                                    logger.debug("Converted timestamp field {} from string '{}' to {} milliseconds",
                                               fieldName, textValue, numValue);
                                } else {
                                    numValue = Long.parseLong(textValue);
                                }
                                convertedPayload.set(fieldName, new LongNode(numValue));
                            }
                        } catch (NumberFormatException e) {
                            // Keep original value if conversion fails
                            logger.debug("Could not convert field {} to {}: {}", fieldName, fieldType, e.getMessage());
                        }
                    }
                }
            }
        }

        return convertedPayload;
    }

    /**
     * Wraps the given JSON value with a schema.
     *
     * @param value the original JSON string
     * @param schemaNode the schema to attach
     * @return JSON string with schema wrapper, or null if input is null
     * @throws JsonProcessingException if the value is not valid JSON
     */
    public String wrapWithSchema(String value, JsonNode schemaNode) throws JsonProcessingException {
        if (value == null) {
            return null; // tombstone
        }

        JsonNode payloadNode = mapper.readTree(value);

        // Convert payload types to match schema
        JsonNode convertedPayload = convertPayloadTypes(payloadNode, schemaNode);

        ObjectNode root = mapper.createObjectNode();
        root.set("schema", schemaNode);
        root.set("payload", convertedPayload);

        return mapper.writeValueAsString(root);
    }

    /**
     * Wraps the given JSON value with a schema, with error handling.
     * If the value is not valid JSON, returns it unchanged and logs a warning.
     *
     * @param value the original JSON string
     * @param schemaNode the schema to attach
     * @return JSON string with schema wrapper, original value if parsing fails, or null if input is null
     */
    public String wrapWithSchemaOrPassThrough(String value, JsonNode schemaNode) {
        if (value == null) {
            logger.debug("Processing tombstone (null value) - passing through for delete");
            return null;  // Preserve tombstones for deletes
        }

        try {
            return wrapWithSchema(value, schemaNode);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to parse JSON value, passing through unchanged: {}", e.getMessage());
            return value;
        }
    }
}
