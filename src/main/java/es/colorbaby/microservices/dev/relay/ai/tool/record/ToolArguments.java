package es.colorbaby.microservices.dev.relay.ai.tool.record;

import java.util.Map;

public record ToolArguments(
        Map<String, Object> values
) {

    public String getString(String key) {
        Object value = values.get(key);

        if (value == null) {
            return null;
        }
        return value.toString();
    }

    public String requireString(String key) {
        String value = getString(key);

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Missing required argument: " + key
            );
        }
        return value;
    }

    public boolean getBoolean(String key) {
        Object value = values.get(key);
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public int getInt(String key) {
        Object value = values.get(key);

        if (value instanceof Number number) {
            return number.intValue();
        }

        return Integer.parseInt(String.valueOf(value));
    }
}
