package com.saadat.pricing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saadat.common.error.ValidationException;
import java.util.Iterator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Partial-update support for the admin CRUD endpoints (the SPA sends partial bodies, e.g.
 * {@code {id, active:false}}): serializes the current DTO, overlays the fields present in the request
 * (an explicit {@code null} clears a field, an absent field keeps its value; {@code id} is ignored) and
 * reads the result back as the DTO.
 */
@Component
@RequiredArgsConstructor
public class JsonMerge {

    private static final String ID = "id";

    private final ObjectMapper objectMapper;

    public <T> T merge(T current, JsonNode patch, Class<T> type) {
        ObjectNode base = objectMapper.valueToTree(current);
        if (patch != null && patch.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = patch.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!ID.equals(e.getKey())) {
                    base.set(e.getKey(), e.getValue());
                }
            }
        }
        try {
            return objectMapper.treeToValue(base, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ValidationException("Malformed request body", "MALFORMED_BODY");
        }
    }
}
