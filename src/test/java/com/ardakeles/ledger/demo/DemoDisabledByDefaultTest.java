package com.ardakeles.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.service.LockingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The unsafe-locking switch must be unreachable unless someone explicitly sets ledger.demo.enabled=true.
 * This runs with default configuration, i.e. what anyone gets who just starts the application.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class DemoDisabledByDefaultTest {

    @Autowired MockMvc mvc;
    @Autowired LockingMode lockingMode;

    @Test
    void lockingIsOrderedByDefault() {
        assertThat(lockingMode.isOrdered()).isTrue();
    }

    @Test
    void demoPageAndItsEndpointsDoNotExist() throws Exception {
        mvc.perform(get("/demo")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/events")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/state")).andExpect(status().isNotFound());
        mvc.perform(post("/demo/fire")).andExpect(status().isNotFound());
        mvc.perform(post("/demo/mode").contentType(MediaType.APPLICATION_JSON).content("{\"safe\":false}"))
                .andExpect(status().isNotFound());

        assertThat(lockingMode.isOrdered()).as("a request to flip the mode must have had no effect").isTrue();
    }

    @Test
    void demoPageIsNotLeakedThroughStaticResources() throws Exception {
        mvc.perform(get("/demo/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/demo.html")).andExpect(status().isNotFound());
    }
}
