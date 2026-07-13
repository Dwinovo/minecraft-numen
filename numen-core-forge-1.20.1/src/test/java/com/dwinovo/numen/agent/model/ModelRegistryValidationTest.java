package com.dwinovo.numen.agent.model;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryValidationTest {
    @Test void validatesProviderShapeAndPositiveContextWindows() {
        assertDoesNotThrow(() -> ModelRegistryJson.validate(JsonParser.parseString("""
                {"providers":[{"id":"custom","name":"Custom","models":[{"id":"m","ctx":32000}]}]}
                """)));
        assertThrows(IllegalArgumentException.class, () -> ModelRegistryJson.validate(
                JsonParser.parseString("{\"providers\":[]}")));
        assertThrows(IllegalArgumentException.class, () -> ModelRegistryJson.validate(JsonParser.parseString("""
                {"providers":[{"id":"custom","name":"Custom","models":[{"id":"m","ctx":0}]}]}
                """)));
    }
}
