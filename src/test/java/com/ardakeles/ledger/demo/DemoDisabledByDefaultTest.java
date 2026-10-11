package com.ardakeles.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.service.LockingMode;
import org.springframework.context.ApplicationContext;
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
    @Autowired ApplicationContext context;

    /** The unsafe switch is not a bean at all: nothing in the application can be told to lock out of order. */
    @Test
    void thereIsNoLockingSwitchInTheApplication() {
        assertThat(context.getBeanNamesForType(LockingMode.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DemoService.class)).isEmpty();
    }

    @Test
    void demoPageAndItsEndpointsDoNotExist() throws Exception {
        mvc.perform(get("/demo")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/events")).andExpect(status().isNotFound());
        mvc.perform(get("/demo/state")).andExpect(status().isNotFound());
        mvc.perform(post("/demo/fire")).andExpect(status().isNotFound());
        mvc.perform(post("/demo/mode").contentType(MediaType.APPLICATION_JSON).content("{\"safe\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void demoPageIsNotLeakedThroughStaticResources() throws Exception {
        mvc.perform(get("/demo/index.html")).andExpect(status().isNotFound());
        mvc.perform(get("/demo.html")).andExpect(status().isNotFound());
    }
}
