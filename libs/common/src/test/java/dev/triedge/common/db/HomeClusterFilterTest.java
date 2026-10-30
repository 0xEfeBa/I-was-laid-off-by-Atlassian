package dev.triedge.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class HomeClusterFilterTest {

    private final HomeClusterFilter filter = new HomeClusterFilter(Set.of("eu", "us"));
    private final List<String> seen = new ArrayList<>();

    @Test
    void requestsRunInTheCallersHomeCluster() throws Exception {
        filter.doFilter(request("us"), new MockHttpServletResponse(),
                (req, res) -> seen.add(ClusterContext.current().orElse("none")));
        filter.doFilter(request(null), new MockHttpServletResponse(),
                (req, res) -> seen.add(ClusterContext.current().orElse("none")));
        filter.doFilter(request("mars"), new MockHttpServletResponse(),
                (req, res) -> seen.add(ClusterContext.current().orElse("none")));

        assertThat(seen).containsExactly("us", "none", "none");
        assertThat(ClusterContext.current()).isEmpty();
    }

    @Test
    void failuresFromTheChainKeepTheirType() {
        assertThatThrownBy(() -> filter.doFilter(request("eu"), new MockHttpServletResponse(), (req, res) -> {
            throw new IOException("broken pipe");
        })).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> filter.doFilter(request("eu"), new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("bad");
        })).isInstanceOf(ServletException.class);
    }

    private static MockHttpServletRequest request(String home) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        if (home != null) {
            request.addHeader(HomeClusterFilter.HOME_REGION_HEADER, home);
        }
        return request;
    }
}
