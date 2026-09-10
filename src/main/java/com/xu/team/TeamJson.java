package com.xu.team;

import com.fasterxml.jackson.databind.ObjectMapper;

final class TeamJson {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private TeamJson() {}
    static String write(Object value) {
        try { return MAPPER.writeValueAsString(value); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode Team value", e); }
    }
}
