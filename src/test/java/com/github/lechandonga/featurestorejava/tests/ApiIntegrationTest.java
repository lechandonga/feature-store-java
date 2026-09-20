package com.github.lechandonga.featurestorejava.tests;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void endToEndRegisterWriteRejectOnlineOfflineConsistencyFreshness() throws Exception {
        // register
        mvc.perform(post("/api/features")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"item_price","valueType":"DOUBLE","missingPolicy":"DEFAULT",
                                 "defaultValue":0.0,"ttlMs":60000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));

        // incompatible evolution: type change -> 409 TYPE_CHANGED
        mvc.perform(post("/api/features")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"item_price","version":2,"valueType":"STRING",
                                 "missingPolicy":"DEFAULT","defaultValue":"0"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("TYPE_CHANGED"));

        // duplicate version -> 409 VERSION_ALREADY_EXISTS
        mvc.perform(post("/api/features")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"item_price","version":1,"valueType":"DOUBLE",
                                 "missingPolicy":"DEFAULT","defaultValue":0.0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("VERSION_ALREADY_EXISTS"));

        long t = System.currentTimeMillis() - 10_000;
        // valid write
        mvc.perform(post("/api/ingest/write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"w1\",\"rows\":[{\"feature\":\"item_price\","
                                + "\"version\":1,\"entityId\":\"sku-1\",\"eventTs\":" + t
                                + ",\"value\":12.5}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));

        // rejected batch (type mismatch + valid row in same batch) -> 422, distinguishable reasons,
        // and the valid row did NOT take effect (batch rollback).
        mvc.perform(post("/api/ingest/write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchId\":\"w2\",\"rows\":["
                                + "{\"feature\":\"item_price\",\"version\":1,\"entityId\":\"sku-2\","
                                + "\"eventTs\":" + (t - 5000) + ",\"value\":1.0},"
                                + "{\"feature\":\"item_price\",\"version\":1,\"entityId\":\"sku-2\","
                                + "\"eventTs\":" + (t - 4000) + ",\"value\":\"bad\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("WRITE_REJECTED"))
                .andExpect(jsonPath("$.message[0].reason").value("TYPE_MISMATCH"));

        // online read for sku-2 -> defaulted explicitly (rejected batch changed nothing)
        mvc.perform(post("/api/serving/online")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityId\":\"sku-2\",\"features\":[\"item_price\"],\"versions\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DEFAULTED"))
                .andExpect(jsonPath("$[0].value").value(0.0));

        // online read for sku-1 -> recorded
        mvc.perform(post("/api/serving/online")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityId\":\"sku-1\",\"features\":[\"item_price\"],\"versions\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RECORDED"))
                .andExpect(jsonPath("$[0].value").value(12.5));

        // offline point-in-time read at the write time
        mvc.perform(post("/api/serving/offline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityId\":\"sku-1\",\"asOf\":" + System.currentTimeMillis()
                                + ",\"features\":[\"item_price\"],\"versions\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RECORDED"))
                .andExpect(jsonPath("$[0].value").value(12.5));

        // freshness + audit
        mvc.perform(post("/api/ops/freshness")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entityId\":\"sku-1\",\"features\":[\"item_price\"],\"versions\":[1]}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/ops/audit?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }
}
