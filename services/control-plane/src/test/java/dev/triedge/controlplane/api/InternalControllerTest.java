package dev.triedge.controlplane.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.controlplane.validate.ValidationService;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InternalControllerTest {

    private final ValidationService validation = mock(ValidationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new InternalController(validation,
            mock(SnapshotPublisher.class), mock(AckTracker.class), mock(TenantDirectory.class))).build();

    @Test
    void answers503WhenTheCurrentConfigurationCannotBeValidated() throws Exception {
        when(validation.validate(any())).thenThrow(new ValidationService.ValidationUnavailable("try again later"));

        mvc.perform(post("/internal/v1/validate").contentType(MediaType.APPLICATION_JSON).content("""
                        {"apiVersion":"tri-edge/v1","name":"shop","hosts":["shop.test"],
                         "backends":[{"name":"main","address":"shop.example.com:80"}],
                         "routes":[{"path":"/","backend":"main"}]}
                        """))
                .andExpect(status().isServiceUnavailable());
    }
}
