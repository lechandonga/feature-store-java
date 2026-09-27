package com.github.lechandonga.featurestorejava.tests;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-level workspace flow: create/list/inspect workspaces, register the
 * same feature set name in two workspaces, isolate writes/reads via the
 * {@code workspace} query parameter, and observe distinguishable error
 * responses (WORKSPACE_NOT_FOUND / WORKSPACE_CAPACITY_EXCEEDED).
 */
@SpringBootTest(properties = "feature-store.data-dir=target/test-data/workspace-api")
@AutoConfigureMockMvc
class WorkspaceApiIntegrationTest {

    @BeforeAll
    static void cleanDataDir() throws IOException {
        Path dir = Path.of("target/test-data/workspace-api");
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
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
    void workspaceLifecycleOverHttp() throws Exception {
        Map<String, Object> age = f("name", "age", "type", "LONG", "required", true,
                "defaultValue", 0, "missingPolicy", "DEFAULT", "description", "d");

        // create two workspaces, one of them capacity-capped
        mvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("name", "alpha", "maxFeatureSets", null,
                                        "maxPoints", null, "description", "A"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("alpha"));
        mvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("name", "tiny", "maxFeatureSets", 1,
                                        "maxPoints", 1, "description", "T"))))
                .andExpect(status().isOk());

        // duplicate workspace -> 409 WORKSPACE_CONFLICT
        mvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(f("name", "alpha"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WORKSPACE_CONFLICT"));

        // list contains default + both new workspaces
        mvc.perform(get("/api/workspaces"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'default')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'alpha')]").exists())
                .andExpect(jsonPath("$[?(@.name == 'tiny')]").exists());

        // register the SAME feature set name in default and alpha
        Map<String, Object> register = f("name", "http_ws_user", "entityKey", "uid",
                "features", List.of(age), "description", "d");
        mvc.perform(post("/api/features").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(register)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/features?workspace=alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(register)))
                .andExpect(status().isOk());

        // write different values per workspace
        Map<String, Object> row = f("eventTime", 1000,
                "fields", f("uid", "u1", "age", 11));
        Map<String, Object> batch = f("featureSet", "http_ws_user", "version", 1,
                "rows", List.of(row));
        mvc.perform(post("/api/write").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(batch)))
                .andExpect(status().isOk());
        Map<String, Object> rowAlpha = f("eventTime", 1000,
                "fields", f("uid", "u1", "age", 22));
        mvc.perform(post("/api/write?workspace=alpha")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("featureSet", "http_ws_user", "version", 1,
                                        "rows", List.of(rowAlpha)))))
                .andExpect(status().isOk());

        // reads are scoped: default sees 11, alpha sees 22
        mvc.perform(get("/api/read/online/http_ws_user/entities/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.age.value").value(11));
        mvc.perform(get("/api/read/online/http_ws_user/entities/u1?workspace=alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.age.value").value(22));

        // unknown workspace -> 404 WORKSPACE_NOT_FOUND
        mvc.perform(get("/api/read/online/http_ws_user/entities/u1?workspace=ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WORKSPACE_NOT_FOUND"));

        // capacity: "tiny" allows exactly one feature set and one point
        mvc.perform(post("/api/features?workspace=tiny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("name", "only_set", "entityKey", "uid",
                                        "features", List.of(age), "description", "d"))))
                .andExpect(status().isOk());
        // second registration exceeds maxFeatureSets -> 409 WORKSPACE_CAPACITY_EXCEEDED
        mvc.perform(post("/api/features?workspace=tiny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("name", "second_set", "entityKey", "uid",
                                        "features", List.of(age), "description", "d"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WORKSPACE_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.dimension").value("featureSets"))
                .andExpect(jsonPath("$.workspace").value("tiny"))
                .andExpect(jsonPath("$.featureSet").value("second_set"));

        // one point fits; the second write exceeds maxPoints
        mvc.perform(post("/api/write?workspace=tiny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("featureSet", "only_set", "version", 1,
                                        "rows", List.of(f("eventTime", 1000,
                                                "fields", f("uid", "u1", "age", 1)))))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/write?workspace=tiny")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("featureSet", "only_set", "version", 1,
                                        "rows", List.of(f("eventTime", 2000,
                                                "fields", f("uid", "u1", "age", 2)))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WORKSPACE_CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.dimension").value("points"));

        // usage endpoint reports limits and consumption
        mvc.perform(get("/api/workspaces/tiny"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureSetCount").value(1))
                .andExpect(jsonPath("$.maxFeatureSets").value(1))
                .andExpect(jsonPath("$.pointCount").value(1))
                .andExpect(jsonPath("$.maxPoints").value(1));

        // audit endpoint is filterable per workspace
        mvc.perform(get("/api/ops/audit?workspace=tiny&limit=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.workspace == 'tiny')]").exists())
                .andExpect(jsonPath("$[?(@.workspace == 'alpha')]").doesNotExist());
    }
}
