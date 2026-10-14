package com.rhythm.los.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Points every pooled connection at the current tenant's schema when it is handed out. */
@Configuration
public class TenantDataSourceConfig {

    static class TenantDataSource extends DelegatingDataSource implements AutoCloseable {
        TenantDataSource(DataSource target) { super(target); }

        @Override
        public void close() throws Exception {
            if (getTargetDataSource() instanceof AutoCloseable c) c.close();
        }

        @Override
        public Connection getConnection() throws SQLException { return point(super.getConnection()); }

        @Override
        public Connection getConnection(String username, String password) throws SQLException { return point(super.getConnection(username, password)); }

        static Connection point(Connection c) throws SQLException {
            String schema = TenantContext.schema();
            try (Statement s = c.createStatement()) {
                // schema names are validated in TenantContext ([a-z][a-z0-9_]*), so quoting is safe
                s.execute("set search_path to \"" + schema + "\"");
            } catch (SQLException e) {
                c.close();
                throw e;
            }
            return c;
        }
    }

    @org.springframework.context.annotation.Bean
    static BeanPostProcessor tenantDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DataSource ds && !(bean instanceof TenantDataSource) && "dataSource".equals(name)) return new TenantDataSource(ds);
                return bean;
            }
        };
    }
}
