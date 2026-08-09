package com.thock.back.market.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Configuration
public class LazyDataSourceConfig {

    @Bean(name = "dataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            DataSourceProperties dataSourceProperties
    ) {
        return dataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = "lazyDataSource")
    @Primary
    public DataSource lazyDataSource(
            @Qualifier("dataSource") DataSource targetDataSource
    ) {
        // TODO(debug): 주문 생성 API 한정 실제 커넥션 획득 시점 확인용 임시 로깅. 확인 끝나면 제거.
        return new LazyConnectionDataSourceProxy(new OrderCreationConnectionAcquisitionLoggingDataSource(targetDataSource));
    }

    // LazyConnectionDataSourceProxy가 감싸는 실제(Hikari) DataSource를 가로채므로,
    // 이 getConnection()이 호출되는 시점 = 실제 물리 커넥션을 풀에서 checkout하는 시점이다.
    @Slf4j
    private static class OrderCreationConnectionAcquisitionLoggingDataSource extends DelegatingDataSource {

        OrderCreationConnectionAcquisitionLoggingDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            if (OrderCreationConnectionDebugMarker.isActive()) {
                log.info("[CONN-DEBUG] 주문 생성 중 실제 물리 커넥션 획득(pool checkout) thread={}", Thread.currentThread().getName());
            }
            return super.getConnection();
        }
    }

}
