package dev.triedge.common.db;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

public class HomeClusterFilter extends OncePerRequestFilter {

    public static final String HOME_REGION_HEADER = "x-user-home-region";

    private final Set<String> clusters;

    public HomeClusterFilter(Set<String> clusters) {
        this.clusters = Set.copyOf(clusters);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String home = request.getHeader(HOME_REGION_HEADER);
        if (home == null || !clusters.contains(home)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            ClusterContext.call(home, () -> {
                try {
                    chain.doFilter(request, response);
                    return null;
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
            });
        } catch (FilterFailure failure) {
            if (failure.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) failure.getCause();
        }
    }

    private static final class FilterFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
