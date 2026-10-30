package dev.triedge.identity.config;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.db.ClusterRoutingDataSource;
import dev.triedge.common.db.HomeClusterFilter;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.outbox.OutboxWriter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IdentityProperties.class, ClusterProperties.class})
@EnableScheduling
public class IdentityConfiguration {

    @Bean(destroyMethod = "close")
    ClusterDataSources clusterDataSources(ClusterProperties properties) {
        return new ClusterDataSources(properties.clusters());
    }

    @Bean
    ClusterRoutingDataSource homeDataSource(ClusterDataSources clusters, ClusterProperties properties) {
        return new ClusterRoutingDataSource(clusters, properties.region());
    }

    @Bean
    JdbcClient homeJdbc(ClusterRoutingDataSource homeDataSource) {
        return JdbcClient.create(homeDataSource);
    }

    @Bean
    PlatformTransactionManager transactionManager(ClusterRoutingDataSource homeDataSource) {
        return new DataSourceTransactionManager(homeDataSource);
    }

    @Bean
    HomeClusterFilter homeClusterFilter(ClusterDataSources clusters) {
        return new HomeClusterFilter(clusters.clusters());
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient homeJdbc) {
        return new OutboxWriter(homeJdbc, "identity");
    }

    @Bean
    EtcdClient etcdClient(IdentityProperties properties) {
        return new EtcdClient(RestClient.create(properties.etcdUrl()));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
