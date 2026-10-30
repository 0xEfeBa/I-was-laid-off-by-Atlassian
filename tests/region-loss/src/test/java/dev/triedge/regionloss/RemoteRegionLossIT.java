package dev.triedge.regionloss;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = RemoteRegions.PREFIX + "eu", matches = ".+")
class RemoteRegionLossIT {

    @Test
    void losingARealRegionLosesNoAcknowledgedWrite() throws Exception {
        RegionLossCheck.Result result = RegionLossCheck.run(RemoteRegions.fromSystemProperties(), Duration.ofSeconds(30), 8);

        System.out.println("three continents: " + result);
        assertThat(result.acknowledged()).isNotEmpty();
        assertThat(result.lost()).isZero();
    }
}
