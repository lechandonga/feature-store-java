package com.github.lechandonga.featurestorejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP flow proving the store runs locally end to end:
 * register -> write (rejected/accepted) -> online read -> backfill -> offline
 * read -> consistency/ops, plus structured conflict and rejection responses.
 */
@SpringBootTest(properties = "feature-store.data-dir=${legacy.it.dir}")
@AutoConfigureMockMvc
class FeatureStoreApiIntegrationTest {

    static final Path DATA_DIR = uniqueDataDir();

    private static Path uniqueDataDir() {
        try {
            Path d = Files.createTempDirectory("legacy.it.dir-" + UUID.randomUUID());
            System.setProperty("legacy.it.dir", d.toString());
            return d;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private Map<String, Object> f(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        // register
        Map<String, Object> age = f("name", "age", "type", "LONG", "required", true,
                "defaultValue", 0, "missingPolicy", "DEFAULT", "description", "d");
        Map<String, Object> score = f("name", "score", "type", "DOUBLE", "required", false,
                "defaultValue", null, "missingPolicy", "NULL", "description", "d");
        Map<String, Object> register = f("name", "http_user", "entityKey", "uid",
                "ttlMillis", null, "features", List.of(age, score), "description", "d");
        mvc.perform(post("/api/features").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(register)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // duplicate registration -> 409 ALREADY_EXISTS
        mvc.perform(post("/api/features").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(register)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DEFINITION_CONFLICT"))
                .andExpect(jsonPath("$.conflicts[0].reason").value("ALREADY_EXISTS"));

        // bad write (type mismatch) -> 422, state untouched
        Map<String, Object> badRow = f("eventTime", 1000,
                "fields", f("uid", "u1", "age", "not-long"));
        Map<String, Object> badBatch = f("featureSet", "http_user", "version", 1,
                "rows", List.of(badRow));
        mvc.perform(post("/api/write").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(badBatch)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BATCH_REJECTED"))
                .andExpect(jsonPath("$.rejections[0].reason").value("TYPE_MISMATCH"));

        // good write
        Map<String, Object> goodRow = f("eventTime", 1000,
                "fields", f("uid", "u1", "age", 30, "score", 0.8));
        Map<String, Object> goodBatch = f("featureSet", "http_user", "version", 1,
                "rows", List.of(goodRow));
        MvcResult written = mvc.perform(post("/api/write").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(goodBatch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedRows").value(1))
                .andReturn();
        String pointId = json.readTree(written.getResponse().getContentAsString())
                .get("pointIds").get(0).asText();

        // online read far enough in the future (ingest wall-clock has advanced)
        long future = System.currentTimeMillis() + 60_000L;
        mvc.perform(get("/api/read/online/http_user/entities/u1")
                        .param("version", "1").param("asOf", String.valueOf(future)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.age.status").value("PRESENT"))
                .andExpect(jsonPath("$.features.age.value").value(30));

        // offline point-in-time read
        Map<String, Object> target = f("entity", "u1", "asOfTime", future);
        mvc.perform(post("/api/read/offline/http_user").param("version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(List.of(target))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].features.age.value").value(30));

        // backfill correction
        Map<String, Object> correction = f("targetPointId", pointId, "entity", "u1",
                "eventTime", 1000, "correctedValues", f("age", 31, "score", 0.9),
                "reason", "restated");
        Map<String, Object> backfill = f("featureSet", "http_user", "version", 1,
                "correlationId", "job-1", "corrections", List.of(correction));
        mvc.perform(post("/api/backfill").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(backfill)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedCount").value(1));

        // consistency report is equivalent
        mvc.perform(post("/api/read/consistency/http_user").param("version", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(List.of(target))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.equivalent").value(true));

        // coverage ops
        mvc.perform(get("/api/ops/coverage/http_user").param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityCount").value(1));

        // unknown set -> 404
        mvc.perform(get("/api/read/online/nope/entities/u1"))
                .andExpect(status().isNotFound());
    }
}
