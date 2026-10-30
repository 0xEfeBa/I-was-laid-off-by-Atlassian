package dev.triedge.broker.config;

import dev.triedge.broker.service.ControlPlaneClient;
import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.db.ClusterRoutingDataSource;
import dev.triedge.common.db.HomeClusterFilter;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.outbox.OutboxWriter;
import dev.triedge.common.queue.JobQueue;
import dev.triedge.common.region.LeaderResolver;
import java.net.http.HttpClient;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({BrokerProperties.class, ClusterProperties.class})
@EnableScheduling
public class BrokerConfiguration {

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
    LeaderResolver leaderResolver(EtcdClient etcd, BrokerProperties properties) {
        return new LeaderResolver(etcd, properties.leaderNamespace());
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    OutboxWriter outboxWriter(JdbcClient homeJdbc) {
        return new OutboxWriter(homeJdbc, "platform");
    }

    @Bean
    JobQueue jobQueue(JdbcClient homeJdbc, BrokerProperties properties) {
        return new JobQueue(homeJdbc, "platform", properties.jobLock());
    }

    @Bean
    EtcdClient etcdClient(BrokerProperties properties) {
        return new EtcdClient(RestClient.create(properties.etcdUrl()));
    }

    @Bean
    ControlPlaneClient controlPlaneClient(BrokerProperties properties) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.controlPlaneTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.controlPlaneTimeout());
        return new ControlPlaneClient(RestClient.builder()
                .baseUrl(properties.controlPlaneUrl())
                .requestFactory(factory)
                .build());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
