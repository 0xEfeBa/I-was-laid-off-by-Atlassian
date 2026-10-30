package dev.triedge.echo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"server.port=0", "triedge.echo.instance=echo-1"})
@AutoConfigureMockMvc
class EchoApplicationIT {

    @Autowired
    private MockMvc mvc;

    @Test
    void echoesTheRequest() throws Exception {
        mvc.perform(post("/orders/7?expand=items").header("X-User-Id", "u-1").content("hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instance").value("echo-1"))
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.path").value("/orders/7"))
                .andExpect(jsonPath("$.query").value("expand=items"))
                .andExpect(jsonPath("$.headers.x-user-id").value("u-1"))
                .andExpect(jsonPath("$.body").value("hello"));
        mvc.perform(get("/"))
                .andExpect(jsonPath("$.body").doesNotExist());
    }

    @Test
    void failsOnRequest() throws Exception {
        mvc.perform(get("/fail?status=503")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
        mvc.perform(get("/fail")).andExpect(status().isInternalServerError());
        mvc.perform(get("/fail?status=200")).andExpect(status().isBadRequest());
        mvc.perform(get("/fail?status=999")).andExpect(status().isBadRequest());
    }
}
