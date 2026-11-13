package com.seatlock.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import testsupport.IntegrationTest;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class OpenApiDocsTest {

    @Autowired
    MockMvc mvc;

    @Test
    void openApiSpecListsCatalogEndpointsWithFlattenedQueryParams() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("SeatLock API"))
                .andExpect(jsonPath("$.paths['/api/v1/events']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/events/{id}']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/shows/{id}/seats']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/events'].get.parameters[*].name",
                        hasItems("city", "category", "language", "cursor", "limit")));
    }
}