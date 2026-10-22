package com.thock.back.market.config;

import com.thock.back.market.monitoring.sql.MarketDatasourceProxyConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

class LazyDataSourceConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class, LazyDataSourceConfig.class,
                    MarketDatasourceProxyConfiguration.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:lazy_connection_test",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.datasource.username=sa",
                    "spring.datasource.hikari.maximum-pool-size=2",
                    "spring.datasource.hikari.minimum-idle=0"
            );

    @Test
    void primaryDataSourceKeepsSqlMonitoringAndDefersPoolCheckoutUntilSqlExecution() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            HikariDataSource pool = context.getBean("hikariDataSource", HikariDataSource.class);
            DataSource dataSource = context.getBean(DataSource.class);

            assertThat(pool.getMaximumPoolSize()).isEqualTo(2);
            assertThat(dataSource).isSameAs(context.getBean("dataSource"));
            assertThat(dataSource).isInstanceOf(ProxyDataSource.class);
            assertThat(((ProxyDataSource) dataSource).getDataSource())
                    .isInstanceOf(LazyConnectionDataSourceProxy.class);

            // 풀과 기본 연결 속성을 초기화한 뒤 실제 요청의 커넥션 점유를 확인한다.
            try (Connection warmup = dataSource.getConnection()) {
                warmup.getAutoCommit();
            }
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();

            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();

                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("select 1")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                    assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isEqualTo(1);
                }
                connection.rollback();
            }
            assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DataSourceProperties.class)
    static class PropertiesConfig {
    }
}
