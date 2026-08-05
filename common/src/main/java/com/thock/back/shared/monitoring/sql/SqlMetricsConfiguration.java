package com.thock.back.shared.monitoring.sql;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

@Configuration
@ConditionalOnProperty(prefix = "app.sql-metrics", name = "enabled", havingValue = "true")
public class SqlMetricsConfiguration {

    @Bean
    public HibernatePropertiesCustomizer sqlMetricsStatementInspector() {
        return hibernateProperties -> hibernateProperties.put(
                "hibernate.session_factory.statement_inspector",
                (StatementInspector) sql -> sql
        );
    }

    @Bean
    public BeanPostProcessor sqlMetricsDataSourceProxy(
            SqlMetricsRecorder recorder,
            MeterRegistry meterRegistry
    ) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (!(bean instanceof DataSource dataSource)
                        || (beanName != null && beanName.contains("proxy"))) {
                    return bean;
                }

                registerHikariMetrics(dataSource, meterRegistry);

                QueryExecutionListener listener = new QueryExecutionListener() {
                    @Override
                    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                    }

                    @Override
                    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                        long elapsedMillis = execInfo.getElapsedTime();
                        recorder.recordQuery(elapsedMillis);
                        RequestSqlMetricsContext.recordQuery(elapsedMillis);
                    }
                };

                return ProxyDataSourceBuilder
                        .create(dataSource)
                        .name("sql-metrics-datasource-proxy")
                        .listener(listener)
                        .build();
            }
        };
    }

    private void registerHikariMetrics(DataSource dataSource, MeterRegistry meterRegistry) {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            return;
        }

        String poolName = hikariDataSource.getPoolName() == null
                ? "default"
                : hikariDataSource.getPoolName();
        Gauge.builder("hikaricp_connections_active", hikariDataSource, source -> poolValue(source, HikariPoolMXBean::getActiveConnections))
                .tag("pool", poolName)
                .register(meterRegistry);
        Gauge.builder("hikaricp_connections_idle", hikariDataSource, source -> poolValue(source, HikariPoolMXBean::getIdleConnections))
                .tag("pool", poolName)
                .register(meterRegistry);
        Gauge.builder("hikaricp_connections_pending", hikariDataSource, source -> poolValue(source, HikariPoolMXBean::getThreadsAwaitingConnection))
                .tag("pool", poolName)
                .register(meterRegistry);
        Gauge.builder("hikaricp_connections", hikariDataSource, source -> poolValue(source, HikariPoolMXBean::getTotalConnections))
                .tag("pool", poolName)
                .register(meterRegistry);
        Gauge.builder("hikaricp_connections_max", hikariDataSource, HikariDataSource::getMaximumPoolSize)
                .tag("pool", poolName)
                .register(meterRegistry);
        Gauge.builder("hikaricp_connections_min", hikariDataSource, HikariDataSource::getMinimumIdle)
                .tag("pool", poolName)
                .register(meterRegistry);
    }

    private int poolValue(HikariDataSource dataSource, java.util.function.ToIntFunction<HikariPoolMXBean> value) {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool == null ? 0 : value.applyAsInt(pool);
    }
}
