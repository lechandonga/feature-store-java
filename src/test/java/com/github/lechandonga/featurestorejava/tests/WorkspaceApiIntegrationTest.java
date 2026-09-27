package com.github.lechandonga.featurestorejava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end HTTP behavior of workspace isolation:
 * create/list/select via X-Workspace, same-named sets across workspaces,
 * 404 for unknown workspaces, 422 capacity rejection with full evidence, and
 * unchanged behavior when no workspace header is sent.
 */
@SpringBootTest(properties = "feature-store.data-dir=${ws.it.dir}")
@AutoConfigureMockMvc
class WorkspaceApiIntegrationTest {

    static final Path DATA_DIR = uniqueDataDir();

    private static Path uniqueDataDir() {
        try {
            Path d = Files.createTempDirectory("ws.it.dir-" + UUID.randomUUID());
            System.setProperty("ws.it.dir", d.toString());
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

    private Map<String, Object> ageFeature() {
        return f("name", "age", "type", "LONG", "required", true,
                "defaultValue", 0, "missingPolicy", "DEFAULT", "description", "d");
    }

    @Test
    void workspaceLifecycleOverHttp() throws Exception {
        // 1) create a workspace with tight limits
        Map<String, Object> createBody = f("name", "http-team-x",
                "limits", f("maxFeatureSets", 1, "maxPoints", 1));
        mvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("http-team-x"))
                .andExpect(jsonPath("$.limits.maxPoints").value(1));

        // duplicate creation -> 409 WORKSPACE_CONFLICT
        mvc.perform(post("/api/workspaces").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(createBody)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("WORKSPACE_CONFLICT"));

        // list contains default and the new workspace
        String listBody = mvc.perform(get("/api/workspaces"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(listBody.contains("\"name\":\"default\"") || listBody.contains("\"name\": \"default\""),
                listBody);
        assertTrue(listBody.contains("http-team-x"), listBody);

        // 2) register a feature set in the workspace
        Map<String, Object> register = f("name", "http_shared", "entityKey", "uid",
                "ttlMillis", null, "features", List.of(ageFeature()), "description", "d");
        mvc.perform(post("/api/features").header("X-Workspace", "http-team-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(register)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // same name in default workspace is independent: no 409
        mvc.perform(post("/api/features")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(register)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // 3) FEATURE_SETS limit: second distinct set in http-team-x -> 422
        Map<String, Object> register2 = f("name", "http_other", "entityKey", "uid",
                "ttlMillis", null, "features", List.of(ageFeature()), "description", "d");
        mvc.perform(post("/api/features").header("X-Workspace", "http-team-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(register2)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.dimension").value("FEATURE_SETS"))
                .andExpect(jsonPath("$.workspace").value("http-team-x"))
                .andExpect(jsonPath("$.featureSet").value("http_other"))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.currentUsage").value(1))
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.basis").exists());

        // 4) one point fits; the second write exceeds maxPoints=1 -> whole batch rejected
        Map<String, Object> row1 = f("eventTime", 1000, "fields", f("uid", "u1", "age", 1));
        mvc.perform(post("/api/write").header("X-Workspace", "http-team-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("featureSet", "http_shared", "version", 1,
                                        "rows", List.of(row1)))))
                .andExpect(status().isOk());

        Map<String, Object> row2 = f("eventTime", 2000, "fields", f("uid", "u2", "age", 2));
        mvc.perform(post("/api/write").header("X-Workspace", "http-team-x")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                f("featureSet", "http_shared", "version", 1,
                                        "rows", List.of(row2)))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("CAPACITY_EXCEEDED"))
                .andExpect(jsonPath("$.dimension").value("POINTS"))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.currentUsage").value(1))
                .andExpect(jsonPath("$.requested").value(1));

        // usage endpoint reflects exact state
        mvc.perform(get("/api/workspaces/usage").header("X-Workspace", "http-team-x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(1))
                .andExpect(jsonPath("$.featureSets").value(1));

        // rejected point is not readable, committed one is
        mvc.perform(get("/api/read/online/http_shared/entities/u1")
                        .header("X-Workspace", "http-team-x").param("asOf", "4102444800000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.age.value").value(1));
        mvc.perform(get("/api/read/online/http_shared/entities/u2")
                        .header("X-Workspace", "http-team-x").param("asOf", "4102444800000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.age.status").value("DEFAULTED"));

        // 5) unknown workspace -> 404 WORKSPACE_NOT_FOUND
        mvc.perform(get("/api/read/online/http_shared/entities/u1")
                        .header("X-Workspace", "ghost").param("asOf", "4102444800000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WORKSPACE_NOT_FOUND"))
                .andExpect(jsonPath("$.workspace").value("ghost"));

        // 6) default workspace header omission still works and stays independent
        mvc.perform(get("/api/read/online/http_shared/entities/u1").param("asOf", "4102444800000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.features.age.status").value("DEFAULTED"));
    }
}
